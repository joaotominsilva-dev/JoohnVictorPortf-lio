package com.joohn.baixavideos.ui.trim

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import android.net.Uri
import com.joohn.baixavideos.engine.PreviewFile
import com.joohn.baixavideos.engine.PreviewStream
import com.joohn.baixavideos.engine.VideoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Player da prévia do corte: toca direto do site (sem baixar) o endereço que o yt-dlp
 * encontrou, com os mesmos cabeçalhos que ele usaria. Se o site recusar (o TikTok responde 403
 * fora do app dele), baixa uma cópia leve do vídeo, se ele for curto, e toca a cópia.
 */
@Stable
@OptIn(UnstableApi::class)
class PreviewController(context: Context, private val info: VideoInfo, private val onDuration: (Double) -> Unit) :
    Player.Listener {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val canUseCopy = (info.durationSec ?: 0.0) <= COPY_MAX_SEC
    private var triedCopy = false
    private var openedAt = System.currentTimeMillis()

    val player: ExoPlayer?

    /** Baixando a cópia leve para a prévia. */
    var loadingCopy by mutableStateOf(false)
        private set

    var videoAspect by mutableFloatStateOf(0f)
        private set
    var buffering by mutableStateOf(true)
        private set
    var playing by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var hasFrame by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set

    private var stopAtMs = Long.MAX_VALUE
    private var ready = false

    init {
        val source = buildSource(info.preview)
        player = if (source == null && !canUseCopy) {
            failed = true
            null
        } else {
            ExoPlayer.Builder(appContext).build().apply {
                addListener(this@PreviewController)
                playWhenReady = false
                if (source != null) {
                    setMediaSource(source)
                    prepare()
                }
            }
        }
        if (player != null && source == null) useCopy()
    }

    /** Troca o endereço do site por uma cópia leve baixada pelo yt-dlp. */
    private fun useCopy() {
        val p = player ?: return
        triedCopy = true
        loadingCopy = true
        buffering = true
        scope.launch {
            val file = withTimeoutOrNull(COPY_TIMEOUT_MS) {
                runCatching { PreviewFile.fetch(appContext, info.url) }
                    .onFailure { Log.w(TAG, "Cópia da prévia falhou", it) }
                    .getOrNull()
            }
            loadingCopy = false
            if (file == null) {
                failed = true
                return@launch
            }
            openedAt = System.currentTimeMillis()
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            p.prepare()
            p.seekTo(positionMs)
        }
    }

    /** Mostra o quadro de um instante (alças do corte). Rápido durante o arraste, exato ao soltar. */
    fun showFrame(seconds: Long, dragging: Boolean) {
        positionMs = seconds * 1000
        val p = player ?: return
        p.pause()
        p.setSeekParameters(if (dragging) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
        p.seekTo(positionMs)
    }

    /** Toca o trecho do início ao fim e para no fim. */
    fun playRange(startSec: Long, endSec: Long) {
        val p = player ?: return
        val start = startSec * 1000
        stopAtMs = endSec * 1000
        if (p.currentPosition < start || p.currentPosition >= stopAtMs - 250) {
            p.setSeekParameters(SeekParameters.EXACT)
            p.seekTo(start)
        }
        p.play()
    }

    fun pause() {
        player?.pause()
    }

    fun release() {
        scope.cancel()
        player?.removeListener(this)
        player?.release()
    }

    /**
     * Acompanha a posição para a interface e interrompe a reprodução no fim do trecho. Se a
     * prévia não abrir em [OPEN_TIMEOUT_MS], desiste (o corte funciona sem ela).
     */
    suspend fun track() {
        while (true) {
            val p = player ?: return
            if (!ready && !failed && !loadingCopy && System.currentTimeMillis() - openedAt > OPEN_TIMEOUT_MS) {
                Log.w(TAG, "Prévia não abriu em ${OPEN_TIMEOUT_MS / 1000}s")
                if (canUseCopy && !triedCopy) {
                    p.stop()
                    useCopy()
                } else {
                    failed = true
                    p.stop()
                }
            }
            if (p.isPlaying) {
                positionMs = p.currentPosition
                if (positionMs >= stopAtMs) {
                    p.pause()
                    positionMs = stopAtMs
                }
            }
            delay(if (p.isPlaying) 40L else 200L)
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        playing = isPlaying
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        buffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
        if (playbackState == Player.STATE_READY) {
            ready = true
            val duration = player?.duration ?: C.TIME_UNSET
            if (duration != C.TIME_UNSET && duration > 0) onDuration(duration / 1000.0)
        }
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        if (videoSize.width > 0 && videoSize.height > 0) {
            videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        }
    }

    override fun onRenderedFirstFrame() {
        hasFrame = true
    }

    override fun onPlayerError(error: PlaybackException) {
        Log.w(TAG, "Prévia indisponível: ${error.errorCodeName}", error)
        if (canUseCopy && !triedCopy) useCopy() else failed = true
    }

    private companion object {
        const val TAG = "PreviewController"
        const val OPEN_TIMEOUT_MS = 25_000L
        const val COPY_TIMEOUT_MS = 120_000L

        /** Só vale baixar uma cópia para a prévia em vídeos de até 10 minutos (ou sem duração). */
        const val COPY_MAX_SEC = 600.0

        fun buildSource(streams: List<PreviewStream>): MediaSource? {
            // Vídeo e áudio de um mesmo manifesto DASH viram uma fonte só.
            val unique = streams.distinctBy { if (it.isDash) it.manifestUrl else it.url }
            val sources = unique.map { sourceFor(it) ?: return null }
            return when (sources.size) {
                0 -> null
                1 -> sources[0]
                else -> MergingMediaSource(*sources.toTypedArray())
            }
        }

        val PreviewStream.isDash: Boolean get() = protocol.startsWith("http_dash_segments")

        fun sourceFor(stream: PreviewStream): MediaSource? {
            val data = dataSource(stream)
            return when {
                stream.protocol.startsWith("m3u8") ->
                    HlsMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(stream.url))
                stream.isDash -> stream.manifestUrl?.let {
                    DashMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(it))
                }
                stream.protocol == "https" || stream.protocol == "http" ->
                    ProgressiveMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(stream.url))
                else -> null
            }
        }

        fun dataSource(stream: PreviewStream): DataSource.Factory {
            val userAgent = stream.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            val headers = stream.headers.filterKeys { !it.equals("User-Agent", true) }.toMutableMap()
            stream.cookieHeader?.let { headers["Cookie"] = it }
            return DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
        }
    }
}
