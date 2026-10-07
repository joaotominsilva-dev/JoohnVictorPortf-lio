package com.joohn.baixavideos.engine

import android.content.Context
import android.util.Log
import com.joohn.baixavideos.data.Prefs
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

class EngineUnavailableException(message: String) : Exception(message)

/**
 * O "motor": Python + yt-dlp + FFmpeg + QuickJS do youtubedl-android. Cuida da instalação na
 * primeira execução, da versão em uso e das atualizações do yt-dlp (essenciais: YouTube,
 * Instagram e TikTok mudam com frequência e versões antigas param de funcionar).
 */
object Engine {
    /** Versão do yt-dlp que vem dentro do youtubedl-android 0.18.1. */
    const val BUNDLED_VERSION = "2025.11.12"
    private const val TAG = "Engine"
    private const val AUTO_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val WAIT_FOR_UPDATE_MS = 60_000L
    private const val WAIT_FOR_IDLE_MS = 15 * 60 * 1000L

    sealed interface State {
        data object Starting : State
        data class Ready(val version: String) : State
        data class Failed(val message: String) : State
    }

    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data class Downloading(val version: String) : UpdateState
        data class WaitingIdle(val version: String) : UpdateState
        data class UpToDate(val version: String) : UpdateState
        data class Updated(val version: String) : UpdateState
        data class Failed(val message: String) : UpdateState

        val inFlight: Boolean get() = this is Checking || this is Downloading || this is WaitingIdle
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Starting)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    // Downloads em execução usam o binário do yt-dlp; a troca só acontece com o contador em zero.
    private val swapLock = Mutex()
    private val running = AtomicInteger(0)
    private var initJob: Job? = null
    private var updateJob: Job? = null
    private lateinit var app: Context

    fun start(context: Context) {
        synchronized(this) {
            app = context.applicationContext
            if (initJob?.isActive == true || _state.value is State.Ready) return
            _state.value = State.Starting
            initJob = scope.launch {
                try {
                    YoutubeDL.init(app)
                    FFmpeg.init(app)
                    val version = readVersion()
                    Log.i(TAG, "yt-dlp $version pronto")
                    _state.value = State.Ready(version)
                    maybeAutoUpdate(version)
                } catch (e: Exception) {
                    Log.e(TAG, "Falha ao iniciar o motor", e)
                    _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /** Espera o motor ficar pronto e, se houver atualização em curso, dá a ela até 1 minuto. */
    suspend fun awaitReady() {
        val ready = state.first { it !is State.Starting }
        if (ready is State.Failed) throw EngineUnavailableException(ready.message)
        withTimeoutOrNull(WAIT_FOR_UPDATE_MS) { update.first { !it.inFlight } }
    }

    /** Executa um processo do yt-dlp registrado como "em uso", bloqueando a troca de versão. */
    suspend fun <T> withProcess(block: () -> T): T {
        swapLock.withLock { running.incrementAndGet() }
        try {
            return block()
        } finally {
            running.decrementAndGet()
        }
    }

    fun requestUpdate() {
        synchronized(this) {
            if (updateJob?.isActive == true || _state.value !is State.Ready) return
            updateJob = scope.launch { runUpdate() }
        }
    }

    private fun maybeAutoUpdate(current: String) {
        if (!Prefs.settings.value.autoUpdate) return
        val stale = System.currentTimeMillis() - Prefs.lastUpdateCheck > AUTO_CHECK_INTERVAL_MS
        if (stale || current <= BUNDLED_VERSION) requestUpdate()
    }

    private suspend fun runUpdate() {
        _update.value = UpdateState.Checking
        try {
            val nightly = Prefs.settings.value.nightly
            val latest = YtDlpUpdater.latestTag(nightly)
            Prefs.lastUpdateCheck = System.currentTimeMillis()
            val current = (state.value as? State.Ready)?.version
            if (current == latest) {
                _update.value = UpdateState.UpToDate(latest)
                return
            }
            _update.value = UpdateState.Downloading(latest)
            val binary = YtDlpUpdater.download(app, latest, nightly)
            try {
                val installed = installWhenIdle(binary, latest)
                Log.i(TAG, "yt-dlp atualizado para $installed")
                _state.value = State.Ready(installed)
                _update.value = UpdateState.Updated(installed)
            } finally {
                binary.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Atualização do yt-dlp falhou", e)
            _update.value = UpdateState.Failed(
                when (e) {
                    is UnknownHostException -> "Sem conexão com o GitHub."
                    is SocketTimeoutException -> "O GitHub demorou para responder."
                    else -> e.message ?: "Erro desconhecido."
                },
            )
        }
    }

    private suspend fun installWhenIdle(binary: File, version: String): String {
        val deadline = System.currentTimeMillis() + WAIT_FOR_IDLE_MS
        while (true) {
            swapLock.withLock {
                if (running.get() == 0) return YtDlpUpdater.install(app, binary) { readVersion() }
            }
            if (System.currentTimeMillis() > deadline) {
                throw IOException("Há downloads em andamento; a atualização fica para a próxima abertura.")
            }
            _update.value = UpdateState.WaitingIdle(version)
            delay(3_000)
        }
    }

    private fun readVersion(): String {
        val request = YoutubeDLRequest(emptyList<String>()).addOption("--version")
        val response = YoutubeDL.execute(request, null, false, null)
        return response.out.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            ?: throw IOException("O yt-dlp não respondeu")
    }
}
