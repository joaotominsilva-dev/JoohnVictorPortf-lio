package com.charis.shoot

import android.content.Context
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil.ImageLoader
import com.charis.shoot.culling.Analyzer
import com.charis.shoot.culling.Insights
import com.charis.shoot.culling.Sensitivity
import com.charis.shoot.culling.computeInsights
import com.charis.shoot.drive.AuthException
import com.charis.shoot.drive.AuthManager
import com.charis.shoot.drive.DriveClient
import com.charis.shoot.drive.DriveFolder
import com.charis.shoot.drive.Photo
import com.charis.shoot.edit.Adjust
import com.charis.shoot.edit.Editor
import com.charis.shoot.source.DriveSource
import com.charis.shoot.source.LocalImages
import com.charis.shoot.source.LocalSource
import com.charis.shoot.source.Loaded
import com.charis.shoot.source.PhotoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

sealed interface Screen {
    data object Login : Screen
    data object Folders : Screen
    data object Grid : Screen
    data class Viewer(val ids: List<String>, val start: Int) : Screen
    data class Editor(val photoId: String, val back: Screen) : Screen
}

enum class Filter(val label: String) {
    ALL("Todas"),
    PICKS("Escolhidas"),
    REJECTS("Rejeitadas"),
    UNMARKED("Sem marcação"),
    BLURRY("Desfocadas"),
    EYES("Olhos fechados"),
    DUPES("Duplicadas"),
    STARS("3★ ou mais"),
}

enum class SeparateWhat(val label: String) { PICKS("Escolhidas"), REJECTS("Rejeitadas"), STARRED("Com estrelas") }

data class Crumb(val id: String, val name: String)

data class RecentFolder(val uri: String, val name: String)

class CharisApp(val ctx: Context, private val auth: AuthManager) {
    private val scope = MainScope()
    private val prefs = ctx.getSharedPreferences("charis", Context.MODE_PRIVATE)
    val snack = SnackbarHostState()
    val sha1: String = AuthManager.signingSha1(ctx)

    val imageLoader: ImageLoader = ImageLoader.Builder(ctx)
        .components { add(LocalImages.ThumbFetcher.Factory(ctx)) }
        .crossfade(true)
        .build()

    /** Abre o seletor de pastas do Android (definido pela Activity). */
    var pickFolder: () -> Unit = {}

    var dark by mutableStateOf(prefs.getBoolean("dark", true))
        private set
    var screen by mutableStateOf<Screen>(Screen.Login)
    var token by mutableStateOf<String?>(null)
        private set
    var userName by mutableStateOf("")
        private set
    var userEmail by mutableStateOf("")
        private set
    var loginError by mutableStateOf<String?>(null)
        private set
    var loggingIn by mutableStateOf(false)
        private set
    var recents by mutableStateOf(loadRecents())
        private set

    val signedIn get() = token != null
    val home: Screen get() = if (signedIn) Screen.Folders else Screen.Login

    val drive = DriveClient { force ->
        val t = auth.getToken(interactive = true, forceRefresh = force)
        withContext(Dispatchers.Main) { token = t }
        t
    }
    private val analyzer by lazy { Analyzer() }

    // Pastas do Drive
    val crumbs = mutableStateListOf<Crumb>()
    var shared by mutableStateOf(false)
        private set
    var folders by mutableStateOf<List<DriveFolder>>(emptyList())
        private set
    var foldersLoading by mutableStateOf(false)
        private set

    // Culling
    var source by mutableStateOf<PhotoSource?>(null)
        private set
    var photos by mutableStateOf<List<Photo>>(emptyList())
        private set
    var photosLoading by mutableStateOf(false)
        private set
    var insights by mutableStateOf(Insights())
        private set
    var filter by mutableStateOf(Filter.ALL)
    var sensitivity by mutableStateOf(Sensitivity.entries[prefs.getInt("sens", 1).coerceIn(0, 2)])
        private set

    var working by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0f)
        private set
    var progressText by mutableStateOf("")
        private set
    private var job: Job? = null
    private var flushJob: Job? = null

    // ---------- Atualização ----------

    val versionName: String = Updater.installedName(ctx)
    var update by mutableStateOf<Updater.Info?>(null)
        private set
    var showUpdate by mutableStateOf(false)
    var updateProgress by mutableStateOf<Float?>(null)
        private set
    var checkingUpdate by mutableStateOf(false)
        private set

    fun checkUpdate(manual: Boolean) {
        if (checkingUpdate) return
        if (!manual && System.currentTimeMillis() - prefs.getLong("lastUpdateCheck", 0) < 20 * 3600_000L) return
        checkingUpdate = true
        scope.launch {
            try {
                val info = Updater.latest(drive.http)
                prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply()
                if (info != null && info.code > Updater.installedCode(ctx)) {
                    update = info
                    showUpdate = true
                } else if (manual) {
                    toast(if (info == null) "Não consegui verificar agora. Confira a internet." else "Você já tem a versão mais nova ($versionName).")
                }
            } finally {
                checkingUpdate = false
            }
        }
    }

    fun installUpdate() {
        val info = update ?: return
        if (!Updater.canInstall(ctx)) {
            toast("Libere \"Instalar apps desconhecidos\" para o Charis Shoot e toque em Atualizar de novo.")
            Updater.openInstallPermission(ctx)
            return
        }
        if (updateProgress != null) return
        updateProgress = 0f
        scope.launch {
            try {
                flushNow()
                val apk = Updater.download(ctx, drive.http, info) { p -> scope.launch { updateProgress = p } }
                Updater.install(ctx, apk)
                showUpdate = false
            } catch (e: Exception) {
                toast("Atualização falhou: ${e.message}")
            } finally {
                updateProgress = null
            }
        }
    }

    fun toast(msg: String) {
        scope.launch { snack.showSnackbar(msg) }
    }

    fun toggleTheme() {
        dark = !dark
        prefs.edit().putBoolean("dark", dark).apply()
    }

    // ---------- Login ----------

    fun start() {
        checkUpdate(manual = false)
        if (prefs.getBoolean("loggedOut", false)) return
        scope.launch {
            val t = runCatching { auth.getToken(interactive = false) }.getOrNull() ?: return@launch
            token = t
            afterLogin()
        }
    }

    fun login() {
        if (loggingIn) return
        loggingIn = true
        loginError = null
        scope.launch {
            try {
                val t = auth.getToken(interactive = true)
                if (t == null) {
                    loginError = "Login não concluído."
                } else {
                    token = t
                    prefs.edit().putBoolean("loggedOut", false).apply()
                    afterLogin()
                }
            } catch (e: AuthException) {
                loginError = e.message
            } catch (e: Exception) {
                loginError = "Não foi possível entrar: ${e.message}"
            } finally {
                loggingIn = false
            }
        }
    }

    private suspend fun afterLogin() {
        runCatching { drive.about() }.onSuccess { userName = it.name; userEmail = it.email }
        if (screen == Screen.Login) screen = Screen.Folders
        openRoot(false)
    }

    fun logout() {
        job?.cancel()
        scope.launch {
            auth.signOut()
            token = null
            prefs.edit().putBoolean("loggedOut", true).apply()
            crumbs.clear()
            if (source?.isLocal != true) { photos = emptyList(); source = null }
            screen = Screen.Login
        }
    }

    // ---------- Pastas do Drive ----------

    fun openRoot(sharedWithMe: Boolean) {
        shared = sharedWithMe
        crumbs.clear()
        if (!sharedWithMe) crumbs += Crumb("root", "Meu Drive")
        loadFolders()
    }

    fun openFolder(f: DriveFolder) {
        crumbs += Crumb(f.id, f.name)
        loadFolders()
    }

    fun goToCrumb(index: Int) {
        while (crumbs.size > index + 1) crumbs.removeAt(crumbs.lastIndex)
        loadFolders()
    }

    fun folderBack(): Boolean {
        val min = if (shared) 0 else 1
        if (crumbs.size <= min) return false
        crumbs.removeAt(crumbs.lastIndex)
        loadFolders()
        return true
    }

    fun loadFolders() {
        val parent = crumbs.lastOrNull()?.id
        foldersLoading = true
        scope.launch {
            try {
                folders = drive.listFolders(parent, shared)
            } catch (e: Exception) {
                folders = emptyList()
                toast(e.message ?: "Erro ao listar pastas")
            } finally {
                foldersLoading = false
            }
        }
    }

    val canCull get() = crumbs.isNotEmpty()

    fun startCulling() {
        val c = crumbs.lastOrNull() ?: return
        openSource(DriveSource(drive, c.id, c.name))
    }

    // ---------- Pastas do aparelho (memória, cartão SD, HD/pendrive USB) ----------

    private fun loadRecents(): List<RecentFolder> = runCatching {
        val arr = JSONArray(prefs.getString("recents", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> RecentFolder(o.getString("uri"), o.getString("name")) } }
    }.getOrDefault(emptyList())

    private fun saveRecents(list: List<RecentFolder>) {
        recents = list
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
        prefs.edit().putString("recents", arr.toString()).apply()
    }

    /** Chamado pela Activity com a pasta escolhida no seletor. */
    fun onFolderPicked(tree: Uri) {
        val name = LocalSource.displayName(ctx, tree)
        saveRecents((listOf(RecentFolder(tree.toString(), name)) + recents.filter { it.uri != tree.toString() }).take(8))
        openSource(LocalSource(ctx, tree, name))
    }

    fun openRecent(r: RecentFolder) {
        val tree = Uri.parse(r.uri)
        val ok = ctx.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        if (!ok) {
            saveRecents(recents.filter { it.uri != r.uri })
            toast("Sem acesso a \"${r.name}\" (o HD ou cartão foi removido?). Escolha a pasta de novo.")
            pickFolder()
            return
        }
        openSource(LocalSource(ctx, tree, r.name))
    }

    fun forgetRecent(r: RecentFolder) {
        runCatching {
            ctx.contentResolver.releasePersistableUriPermission(
                Uri.parse(r.uri),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        saveRecents(recents.filter { it.uri != r.uri })
    }

    // ---------- Culling ----------

    private fun openSource(s: PhotoSource) {
        job?.cancel()
        source = s
        filter = Filter.ALL
        photos = emptyList()
        insights = Insights()
        screen = Screen.Grid
        loadPhotos()
    }

    fun closeGrid() {
        flushNow()
        screen = if (source?.isLocal == true) home else Screen.Folders
    }

    fun loadPhotos() {
        val s = source ?: return
        photosLoading = true
        scope.launch {
            try {
                photos = s.list()
                recompute()
                if (photos.isEmpty()) toast("Nenhuma foto nesta pasta.")
            } catch (e: Exception) {
                toast(e.message ?: "Erro ao carregar fotos")
            } finally {
                photosLoading = false
            }
        }
    }

    private fun recompute() {
        insights = computeInsights(photos, sensitivity)
    }

    fun changeSensitivity(s: Sensitivity) {
        sensitivity = s
        prefs.edit().putInt("sens", s.ordinal).apply()
        recompute()
    }

    fun byId(id: String) = photos.firstOrNull { it.id == id }

    fun visible(): List<Photo> = photos.filter { p ->
        when (filter) {
            Filter.ALL -> true
            Filter.PICKS -> p.isPick
            Filter.REJECTS -> p.isReject
            Filter.UNMARKED -> p.flag.isEmpty()
            Filter.BLURRY -> p.id in insights.blurry
            Filter.EYES -> p.eyes == "closed"
            Filter.DUPES -> insights.groupOf.containsKey(p.id)
            Filter.STARS -> p.rating >= 3
        }
    }

    private fun replace(id: String, props: Map<String, String>) {
        photos = photos.map { if (it.id == id) it.copy(props = props) else it }
    }

    /** Grava as marcações locais pouco depois da última mudança. */
    private fun scheduleFlush() {
        val s = source ?: return
        if (!s.isLocal) return
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(1500)
            runCatching { s.flush() }.onFailure { toast("Não salvou as marcações: ${it.message}") }
        }
    }

    fun flushNow() {
        val s = source ?: return
        if (!s.isLocal) return
        flushJob?.cancel()
        scope.launch { runCatching { s.flush() } }
    }

    private fun setProps(id: String, values: Map<String, String?>) {
        val s = source ?: return
        val p = byId(id) ?: return
        val before = p.props
        val local = HashMap(before).apply { values.forEach { (k, v) -> if (v == null) remove(k) else put(k, v) } }
        replace(id, local)
        recompute()
        scope.launch {
            try {
                replace(id, s.patch(p, values))
                recompute()
                scheduleFlush()
            } catch (e: Exception) {
                replace(id, before)
                recompute()
                toast("Não salvou: ${e.message}")
            }
        }
    }

    fun setFlag(id: String, flag: String) {
        val cur = byId(id)?.flag ?: return
        setProps(id, mapOf(Photo.K_FLAG to if (cur == flag) null else flag))
    }

    fun setRating(id: String, r: Int) {
        val cur = byId(id)?.rating ?: return
        setProps(id, mapOf(Photo.K_RATING to if (cur == r || r == 0) null else r.toString()))
    }

    fun setColor(id: String, c: String) {
        val cur = byId(id)?.color ?: return
        setProps(id, mapOf(Photo.K_COLOR to if (cur == c || c.isEmpty()) null else c))
    }

    // ---------- Trabalhos longos ----------

    fun cancelWork() {
        job?.cancel()
    }

    private fun runWork(label: String, block: suspend (PhotoSource) -> Unit) {
        if (working) return
        val s = source ?: return
        job = scope.launch {
            working = true
            progress = 0f
            progressText = label
            try {
                block(s)
            } catch (e: CancellationException) {
                toast("Interrompido.")
            } catch (e: Exception) {
                toast(e.message ?: "Erro")
            } finally {
                working = false
                recompute()
                if (s.isLocal) runCatching { withContext(kotlinx.coroutines.NonCancellable) { s.flush() } }
            }
        }
    }

    fun analyze(all: Boolean) = runWork("Analisando…") { s ->
        val todo = photos.filter { all || !it.analyzed }
        if (todo.isEmpty()) {
            toast("Todas as fotos já foram analisadas.")
            return@runWork
        }
        var done = 0
        var failed = 0
        val sem = Semaphore(if (s.isLocal) 2 else 3)
        coroutineScope {
            todo.map { p ->
                launch {
                    sem.withPermit {
                        try {
                            val bmp = s.analysisBitmap(p)
                            if (bmp == null) {
                                failed++
                            } else {
                                val res = analyzer.analyze(bmp)
                                bmp.recycle()
                                replace(p.id, s.patch(p, res))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed++
                        }
                        done++
                        progress = done.toFloat() / todo.size
                        progressText = "Analisando $done de ${todo.size}"
                        if (done % 10 == 0) recompute()
                    }
                }
            }.joinAll()
        }
        toast(if (failed == 0) "Análise concluída." else "Análise concluída. $failed sem prévia disponível.")
    }

    fun suggestionCount(): Pair<Int, Int> {
        val free = photos.filter { it.analyzed && it.flag.isEmpty() }
        val rej = free.count { insights.suggestReject(it) }
        return (free.size - rej) to rej
    }

    fun applySuggestion() = runWork("Aplicando sugestão…") { s ->
        val free = photos.filter { it.analyzed && it.flag.isEmpty() }
        var done = 0
        val sem = Semaphore(4)
        coroutineScope {
            free.map { p ->
                launch {
                    sem.withPermit {
                        val f = if (insights.suggestReject(p)) Photo.FLAG_REJECT else Photo.FLAG_PICK
                        runCatching { replace(p.id, s.patch(p, mapOf(Photo.K_FLAG to f))) }
                        done++
                        progress = done.toFloat() / free.size
                        progressText = "Marcando $done de ${free.size}"
                    }
                }
            }.joinAll()
        }
        toast("Sugestão aplicada em ${free.size} fotos.")
    }

    // ---------- Separar ----------

    fun separateCount(what: SeparateWhat) = photos.count { matches(it, what) }

    private fun matches(p: Photo, what: SeparateWhat) = when (what) {
        SeparateWhat.PICKS -> p.isPick
        SeparateWhat.REJECTS -> p.isReject
        SeparateWhat.STARRED -> p.rating > 0
    }

    fun separate(what: SeparateWhat, move: Boolean, folderName: String) = runWork("Separando…") { s ->
        val list = photos.filter { matches(it, what) }
        if (list.isEmpty()) {
            toast("Nenhuma foto para separar.")
            return@runWork
        }
        val name = folderName.trim().ifEmpty { "Charis Selecionadas" }
        val target = s.prepareTarget(name)
        var done = 0
        var failed = 0
        val sem = Semaphore(if (s.isLocal) 2 else 4)
        val moved = java.util.Collections.synchronizedSet(HashSet<String>())
        coroutineScope {
            list.map { p ->
                launch {
                    sem.withPermit {
                        try {
                            s.transfer(p, target, name, move)
                            if (move) moved += p.id
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed++
                        }
                        done++
                        progress = done.toFloat() / list.size
                        progressText = "${if (move) "Movendo" else "Copiando"} $done de ${list.size}"
                    }
                }
            }.joinAll()
        }
        if (moved.isNotEmpty()) photos = photos.filter { it.id !in moved }
        val verb = if (move) "movidas" else "copiadas"
        toast("${list.size - failed} fotos $verb para \"$name\"." + if (failed > 0) " $failed falharam." else "")
    }

    // ---------- Edição ----------

    suspend fun loadForEdit(p: Photo): Loaded = source?.loadForEdit(p) ?: throw IllegalStateException("Pasta fechada")

    suspend fun saveEdit(p: Photo, loaded: Loaded, adj: Adjust): String = withContext(Dispatchers.IO) {
        val s = source ?: throw IllegalStateException("Pasta fechada")
        val out = Editor.apply(loaded.full, adj)
        val jpeg = Editor.encodeJpeg(out, loaded.original, ctx.cacheDir)
        out.recycle()
        s.saveEdited(p, jpeg)
    }
}
