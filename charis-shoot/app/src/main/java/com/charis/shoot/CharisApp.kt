package com.charis.shoot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.max

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

class CharisApp(val ctx: Context, private val auth: AuthManager) {
    private val scope = MainScope()
    private val prefs = ctx.getSharedPreferences("charis", Context.MODE_PRIVATE)
    val snack = SnackbarHostState()
    val sha1: String = AuthManager.signingSha1(ctx)

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

    val drive = DriveClient { force ->
        val t = auth.getToken(interactive = true, forceRefresh = force)
        withContext(Dispatchers.Main) { token = t }
        t
    }
    private val analyzer by lazy { Analyzer() }

    // Pastas
    val crumbs = mutableStateListOf<Crumb>()
    var shared by mutableStateOf(false)
        private set
    var folders by mutableStateOf<List<DriveFolder>>(emptyList())
        private set
    var foldersLoading by mutableStateOf(false)
        private set

    // Culling
    var folder by mutableStateOf<Crumb?>(null)
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

    fun toast(msg: String) {
        scope.launch { snack.showSnackbar(msg) }
    }

    fun toggleTheme() {
        dark = !dark
        prefs.edit().putBoolean("dark", dark).apply()
    }

    // ---------- Login ----------

    fun start() {
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
        screen = Screen.Folders
        openRoot(false)
    }

    fun logout() {
        job?.cancel()
        scope.launch {
            auth.signOut()
            token = null
            prefs.edit().putBoolean("loggedOut", true).apply()
            crumbs.clear(); photos = emptyList(); folder = null
            screen = Screen.Login
        }
    }

    // ---------- Pastas ----------

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

    /** Volta uma pasta; devolve false se já está na raiz. */
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
        folder = c
        filter = Filter.ALL
        photos = emptyList()
        insights = Insights()
        screen = Screen.Grid
        loadPhotos()
    }

    fun loadPhotos() {
        val f = folder ?: return
        photosLoading = true
        scope.launch {
            try {
                photos = drive.listPhotos(f.id)
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

    /** Grava na nuvem com atualização imediata na tela (e desfaz se o Drive recusar). */
    private fun setProps(id: String, values: Map<String, String?>) {
        val p = byId(id) ?: return
        val before = p.props
        val local = HashMap(before).apply { values.forEach { (k, v) -> if (v == null) remove(k) else put(k, v) } }
        replace(id, local)
        recompute()
        scope.launch {
            try {
                replace(id, drive.patchProps(id, values))
                recompute()
            } catch (e: Exception) {
                replace(id, before)
                recompute()
                toast("Não salvou no Drive: ${e.message}")
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

    // ---------- Análise ----------

    fun cancelWork() {
        job?.cancel()
    }

    private fun runWork(label: String, block: suspend () -> Unit) {
        if (working) return
        job = scope.launch {
            working = true
            progress = 0f
            progressText = label
            try {
                block()
            } catch (e: CancellationException) {
                toast("Interrompido.")
            } catch (e: Exception) {
                toast(e.message ?: "Erro")
            } finally {
                working = false
                recompute()
            }
        }
    }

    fun analyze(all: Boolean) = runWork("Analisando…") {
        val todo = photos.filter { all || !it.analyzed }
        if (todo.isEmpty()) {
            toast("Todas as fotos já foram analisadas.")
            return@runWork
        }
        var done = 0
        var failed = 0
        val sem = Semaphore(3)
        kotlinx.coroutines.coroutineScope {
            todo.map { p ->
                launch {
                    sem.withPermit {
                        try {
                            val bmp = analysisBitmap(p)
                            if (bmp == null) {
                                failed++
                            } else {
                                val res = analyzer.analyze(bmp)
                                bmp.recycle()
                                replace(p.id, drive.patchProps(p.id, res))
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
        recompute()
        toast(if (failed == 0) "Análise concluída." else "Análise concluída. $failed sem prévia disponível.")
    }

    private suspend fun analysisBitmap(p: Photo): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = when {
            p.thumb(1024) != null -> drive.fetchThumb(p.thumb(1024)!!)
            p.decodable && p.size in 1..60_000_000 -> drive.downloadMedia(p.id)
            else -> return@withContext null
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= 1024) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Sugestão automática: só nas fotos ainda sem marcação. */
    fun suggestionCount(): Pair<Int, Int> {
        val free = photos.filter { it.analyzed && it.flag.isEmpty() }
        val rej = free.count { insights.suggestReject(it) }
        return (free.size - rej) to rej
    }

    fun applySuggestion() = runWork("Aplicando sugestão…") {
        val free = photos.filter { it.analyzed && it.flag.isEmpty() }
        var done = 0
        val sem = Semaphore(4)
        kotlinx.coroutines.coroutineScope {
            free.map { p ->
                launch {
                    sem.withPermit {
                        val f = if (insights.suggestReject(p)) Photo.FLAG_REJECT else Photo.FLAG_PICK
                        runCatching { replace(p.id, drive.patchProps(p.id, mapOf(Photo.K_FLAG to f))) }
                        done++
                        progress = done.toFloat() / free.size
                        progressText = "Marcando $done de ${free.size}"
                    }
                }
            }.joinAll()
        }
        toast("Sugestão aplicada em ${free.size} fotos.")
    }

    // ---------- Separar no Drive ----------

    fun separateCount(what: SeparateWhat) = photos.count { matches(it, what) }

    private fun matches(p: Photo, what: SeparateWhat) = when (what) {
        SeparateWhat.PICKS -> p.isPick
        SeparateWhat.REJECTS -> p.isReject
        SeparateWhat.STARRED -> p.rating > 0
    }

    fun separate(what: SeparateWhat, move: Boolean, folderName: String) = runWork("Separando…") {
        val f = folder ?: return@runWork
        val list = photos.filter { matches(it, what) }
        if (list.isEmpty()) {
            toast("Nenhuma foto para separar.")
            return@runWork
        }
        val target = drive.ensureFolder(f.id, folderName.trim().ifEmpty { "Charis Selecionadas" })
        var done = 0
        var failed = 0
        val sem = Semaphore(4)
        val moved = java.util.Collections.synchronizedSet(HashSet<String>())
        kotlinx.coroutines.coroutineScope {
            list.map { p ->
                launch {
                    sem.withPermit {
                        try {
                            if (move) {
                                drive.move(p.id, f.id, target); moved += p.id
                            } else drive.copy(p.id, target, p.name)
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
        toast("${list.size - failed} fotos $verb para \"$folderName\"." + if (failed > 0) " $failed falharam." else "")
    }

    // ---------- Edição ----------

    class Loaded(val original: ByteArray?, val full: Bitmap, val fromPreview: Boolean)

    suspend fun loadForEdit(p: Photo): Loaded = withContext(Dispatchers.IO) {
        if (p.decodable) {
            val bytes = drive.downloadMedia(p.id)
            Loaded(bytes, Editor.decode(bytes), false)
        } else {
            val link = p.thumb(3000) ?: throw IllegalStateException("Este arquivo não tem prévia no Drive para editar.")
            val bytes = drive.fetchThumb(link)
            Loaded(null, Editor.decode(bytes), true)
        }
    }

    suspend fun saveEdit(p: Photo, loaded: Loaded, adj: Adjust): String = withContext(Dispatchers.IO) {
        val f = folder ?: throw IllegalStateException("Pasta não encontrada")
        val out = Editor.apply(loaded.full, adj)
        val jpeg = Editor.encodeJpeg(out, loaded.original, ctx.cacheDir)
        out.recycle()
        val target = drive.ensureFolder(f.id, "Charis Editadas")
        val name = p.name.substringBeforeLast('.') + "_charis.jpg"
        drive.uploadJpeg(target, name, jpeg)
        name
    }
}
