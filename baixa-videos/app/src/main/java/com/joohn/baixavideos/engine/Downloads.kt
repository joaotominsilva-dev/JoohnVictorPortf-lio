package com.joohn.baixavideos.engine

import android.content.Context
import android.util.Log
import com.joohn.baixavideos.BuildConfig
import com.joohn.baixavideos.data.History
import com.joohn.baixavideos.engine.music.MusicTagger
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
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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
        reframe: Reframe? = null,
    ): DownloadTask {
        val task = DownloadTask(
            id = UUID.randomUUID().toString().replace("-", "").take(16),
            url = url.trim(),
            mode = mode,
            quality = quality,
            trim = trim,
            reframe = reframe.takeIf { mode == DownloadMode.VIDEO },
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
        enqueue(context, old.url, old.mode, old.quality, old.trim, info, old.reframe)
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
        data class Done(val files: List<File>, val music: Map<String, String> = emptyMap()) : RunResult
        data object Canceled : RunResult
        data class Failed(val raw: String, val details: String, val reachedDownload: Boolean, val stalled: Boolean) :
            RunResult
    }

    private suspend fun download(task: DownloadTask, workDir: File) {
        val id = task.id
        val trim = task.trim
        if (trim == null) {
            val reframing = task.mode == DownloadMode.VIDEO && task.reframe != null
            when (val result = runYtDlp(task, workDir, sections = false, progressShare = if (reframing) DOWNLOAD_SHARE else 1f)) {
                is RunResult.Done -> if (reframing) reframeAll(task, result.files, workDir) else save(task, result.files, result.music)
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
                    if (task.mode == DownloadMode.VIDEO) finishSections(task, trim, result.files, workDir)
                    else save(task, result.files, result.music)
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
            val output = File(File(workDir, "corte").apply { mkdirs() }, outputName(task, input.nameWithoutExtension, "mp4"))
            when {
                cutLocally(task, input, output, Clip.Section(trim.lengthSec), SECTION_SHARE) -> output
                // Sem recorte, o trecho copiado serve; com recorte, sairia no formato errado.
                task.reframe == null || id in cancelled -> {
                    lastCutError.remove(id)
                    input
                }
                else -> {
                    val error = lastCutError.remove(id)
                    val details = (error as? CutException)?.details ?: error?.message.orEmpty()
                    fail(id, task, error?.message ?: "Falha ao recortar", details,
                        friendly = "Não consegui deixar o trecho na ${task.reframe?.framing?.word}.")
                    return
                }
            }
        }
        if (id in cancelled) {
            mutate(id) { it.canceled() }
            return
        }
        save(task, finals)
    }

    /** O que o acerto no aparelho faz com o tempo do arquivo. */
    private sealed interface Clip {
        /** Trecho baixado por cópia: começa no quadro-chave anterior, então mede e acerta o início. */
        data class Section(val lengthSec: Long) : Clip

        /** Arquivo inteiro: fica só de [startSec] até [startSec] + [lengthSec]. */
        data class Range(val startSec: Long, val lengthSec: Long) : Clip

        /** Sem mexer no tempo: só recorta o quadro (vertical ou horizontal). */
        data object Whole : Clip
    }

    /**
     * Roda o corte e o recorte no aparelho mostrando o avanço; devolve false se não conseguiu.
     * Vídeo vai primeiro pelo Media3 Transformer (codificador de hardware; sem recorte, recodifica
     * só o começo do trecho); o FFmpeg é a reserva.
     */
    private suspend fun cutLocally(task: DownloadTask, input: File, output: File, clip: Clip, from: Float): Boolean {
        val id = task.id
        val video = !output.extension.equals("mp3", ignoreCase = true)
        val reframe = task.reframe.takeIf { video }
        val stage = when {
            reframe != null && clip == Clip.Whole -> "Deixando na ${reframe.framing.word}"
            reframe != null -> "Cortando e deixando na ${reframe.framing.word}"
            else -> "Ajustando o corte"
        }
        mutate(id) {
            it.copy(status = TaskStatus.PROCESSING, stage = stage, progress = from, speedBps = null, etaSeconds = null)
        }
        var lastEmit = 0L
        val report: (Float) -> Unit = { fraction ->
            val now = System.currentTimeMillis()
            if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                lastEmit = now
                mutate(id) { it.copy(progress = from + (1 - from) * fraction) }
            }
        }
        if (video && forcedCutEngine != CutEngine.FFMPEG) {
            try {
                val source: File
                val startMs: Long?
                val endMs: Long?
                when (clip) {
                    is Clip.Section -> {
                        // O trecho copiado começa no quadro-chave anterior, com tempo negativo antes do
                        // ponto pedido; o Transformer precisa de um arquivo com tempos a partir de zero.
                        val preroll = withContext(Dispatchers.IO) { LocalCutter.prerollSeconds(app, input) }
                            ?: throw IOException("não deu para medir o início do trecho")
                        if (preroll > 0.01) {
                            source = File(input.parentFile, "limpo-" + input.name)
                            val ok = withContext(Dispatchers.IO) { LocalCutter.remuxClean(app, input, source) }
                            if (!ok) throw IOException("não deu para preparar o trecho")
                        } else {
                            source = input
                        }
                        startMs = (preroll * 1000).roundToLong()
                        endMs = startMs + clip.lengthSec * 1000
                    }
                    is Clip.Range -> {
                        source = input
                        startMs = clip.startSec * 1000
                        endMs = startMs + clip.lengthSec * 1000
                    }
                    Clip.Whole -> {
                        source = input
                        startMs = null
                        endMs = null
                    }
                }
                val crop = reframe?.let { frameCrop(source, it) }
                if (clip == Clip.Whole && crop == null) throw IOException("nada para recortar")
                TransformerCutter.cut(app, source, output, startMs, endMs, crop, report)
                if (output.isFile && output.length() > 0L) return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Transformer não processou $id; usando o FFmpeg", e)
                if (forcedCutEngine == CutEngine.TRANSFORMER) {
                    lastCutError[id] = e
                    return false
                }
            }
            output.delete()
            if (id in cancelled) return false
        }
        val (startSec, lengthSec) = when (clip) {
            is Clip.Section -> null to clip.lengthSec
            is Clip.Range -> clip.startSec to clip.lengthSec
            Clip.Whole -> null to null
        }
        val totalSec = lengthSec?.toDouble() ?: VideoShape.durationSec(input) ?: task.durationSec ?: 1.0
        return try {
            LocalCutter.cut(
                app, input, output, startSec, lengthSec, totalSec, task.quality, reframe,
                register = { cutProcesses[id] = it },
                isCancelled = { id in cancelled },
                onProgress = report,
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

    /** Janela e tamanho final do recorte; null quando o vídeo já está no formato pedido. */
    private fun frameCrop(file: File, reframe: Reframe): TransformerCutter.FrameCrop? {
        val (width, height) = VideoShape.size(file) ?: throw IOException("não deu para ler o tamanho do vídeo")
        val window = reframe.window(width.toFloat() / height) ?: return null
        val cropWidth = ((width * window.width).roundToInt() / 2 * 2).coerceAtLeast(2)
        val cropHeight = ((height * window.height).roundToInt() / 2 * 2).coerceAtLeast(2)
        return TransformerCutter.FrameCrop(window, cropWidth, cropHeight)
    }

    /** Nome do arquivo final: ganha " vertical" ou " horizontal" quando o quadro é recortado. */
    private fun outputName(task: DownloadTask, base: String, ext: String): String {
        val word = task.reframe?.takeIf { ext != "mp3" }?.let { " ${it.framing.word}" }.orEmpty()
        return base.take(120) + word + "." + ext
    }

    /**
     * Sem corte de tempo: recorta cada vídeo baixado para o formato pedido. Vídeo que já está no
     * formato fica como veio; se o recorte falhar, avisa em vez de salvar no formato errado.
     */
    private suspend fun reframeAll(task: DownloadTask, files: List<File>, workDir: File) {
        val id = task.id
        val reframe = task.reframe ?: return save(task, files)
        val finals = mutableListOf<File>()
        for (input in files) {
            if (id in cancelled) {
                mutate(id) { it.canceled() }
                return
            }
            if (input.extension.lowercase() !in VIDEO_EXTENSIONS) {
                finals += input
                continue
            }
            val size = VideoShape.size(input)
            if (size != null && reframe.window(size.first.toFloat() / size.second) == null) {
                Log.i(TAG, "${input.name} já está na ${reframe.framing.word}")
                finals += input
                continue
            }
            val output = File(File(workDir, "formato").apply { mkdirs() }, outputName(task, input.nameWithoutExtension, "mp4"))
            if (!cutLocally(task, input, output, Clip.Whole, DOWNLOAD_SHARE)) {
                if (id in cancelled) {
                    mutate(id) { it.canceled() }
                    return
                }
                val error = lastCutError.remove(id)
                val details = (error as? CutException)?.details ?: error?.message.orEmpty()
                fail(id, task, error?.message ?: "Falha ao recortar", details,
                    friendly = "Não consegui deixar o vídeo na ${reframe.framing.word}.")
                return
            }
            input.delete()
            finals += output
        }
        save(task, finals)
    }

    private val lastCutError = ConcurrentHashMap<String, Exception>()

    private suspend fun downloadThenCut(task: DownloadTask, trim: Trim, dir: File) {
        val id = task.id
        dir.deleteRecursively()
        dir.mkdirs()
        val fullRun = runYtDlp(task, dir, sections = false, progressShare = DOWNLOAD_SHARE)
        val downloaded = when (val result = fullRun) {
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
            val output = File(File(dir, "corte").apply { mkdirs() }, outputName(task, input.nameWithoutExtension.take(120) + section, ext))
            if (!cutLocally(task, input, output, Clip.Range(trim.startSec, trim.lengthSec), DOWNLOAD_SHARE)) {
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
        save(task, cuts, (fullRun as? RunResult.Done)?.music.orEmpty())
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
        return RunResult.Done(produced, run.music.toMap())
    }

    private suspend fun save(task: DownloadTask, files: List<File>, music: Map<String, String> = emptyMap()) {
        val id = task.id
        val produced = if (task.mode == DownloadMode.AUDIO) tagMusic(task, files, music) else files
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

    /**
     * MP3: nome e título só com o nome da música, artista, álbum, capa e letra (veja MusicTagger).
     * Falhas aqui não derrubam o download; o arquivo sai como veio.
     */
    private suspend fun tagMusic(task: DownloadTask, files: List<File>, music: Map<String, String>): List<File> {
        val id = task.id
        mutate(id) {
            it.copy(status = TaskStatus.PROCESSING, stage = "Buscando capa e letra", progress = null,
                speedBps = null, etaSeconds = null)
        }
        return files.map { file ->
            if (!file.extension.equals("mp3", ignoreCase = true)) return@map file
            val json = music.entries.firstOrNull { (videoId, _) -> file.name.contains("[$videoId]") }?.value
                ?: music.values.singleOrNull()
            try {
                val result = MusicTagger.tag(file, json, task.title, task.trim)
                if (files.size == 1) {
                    mutate(id) {
                        it.copy(title = result.title, uploader = result.artist ?: it.uploader,
                            thumbnail = result.coverUrl ?: it.thumbnail)
                    }
                }
                result.file
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Etiquetas da música falharam para ${file.name}", e)
                file
            }
        }
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
            is OutputParser.Event.Music -> run.music[event.id] = event.json
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
                    addOption("--print", OutputParser.musicTemplate)
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
    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv", "webm")

    /**
     * Estado de um processo em andamento; tocado pela thread que lê a saída do yt-dlp (e só
     * lido pelo vigia de travamento). [trimLengthSec] existe quando o yt-dlp baixa só o trecho;
     * [share] é a fração da barra que este processo ocupa.
     */
    private class RunState(val mode: DownloadMode, val trimLengthSec: Long?, val share: Float) {
        val files = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val music = mutableMapOf<String, String>()
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
