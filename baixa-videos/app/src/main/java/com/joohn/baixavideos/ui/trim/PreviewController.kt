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
import com.joohn.baixavideos.engine.PreviewStream
import com.joohn.baixavideos.engine.VideoInfo
import kotlinx.coroutines.delay

/**
 * Player da prévia do corte: toca direto do site (sem baixar) o endereço que o yt-dlp
 * encontrou, com os mesmos cabeçalhos que ele usaria.
 */
@Stable
@OptIn(UnstableApi::class)
class PreviewController(context: Context, info: VideoInfo, private val onDuration: (Double) -> Unit) : Player.Listener {
    val player: ExoPlayer?

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
        player = if (source == null) {
            failed = true
            null
        } else {
            ExoPlayer.Builder(context.applicationContext).build().apply {
                addListener(this@PreviewController)
                setMediaSource(source)
                playWhenReady = false
                prepare()
            }
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
        player?.removeListener(this)
        player?.release()
    }

    /**
     * Acompanha a posição para a interface e interrompe a reprodução no fim do trecho. Se a
     * prévia não abrir em [OPEN_TIMEOUT_MS], desiste (o corte funciona sem ela).
     */
    suspend fun track() {
        val openedAt = System.currentTimeMillis()
        while (true) {
            val p = player ?: return
            if (!ready && !failed && System.currentTimeMillis() - openedAt > OPEN_TIMEOUT_MS) {
                Log.w(TAG, "Prévia não abriu em ${OPEN_TIMEOUT_MS / 1000}s")
                failed = true
                p.stop()
                return
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
        failed = true
    }

    private companion object {
        const val TAG = "PreviewController"
        const val OPEN_TIMEOUT_MS = 25_000L

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
