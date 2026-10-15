package com.joohn.baixavideos.ui

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.joohn.baixavideos.data.Prefs
import com.joohn.baixavideos.engine.DownloadMode
import com.joohn.baixavideos.engine.EngineUnavailableException
import com.joohn.baixavideos.engine.Trim
import com.joohn.baixavideos.engine.VideoInfo
import com.joohn.baixavideos.engine.VideoInfoException
import com.joohn.baixavideos.engine.VideoInfoFetcher
import com.joohn.baixavideos.engine.VideoQuality
import com.joohn.baixavideos.util.Links
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

sealed interface InfoState {
    data object Empty : InfoState
    data object Loading : InfoState
    data class Loaded(val info: VideoInfo) : InfoState
    data class Failed(val message: String) : InfoState
}

/**
 * Rascunho de um download: link, formato, qualidade e corte. O Início e a janela de
 * compartilhar usam o mesmo rascunho, então as opções se comportam igual nos dois lugares.
 */
@Stable
class DownloadDraft(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private var fetchJob: Job? = null

    var text by mutableStateOf("")
        private set
    var link by mutableStateOf<String?>(null)
        private set
    var info by mutableStateOf<InfoState>(InfoState.Empty)
        private set
    var mode by mutableStateOf(Prefs.settings.value.mode)
        private set
    var quality by mutableStateOf(Prefs.settings.value.quality)
        private set
    var trimOn by mutableStateOf(false)
        private set
    var trimStart by mutableLongStateOf(0L)
        private set
    var trimEnd by mutableLongStateOf(0L)
        private set

    /** Duração descoberta pelo player da prévia, para sites que não informam a duração. */
    private var playerDurationSec by mutableStateOf<Double?>(null)

    val loadedInfo: VideoInfo? get() = (info as? InfoState.Loaded)?.info

    /** Duração em segundos inteiros, quando conhecida. */
    val durationSec: Long?
        get() = (loadedInfo?.durationSec ?: playerDurationSec)?.let { ceil(it).toLong() }?.takeIf { it > 0 }

    fun updateText(value: String) {
        text = value
        val next = Links.extractUrl(value)
        if (next == link) return
        link = next
        load(next)
    }

    fun retryInfo() = load(link)

    private fun load(next: String?) {
        fetchJob?.cancel()
        trimOn = false
        playerDurationSec = null
        if (next == null) {
            info = InfoState.Empty
            return
        }
        VideoInfoFetcher.cached(next)?.let {
            info = InfoState.Loaded(it)
            return
        }
        info = InfoState.Loading
        fetchJob = scope.launch {
            delay(350) // deixa a colagem ou a digitação terminar antes de abrir o motor
            info = try {
                InfoState.Loaded(VideoInfoFetcher.fetch(app, next))
            } catch (e: CancellationException) {
                throw e
            } catch (e: VideoInfoException) {
                InfoState.Failed(e.message ?: "Não consegui ler esse link.")
            } catch (_: EngineUnavailableException) {
                InfoState.Failed("O motor de download não iniciou. Abra os Ajustes para tentar de novo.")
            } catch (_: Exception) {
                InfoState.Failed("Não consegui ler esse link agora.")
            }
        }
    }

    fun selectMode(value: DownloadMode) {
        mode = value
        Prefs.update { it.copy(mode = value) }
    }

    fun selectQuality(value: VideoQuality) {
        quality = value
        Prefs.update { it.copy(quality = value) }
    }

    fun setTrimEnabled(enabled: Boolean) {
        trimOn = enabled
        if (enabled) {
            trimStart = 0L
            trimEnd = durationSec ?: 0L
        }
    }

    fun setTrimRange(start: Long, end: Long) {
        val max = durationSec
        trimStart = start.coerceAtLeast(0L).let { if (max != null) it.coerceAtMost(max) else it }
        trimEnd = end.coerceAtLeast(0L).let { if (max != null) it.coerceAtMost(max) else it }
    }

    fun onPlayerDuration(seconds: Double) {
        if (seconds <= 0 || loadedInfo?.durationSec != null || playerDurationSec != null) return
        playerDurationSec = seconds
        if (trimOn && trimEnd == 0L) trimEnd = durationSec ?: 0L
    }

    /** Trecho para baixar; null com o corte desligado ou quando ele cobre o vídeo inteiro. */
    val trim: Trim?
        get() {
            if (!trimOn || trimEnd <= trimStart) return null
            val max = durationSec
            if (max != null && trimStart == 0L && trimEnd >= max) return null
            return Trim(trimStart, trimEnd)
        }

    val trimProblem: String?
        get() = when {
            !trimOn -> null
            durationSec == null && trimEnd == 0L -> "Toque em Fim e digite onde o trecho termina."
            trimEnd <= trimStart -> "O fim do trecho precisa vir depois do início."
            else -> null
        }

    val canDownload: Boolean get() = link != null && trimProblem == null

    fun toStart(): PendingStart? = link?.let { PendingStart(it, mode, quality, trim, loadedInfo) }

    fun reset() = updateText("")
}
