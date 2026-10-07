package com.joohn.baixavideos.engine

import android.content.Context
import android.util.Log
import com.joohn.baixavideos.data.History
import com.joohn.baixavideos.service.DownloadService
import com.joohn.baixavideos.service.Notifications
import com.joohn.baixavideos.util.Links
import com.joohn.baixavideos.util.Platform
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Fila de downloads: cada item vira um processo do yt-dlp com saída monitorada linha a linha. */
object Downloads {
    private const val TAG = "Downloads"
    private const val MAX_PARALLEL = 2
    private const val PROGRESS_INTERVAL_MS = 250L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val slots = Semaphore(MAX_PARALLEL)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val historyLock = Mutex()
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
        scope.launch {
            val history = History.load(app)
            _tasks.update { current -> current + history.filter { saved -> current.none { it.id == saved.id } } }
            val activeIds = _tasks.value.filter { it.status.isActive }.map { it.id }.toSet()
            workRoot().listFiles()?.filter { it.name !in activeIds }?.forEach { it.deleteRecursively() }
        }
    }

    fun find(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    fun enqueue(context: Context, url: String, mode: DownloadMode, quality: VideoQuality): DownloadTask {
        val task = DownloadTask(
            id = UUID.randomUUID().toString().replace("-", "").take(16),
            url = url.trim(),
            mode = mode,
            quality = quality,
            stage = "Na fila",
        )
        _tasks.update { listOf(task) + it }
        val job = scope.launch(start = CoroutineStart.LAZY) { runTask(task) }
        jobs[task.id] = job
        job.start()
        DownloadService.start(context)
        return task
    }

    fun retry(context: Context, id: String) {
        val old = find(id) ?: return
        remove(id)
        enqueue(context, old.url, old.mode, old.quality)
    }

    fun cancel(id: String) {
        cancelled += id
        jobs[id]?.cancel()
        mutate(id) { if (it.status.isActive) it.canceled() else it }
        scope.launch { YoutubeDL.destroyProcessById(id) }
    }

    fun remove(id: String) {
        if (find(id)?.status?.isActive == true) cancel(id)
        _tasks.update { list -> list.filterNot { it.id == id } }
        persistHistory()
    }

    /** Apaga os arquivos do aparelho e tira o item da lista. Retorna false se algum não pôde ser apagado. */
    fun deleteFiles(id: String): Boolean {
        val task = find(id) ?: return true
        val allDeleted = task.files.map { MediaSaver.delete(app, it) }.all { it }
        remove(id)
        return allDeleted
    }

    fun clearFinished() {
        _tasks.update { list -> list.filter { it.status.isActive } }
        persistHistory()
    }

    private suspend fun runTask(task: DownloadTask) {
        val id = task.id
        val workDir = File(workRoot(), id)
        try {
            slots.withPermit {
                mutate(id) { it.copy(status = TaskStatus.PREPARING, stage = "Preparando o motor") }
                if (Engine.update.value.inFlight) mutate(id) { it.copy(stage = "Atualizando o motor") }
                Engine.awaitReady()
                mutate(id) { it.copy(stage = "Procurando o vídeo") }
                workDir.deleteRecursively()
                workDir.mkdirs()
                download(task, workDir)
            }
        } catch (e: CancellationException) {
            mutate(id) { if (it.status.isActive) it.canceled() else it }
            throw e
        } catch (e: EngineUnavailableException) {
            fail(id, task, e.message.orEmpty(), friendly = "O motor de download não iniciou. Toque no aviso do motor, na tela inicial, para tentar de novo.")
        } catch (e: Exception) {
            Log.e(TAG, "Download $id falhou", e)
            fail(id, task, e.message ?: e.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) { workDir.deleteRecursively() }
            jobs.remove(id)
            cancelled.remove(id)
        }
    }

    private suspend fun download(task: DownloadTask, workDir: File) {
        val id = task.id
        val platform = Links.platformOf(task.url)
        val cookies = if (platform == Platform.INSTAGRAM) InstagramSession.copyFor(app, workDir) else null
        val request = buildRequest(task, workDir, cookies)
        val run = RunState(task.mode)

        val failure = Engine.withProcess {
            if (id in cancelled) return@withProcess null
            try {
                YoutubeDL.execute(request, id, true) { _, _, line -> onLine(id, line, run) }
                null
            } catch (e: Exception) {
                e
            }
        }

        if (id in cancelled || failure is YoutubeDL.CanceledException) {
            mutate(id) { it.canceled() }
            return
        }
        if (failure != null) {
            val raw = run.errors.lastOrNull() ?: failure.message?.trim().orEmpty().ifEmpty { failure.javaClass.simpleName }
            val details = (run.errors + listOfNotNull(failure.message?.trim())).distinct().joinToString("\n")
            fail(id, task, raw, details)
            return
        }

        val produced = run.files.map(::File).filter { it.isFile }.ifEmpty {
            workDir.listFiles()?.filter { it.isFile && !it.name.startsWith('.') && it.extension !in TEMP_EXTENSIONS }
                .orEmpty()
        }
        if (produced.isEmpty()) {
            fail(id, task, "O download terminou, mas nenhum arquivo foi gerado.")
            return
        }

        mutate(id) {
            it.copy(status = TaskStatus.SAVING, stage = "Salvando em ${MediaSaver.publicPath}", progress = null,
                speedBps = null, etaSeconds = null)
        }
        val saved = try {
            produced.map { MediaSaver.save(app, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao salvar $id", e)
            val reason = e.message ?: e.javaClass.simpleName
            fail(id, task, reason, friendly = "Baixei o vídeo, mas não consegui salvar em Download: $reason")
            return
        }
        val done = mutate(id) {
            it.copy(status = TaskStatus.DONE, files = saved, progress = 1f, stage = null, speedBps = null,
                etaSeconds = null, finishedAt = System.currentTimeMillis())
        }
        persistHistory()
        done?.let { Notifications.finished(app, it) }
    }

    private fun onLine(id: String, line: String, run: RunState) {
        if (id in cancelled) {
            YoutubeDL.destroyProcessById(id)
            return
        }
        when (val event = OutputParser.parse(line) ?: return) {
            is OutputParser.Event.Meta -> {
                run.itemIndex = event.index
                run.itemCount = event.count
                mutate(id) {
                    it.copy(
                        status = TaskStatus.DOWNLOADING,
                        title = event.title ?: it.title,
                        uploader = event.uploader ?: it.uploader,
                        thumbnail = event.thumbnail ?: it.thumbnail,
                        itemIndex = event.index,
                        itemCount = event.count,
                        stage = run.label("Baixando"),
                        progress = null,
                    )
                }
            }
            is OutputParser.Event.Progress -> {
                val overall = run.overall(event)
                if (!run.shouldEmit()) return
                mutate(id) {
                    it.copy(
                        status = TaskStatus.DOWNLOADING,
                        stage = run.label(
                            when (event.stream) {
                                OutputParser.Stream.VIDEO_ONLY -> "Baixando vídeo"
                                OutputParser.Stream.AUDIO_ONLY -> "Baixando áudio"
                                OutputParser.Stream.MUXED -> "Baixando"
                            },
                        ),
                        progress = overall,
                        speedBps = event.speed,
                        etaSeconds = event.eta,
                    )
                }
            }
            is OutputParser.Event.PostProcess -> if (event.status == "started") {
                mutate(id) {
                    it.copy(
                        status = TaskStatus.PROCESSING,
                        stage = run.label(OutputParser.postProcessLabel(event.name, run.mode)),
                        progress = null,
                        speedBps = null,
                        etaSeconds = null,
                    )
                }
            }
            is OutputParser.Event.File -> run.files += event.path
            is OutputParser.Event.Error -> run.errors += ErrorTranslator.clean(event.message)
        }
    }

    private fun buildRequest(task: DownloadTask, workDir: File, cookies: File?): YoutubeDLRequest =
        YoutubeDLRequest(task.url).apply {
            addOption("--ignore-config")
            addOption("--no-update")
            addOption("--no-simulate")
            addOption("--newline")
            addOption("--progress")
            addOption("--no-mtime")
            addOption("--no-playlist")
            addOption("--playlist-items", "1:50")
            addOption("--concurrent-fragments", 4)
            addOption("--cache-dir", File(app.cacheDir, "yt-dlp").absolutePath)
            // Se a versão do solucionador de desafios do YouTube embutida ficar velha,
            // o yt-dlp busca a atual no GitHub (roda no QuickJS do youtubedl-android).
            addOption("--remote-components", "ejs:github")
            addOption("--print", OutputParser.metaTemplate)
            addOption("--print", OutputParser.fileTemplate)
            addOption("--progress-template", OutputParser.progressTemplate)
            addOption("--progress-template", OutputParser.postprocessTemplate)
            addOption("-o", File(workDir, "%(title).80B [%(id)s].%(ext)s").absolutePath)
            if (cookies != null) addOption("--cookies", cookies.absolutePath)
            when (task.mode) {
                DownloadMode.VIDEO -> {
                    // Prioriza a resolução escolhida; no empate, H.264 + AAC (toca em qualquer aparelho).
                    val res = task.quality.maxHeight?.let { "res:$it" } ?: "res"
                    addOption("-S", "$res,+codec:avc:m4a")
                    addOption("--merge-output-format", "mp4")
                }
                DownloadMode.AUDIO -> {
                    addOption("-f", "ba/b")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "192K")
                    addOption("--embed-metadata")
                }
            }
        }

    private fun fail(
        id: String,
        task: DownloadTask,
        raw: String,
        details: String = raw,
        friendly: String = ErrorTranslator.friendly(raw, Links.platformOf(task.url), InstagramSession.loggedIn.value),
    ) {
        val failed = mutate(id) {
            it.copy(status = TaskStatus.FAILED, error = friendly, errorDetails = details, stage = null,
                progress = null, speedBps = null, etaSeconds = null, finishedAt = System.currentTimeMillis())
        }
        failed?.let { Notifications.finished(app, it) }
    }

    private fun DownloadTask.canceled() = copy(
        status = TaskStatus.CANCELED, stage = null, progress = null, speedBps = null, etaSeconds = null,
        finishedAt = System.currentTimeMillis(),
    )

    private fun mutate(id: String, transform: (DownloadTask) -> DownloadTask): DownloadTask? {
        var result: DownloadTask? = null
        _tasks.update { list ->
            list.map { task -> if (task.id == id) transform(task).also { result = it } else task }
        }
        return result
    }

    private fun persistHistory() {
        if (!::app.isInitialized) return
        scope.launch {
            historyLock.withLock {
                runCatching { History.save(app, _tasks.value) }
                    .onFailure { Log.w(TAG, "Não foi possível salvar o histórico", it) }
            }
        }
    }

    private fun workRoot() = File(app.cacheDir, "downloads")

    private val TEMP_EXTENSIONS = setOf("part", "ytdl", "temp", "tmp", "json")

    /** Estado de um processo em andamento; só é tocado pela thread que lê a saída do yt-dlp. */
    private class RunState(val mode: DownloadMode) {
        val files = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var itemIndex: Int? = null
        var itemCount: Int? = null
        private var sawVideoOnly = false
        private var lastEmit = 0L

        fun shouldEmit(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastEmit < PROGRESS_INTERVAL_MS) return false
            lastEmit = now
            return true
        }

        /** Vídeo e áudio separados (YouTube) viram uma barra só: 85% vídeo, 15% áudio. */
        fun overall(p: OutputParser.Event.Progress): Float? {
            val fraction = p.fraction ?: return null
            return when (p.stream) {
                OutputParser.Stream.VIDEO_ONLY -> {
                    sawVideoOnly = true
                    fraction * 0.85f
                }
                OutputParser.Stream.AUDIO_ONLY ->
                    if (mode == DownloadMode.VIDEO && sawVideoOnly) 0.85f + fraction * 0.15f else fraction
                OutputParser.Stream.MUXED -> fraction
            }
        }

        fun label(text: String): String {
            val count = itemCount ?: return text
            val index = itemIndex ?: return text
            return if (count > 1) "Item $index de $count · $text" else text
        }
    }
}
