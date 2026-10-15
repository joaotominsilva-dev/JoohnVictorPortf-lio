package com.joohn.baixavideos.engine

import android.content.Context
import android.util.Log
import com.joohn.baixavideos.BuildConfig
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
import kotlinx.coroutines.delay
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
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Fila de downloads: cada item vira um processo do yt-dlp com saída monitorada linha a linha. */
object Downloads {
    private const val TAG = "Downloads"
    private const val MAX_PARALLEL = 2
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val OUTPUT_TAG = "YtDlpOut"

    /** Sem nenhuma linha de saída por este tempo, o corte direto é dado como travado. */
    private const val STALL_MS = 60_000L

    /** No download completo seguido de corte, a barra vai até 85% baixando e o resto cortando. */
    private const val DOWNLOAD_SHARE = 0.85f

    /** Baixando só o trecho, a barra vai até 70% e o acerto do corte ocupa o resto. */
    private const val SECTION_SHARE = 0.7f

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

    /** Põe um download na fila. [info], quando já lido na prévia, mostra título e miniatura de imediato. */
    fun enqueue(
        context: Context,
        url: String,
        mode: DownloadMode,
        quality: VideoQuality,
        trim: Trim? = null,
        info: VideoInfo? = null,
    ): DownloadTask {
        val task = DownloadTask(
            id = UUID.randomUUID().toString().replace("-", "").take(16),
            url = url.trim(),
            mode = mode,
            quality = quality,
            trim = trim,
            durationSec = info?.durationSec,
            title = info?.title,
            uploader = info?.uploader,
            thumbnail = info?.thumbnail,
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
        val info = VideoInfo(old.url, old.title, old.uploader, old.thumbnail, durationSec = old.durationSec)
        enqueue(context, old.url, old.mode, old.quality, old.trim, info)
    }

    fun cancel(id: String) {
        cancelled += id
        jobs[id]?.cancel()
        mutate(id) { if (it.status.isActive) it.canceled() else it }
        cutProcesses.remove(id)?.destroy()
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

    /** Como baixar um corte. Só o teste instrumentado muda isto, para exercitar cada caminho. */
    enum class TrimPlan { SECTIONS, FULL_THEN_CUT }

    /** Usado pelos testes para forçar um caminho de corte; null deixa o app escolher. */
    @Volatile
    var forcedTrimPlan: TrimPlan? = null

    /** Quem recodifica o vídeo no aparelho. Só os testes forçam um dos dois. */
    enum class CutEngine { TRANSFORMER, FFMPEG }

    @Volatile
    var forcedCutEngine: CutEngine? = null

    private val cutProcesses = ConcurrentHashMap<String, Process>()

    private sealed interface RunResult {
        data class Done(val files: List<File>) : RunResult
        data object Canceled : RunResult
        data class Failed(val raw: String, val details: String, val reachedDownload: Boolean, val stalled: Boolean) :
            RunResult
    }

    private suspend fun download(task: DownloadTask, workDir: File) {
        val id = task.id
        val trim = task.trim
        if (trim == null) {
            when (val result = runYtDlp(task, workDir, sections = false)) {
                is RunResult.Done -> save(task, result.files)
                RunResult.Canceled -> mutate(id) { it.canceled() }
                is RunResult.Failed -> fail(id, task, result.raw, result.details)
            }
            return
        }
        when (forcedTrimPlan ?: TrimPlan.SECTIONS) {
            TrimPlan.FULL_THEN_CUT -> downloadThenCut(task, trim, File(workDir, "inteiro"))
            TrimPlan.SECTIONS -> when (
                val result = runYtDlp(
                    task, workDir, sections = true,
                    progressShare = if (task.mode == DownloadMode.VIDEO) SECTION_SHARE else 1f,
                )
            ) {
                is RunResult.Done ->
                    if (task.mode == DownloadMode.VIDEO) finishSections(task, trim, result.files, workDir) else save(task, result.files)
                RunResult.Canceled -> mutate(id) { it.canceled() }
                is RunResult.Failed -> if (result.stalled || result.reachedDownload) {
                    // O site respondeu, mas baixar só o trecho travou ou falhou: baixa inteiro e corta aqui.
                    Log.w(TAG, "Corte direto falhou para $id (travou=${result.stalled}); usando download completo + corte local")
                    mutate(id) {
                        it.copy(status = TaskStatus.DOWNLOADING, stage = "Baixando o vídeo inteiro para cortar",
                            progress = null, speedBps = null, etaSeconds = null)
                    }
                    downloadThenCut(task, trim, File(workDir, "inteiro"))
                } else {
                    fail(id, task, result.raw, result.details)
                }
            }
        }
    }

    /**
     * O trecho baixado por cópia começa no quadro-chave anterior ao início (o MP4 marca onde
     * começar a mostrar). Recodificar no aparelho deixa o arquivo começando no segundo exato em
     * qualquer player; se não der, o arquivo copiado fica como está.
     */
    private suspend fun finishSections(task: DownloadTask, trim: Trim, files: List<File>, workDir: File) {
        val id = task.id
        val finals = files.map { input ->
            if (id in cancelled) {
                mutate(id) { it.canceled() }
                return
            }
            val output = File(File(workDir, "corte").apply { mkdirs() }, input.name)
            if (cutLocally(task, input, output, startSec = null, trim.lengthSec, SECTION_SHARE)) {
                output
            } else {
                lastCutError.remove(id)
                input
            }
        }
        if (id in cancelled) {
            mutate(id) { it.canceled() }
            return
        }
        save(task, finals)
    }

    /**
     * Roda o corte local mostrando o avanço; devolve false se não conseguiu. Vídeo vai primeiro
     * pelo Media3 Transformer (codificador de hardware, recodifica só o começo); o FFmpeg é a reserva.
     */
    private suspend fun cutLocally(
        task: DownloadTask,
        input: File,
        output: File,
        startSec: Long?,
        lengthSec: Long,
        from: Float,
    ): Boolean {
        val id = task.id
        mutate(id) {
            it.copy(status = TaskStatus.PROCESSING, stage = "Ajustando o corte", progress = from,
                speedBps = null, etaSeconds = null)
        }
        var lastEmit = 0L
        val video = !output.extension.equals("mp3", ignoreCase = true)
        if (video && forcedCutEngine != CutEngine.FFMPEG) {
            try {
                val startMs = (startSec ?: 0L) * 1000
                TransformerCutter.cut(app, input, output, startMs, startMs + lengthSec * 1000) { fraction ->
                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                        lastEmit = now
                        mutate(id) { it.copy(progress = from + (1 - from) * fraction) }
                    }
                }
                if (output.isFile && output.length() > 0L) return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Transformer não cortou $id; usando o FFmpeg", e)
                if (forcedCutEngine == CutEngine.TRANSFORMER) {
                    lastCutError[id] = e
                    return false
                }
            }
            output.delete()
            if (id in cancelled) return false
        }
        return try {
            LocalCutter.cut(
                app, input, output, startSec, lengthSec, task.quality,
                register = { cutProcesses[id] = it },
                isCancelled = { id in cancelled },
                onProgress = { fraction ->
                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                        lastEmit = now
                        mutate(id) { it.copy(progress = from + (1 - from) * fraction) }
                    }
                },
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Corte local falhou para $id", e)
            lastCutError[id] = e
            false
        } finally {
            cutProcesses.remove(id)
        }
    }

    private val lastCutError = ConcurrentHashMap<String, Exception>()

    private suspend fun downloadThenCut(task: DownloadTask, trim: Trim, dir: File) {
        val id = task.id
        dir.deleteRecursively()
        dir.mkdirs()
        val downloaded = when (val result = runYtDlp(task, dir, sections = false, progressShare = DOWNLOAD_SHARE)) {
            is RunResult.Done -> result.files
            RunResult.Canceled -> {
                mutate(id) { it.canceled() }
                return
            }
            is RunResult.Failed -> {
                fail(id, task, result.raw, result.details)
                return
            }
        }
        val section = " trecho ${fileTime(trim.startSec)}-${fileTime(trim.endSec)}"
        val cuts = mutableListOf<File>()
        for (input in downloaded) {
            if (id in cancelled) {
                mutate(id) { it.canceled() }
                return
            }
            val ext = if (task.mode == DownloadMode.AUDIO) "mp3" else "mp4"
            val output = File(File(dir, "corte").apply { mkdirs() }, input.nameWithoutExtension.take(120) + section + "." + ext)
            if (!cutLocally(task, input, output, trim.startSec, trim.lengthSec, DOWNLOAD_SHARE)) {
                if (id in cancelled) {
                    mutate(id) { it.canceled() }
                    return
                }
                val error = lastCutError.remove(id)
                val details = (error as? CutException)?.details ?: error?.message.orEmpty()
                fail(id, task, error?.message ?: "Falha ao cortar", details, friendly = "Não consegui cortar o trecho no aparelho.")
                return
            }
            input.delete()
            cuts += output
        }
        save(task, cuts)
    }

    /** Roda um processo do yt-dlp e devolve os arquivos gerados em [dir]. */
    private suspend fun runYtDlp(
        task: DownloadTask,
        dir: File,
        sections: Boolean,
        progressShare: Float = 1f,
    ): RunResult {
        val id = task.id
        val platform = Links.platformOf(task.url)
        val cookies = if (platform == Platform.INSTAGRAM) InstagramSession.copyFor(app, dir) else null
        val request = buildRequest(task, dir, cookies, sections)
        val run = RunState(task.mode, trimLengthSec = task.trim?.lengthSec?.takeIf { sections }, share = progressShare)

        // Ao baixar só o trecho, quem baixa é o FFmpeg; se ele ficar mudo por muito tempo, travou.
        val watchdog = if (sections) {
            scope.launch {
                while (true) {
                    delay(5_000)
                    if (System.currentTimeMillis() - run.lastLineAt > STALL_MS) {
                        Log.w(TAG, "Download $id sem resposta há ${STALL_MS / 1000}s; interrompendo")
                        run.stalled = true
                        YoutubeDL.destroyProcessById(id)
                        break
                    }
                }
            }
        } else {
            null
        }
        val failure = try {
            Engine.withProcess {
                if (id in cancelled) return@withProcess null
                try {
                    YoutubeDL.execute(request, id, true) { _, _, line -> onLine(id, line, run) }
                    null
                } catch (e: Exception) {
                    e
                }
            }
        } finally {
            watchdog?.cancel()
        }

        if (id in cancelled) return RunResult.Canceled
        if (run.stalled) {
            return RunResult.Failed("O download do trecho parou de responder.", run.errors.joinToString("\n"),
                reachedDownload = true, stalled = true)
        }
        if (failure is YoutubeDL.CanceledException) return RunResult.Canceled
        if (failure != null) {
            val raw = run.errors.lastOrNull() ?: failure.message?.trim().orEmpty().ifEmpty { failure.javaClass.simpleName }
            val details = (run.errors + listOfNotNull(failure.message?.trim())).distinct().joinToString("\n")
            return RunResult.Failed(raw, details, reachedDownload = run.reachedDownload, stalled = false)
        }
        val produced = run.files.map(::File).filter { it.isFile }.ifEmpty {
            dir.listFiles()?.filter { it.isFile && !it.name.startsWith('.') && it.extension !in TEMP_EXTENSIONS }
                .orEmpty()
        }
        if (produced.isEmpty()) {
            return RunResult.Failed("O download terminou, mas nenhum arquivo foi gerado.", run.errors.joinToString("\n"),
                reachedDownload = true, stalled = false)
        }
        return RunResult.Done(produced)
    }

    private suspend fun save(task: DownloadTask, produced: List<File>) {
        val id = task.id
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
        run.lastLineAt = System.currentTimeMillis()
        if (id in cancelled) {
            YoutubeDL.destroyProcessById(id)
            return
        }
        val event = OutputParser.parse(line)
        if (event == null) {
            if (BuildConfig.DEBUG && line.isNotBlank()) Log.d(OUTPUT_TAG, line.take(400))
            return
        }
        when (event) {
            is OutputParser.Event.Meta -> {
                run.itemIndex = event.index
                run.itemCount = event.count
                run.reachedDownload = true
                mutate(id) {
                    it.copy(
                        status = TaskStatus.DOWNLOADING,
                        title = event.title ?: it.title,
                        uploader = event.uploader ?: it.uploader,
                        thumbnail = event.thumbnail ?: it.thumbnail,
                        itemIndex = event.index,
                        itemCount = event.count,
                        stage = run.label(if (run.trimLengthSec != null) "Baixando o trecho" else "Baixando"),
                        progress = if (run.share < 1f) 0f else null,
                    )
                }
            }
            is OutputParser.Event.Progress -> {
                if (run.trimLengthSec != null) return // no corte direto, quem informa o avanço é o FFmpeg
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
                        progress = overall?.let { f -> f * run.share },
                        speedBps = event.speed,
                        etaSeconds = event.eta,
                    )
                }
            }
            is OutputParser.Event.FfmpegTime -> {
                val length = run.trimLengthSec ?: return
                if (!run.shouldEmit()) return
                val fraction = (event.seconds / length).toFloat().coerceIn(0f, 0.99f) * run.share
                mutate(id) {
                    it.copy(status = TaskStatus.DOWNLOADING, stage = run.label("Baixando o trecho"),
                        progress = fraction, speedBps = null, etaSeconds = null)
                }
            }
            is OutputParser.Event.PostProcess -> if (event.status == "started") {
                mutate(id) {
                    it.copy(
                        status = TaskStatus.PROCESSING,
                        stage = run.label(OutputParser.postProcessLabel(event.name, run.mode)),
                        progress = if (run.share < 1f) run.share else null,
                        speedBps = null,
                        etaSeconds = null,
                    )
                }
            }
            is OutputParser.Event.File -> run.files += event.path
            is OutputParser.Event.Error -> run.errors += ErrorTranslator.clean(event.message)
        }
    }

    private fun buildRequest(task: DownloadTask, workDir: File, cookies: File?, sections: Boolean): YoutubeDLRequest =
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
            val trim = task.trim?.takeIf { sections }
            val section = trim?.let { " trecho ${fileTime(it.startSec)}-${fileTime(it.endSec)}" }.orEmpty()
            addOption("-o", File(workDir, "%(title).70B [%(id)s]$section.%(ext)s").absolutePath)
            if (cookies != null) addOption("--cookies", cookies.absolutePath)
            if (trim != null) {
                // Baixa só o trecho, copiando os dados sem recodificar (rápido). O início exato é
                // acertado depois, no aparelho, pelo codificador de hardware.
                addOption("--download-sections", "*${trim.startSec}-${trim.endSec}")
            }
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

    /** 754 -> "12m34s"; 3725 -> "1h02m05s" (nome de arquivo sem dois-pontos). */
    private fun fileTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%dh%02dm%02ds", h, m, s)
        else String.format(Locale.US, "%02dm%02ds", m, s)
    }

    private val TEMP_EXTENSIONS = setOf("part", "ytdl", "temp", "tmp", "json")

    /**
     * Estado de um processo em andamento; tocado pela thread que lê a saída do yt-dlp (e só
     * lido pelo vigia de travamento). [trimLengthSec] existe quando o yt-dlp baixa só o trecho;
     * [share] é a fração da barra que este processo ocupa.
     */
    private class RunState(val mode: DownloadMode, val trimLengthSec: Long?, val share: Float) {
        val files = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var itemIndex: Int? = null
        var itemCount: Int? = null

        @Volatile
        var lastLineAt = System.currentTimeMillis()

        @Volatile
        var stalled = false

        /** O yt-dlp já leu os dados do vídeo e começou a baixar. */
        var reachedDownload = false
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
