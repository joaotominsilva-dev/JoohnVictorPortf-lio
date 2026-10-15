package com.joohn.baixavideos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joohn.baixavideos.engine.DownloadMode
import com.joohn.baixavideos.engine.Downloads
import com.joohn.baixavideos.engine.YouTubeSearch
import com.joohn.baixavideos.engine.SavedFile
import com.joohn.baixavideos.engine.TaskStatus
import com.joohn.baixavideos.engine.Trim
import com.joohn.baixavideos.engine.VideoInfoFetcher
import com.joohn.baixavideos.engine.VideoQuality
import com.joohn.baixavideos.ui.trim.PreviewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Roda o motor de verdade no emulador: Python, yt-dlp, FFmpeg e o salvamento em Download. */
@RunWith(AndroidJUnit4::class)
class EngineSmokeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun engineStartsAndReportsVersion(): Unit = runBlocking {
        val version = TestSupport.awaitEngine(context)
        assertTrue("versão vazia", version.isNotBlank())
    }

    @Test
    fun downloadsLocalVideoAndConvertsAudio(): Unit = runBlocking {
        TestSupport.awaitEngine(context)
        TestHttpServer(TestSupport.testAssets, "dash").use { server ->
            val video = TestSupport.download(context, server.url("manifest.mpd"), DownloadMode.VIDEO)
            assertEquals(video.errorDetails, TaskStatus.DONE, video.status)
            val mp4 = video.files.single()
            assertEquals("video/mp4", mp4.mime)
            assertTrue("vídeo pequeno demais: ${mp4.size}", mp4.size > 20_000)
            assertReadable(mp4)

            val audio = TestSupport.download(context, server.url("manifest.mpd"), DownloadMode.AUDIO)
            assertEquals(audio.errorDetails, TaskStatus.DONE, audio.status)
            val mp3 = audio.files.single()
            assertEquals("audio/mpeg", mp3.mime)
            assertReadable(mp3)
            Log.i(TAG, "OK local: ${mp4.name} (${mp4.size} B), ${mp3.name} (${mp3.size} B)")
        }
    }

    /** O corte baixa só o trecho pedido, com o corte no segundo exato (recodificado em H.264). */
    @Test
    fun trimsVideoAndAudioToTheChosenSection(): Unit = runBlocking {
        TestSupport.awaitEngine(context)
        TestHttpServer(TestSupport.testAssets, "dash").use { server ->
            val video = TestSupport.download(context, server.url("Trecho.mpd"), DownloadMode.VIDEO, trim = Trim(1, 2))
            assertEquals(video.errorDetails, TaskStatus.DONE, video.status)
            val mp4 = video.files.single()
            val videoMs = durationMs(mp4)
            val codec = videoMime(mp4)
            Log.i(TAG, "Trecho de vídeo: ${mp4.name} | $videoMs ms | $codec")
            assertTrue("duração do trecho: $videoMs ms", videoMs in 700L..1400L)
            assertEquals("video/avc", codec)
            assertTrue("nome sem o trecho: ${mp4.name}", "trecho 00m01s-00m02s" in mp4.name)

            val audio = TestSupport.download(context, server.url("Trecho.mpd"), DownloadMode.AUDIO, trim = Trim(1, 2))
            assertEquals(audio.errorDetails, TaskStatus.DONE, audio.status)
            val mp3 = audio.files.single()
            val audioMs = durationMs(mp3)
            Log.i(TAG, "Trecho de áudio: ${mp3.name} | $audioMs ms")
            assertTrue("duração do trecho de áudio: $audioMs ms", audioMs in 700L..1500L)
        }
    }

    /**
     * Corte que começa no meio de um grupo de quadros (quadro-chave em 0 s e 3 s, início em 1 s):
     * o Transformer (hardware) e o FFmpeg (reserva) têm que entregar o mesmo primeiro quadro,
     * e não o quadro-chave anterior.
     */
    @Test
    fun cutStartsOnTheExactFrameWithBothEngines(): Unit = runBlocking {
        TestSupport.awaitEngine(context)
        try {
            TestHttpServer(TestSupport.testAssets, "dashgop").use { server ->
                val frames = mutableMapOf<Downloads.CutEngine, Bitmap>()
                for (engine in Downloads.CutEngine.entries) {
                    Downloads.forcedCutEngine = engine
                    val started = System.currentTimeMillis()
                    val task = TestSupport.download(context, server.url("Gop.mpd"), DownloadMode.VIDEO, trim = Trim(1, 3))
                    val seconds = (System.currentTimeMillis() - started) / 1000.0
                    assertEquals("$engine: ${task.errorDetails}", TaskStatus.DONE, task.status)
                    val file = task.files.single()
                    val ms = durationMs(file)
                    Log.i(TAG, "Corte exato $engine: ${file.name} | $ms ms | ${videoMime(file)} | ${seconds}s")
                    assertTrue("$engine duração: $ms ms", ms in 1700L..2300L)
                    frames[engine] = firstFrame(file)
                }
                val diff = meanDifference(frames.getValue(Downloads.CutEngine.TRANSFORMER), frames.getValue(Downloads.CutEngine.FFMPEG))
                Log.i(TAG, "Diferença do primeiro quadro (Transformer x FFmpeg): $diff")
                assertTrue("o Transformer começou em outro quadro (diferença $diff)", diff < 3.5)
            }
        } finally {
            Downloads.forcedCutEngine = null
        }
    }

    private fun firstFrame(file: SavedFile): Bitmap {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(file.uri))
            return requireNotNull(retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)) { "sem quadro em ${file.name}" }
        } finally {
            retriever.release()
        }
    }

    /** Diferença média de brilho (0 a 255) entre duas imagens reduzidas a 64x36. */
    private fun meanDifference(a: Bitmap, b: Bitmap): Double {
        val sa = Bitmap.createScaledBitmap(a, 64, 36, true)
        val sb = Bitmap.createScaledBitmap(b, 64, 36, true)
        var total = 0.0
        for (y in 0 until 36) for (x in 0 until 64) {
            fun luma(c: Int) = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
            total += kotlin.math.abs(luma(sa.getPixel(x, y)) - luma(sb.getPixel(x, y)))
        }
        return total / (64 * 36)
    }

    /** Reserva do corte: baixa o arquivo inteiro e corta no aparelho. */
    @Test
    fun trimsByDownloadingThenCuttingOnDevice(): Unit = runBlocking {
        TestSupport.awaitEngine(context)
        Downloads.forcedTrimPlan = Downloads.TrimPlan.FULL_THEN_CUT
        try {
            TestHttpServer(TestSupport.testAssets, "dash").use { server ->
                val video = TestSupport.download(context, server.url("Corte.mpd"), DownloadMode.VIDEO, trim = Trim(1, 2))
                assertEquals(video.errorDetails, TaskStatus.DONE, video.status)
                val mp4 = video.files.single()
                val videoMs = durationMs(mp4)
                Log.i(TAG, "Corte local (vídeo): ${mp4.name} | $videoMs ms | ${videoMime(mp4)}")
                assertTrue("duração do corte local: $videoMs ms", videoMs in 700L..1400L)
                assertEquals("video/avc", videoMime(mp4))
                assertTrue("nome sem o trecho: ${mp4.name}", "trecho 00m01s-00m02s" in mp4.name)

                val audio = TestSupport.download(context, server.url("Corte.mpd"), DownloadMode.AUDIO, trim = Trim(1, 2))
                assertEquals(audio.errorDetails, TaskStatus.DONE, audio.status)
                val mp3 = audio.files.single()
                val audioMs = durationMs(mp3)
                Log.i(TAG, "Corte local (áudio): ${mp3.name} | $audioMs ms")
                assertTrue("duração do corte local de áudio: $audioMs ms", audioMs in 700L..1500L)
                assertEquals("audio/mpeg", mp3.mime)
            }
        } finally {
            Downloads.forcedTrimPlan = null
        }
    }

    /** Corte em sites reais pelos dois caminhos; só registra no log (o CI pode ser bloqueado). */
    @Test
    fun realTrimsBestEffort(): Unit = runBlocking {
        TestSupport.awaitEngine(context)
        val sites = listOf(
            "TikTok" to "https://www.tiktok.com/@scout2015/video/6718335390845095173",
            "Instagram" to "https://www.instagram.com/p/aye83DjauH/",
        )
        try {
            for ((name, url) in sites) {
                val info = runCatching { VideoInfoFetcher.fetch(context, url) }
                Log.i(TAG, "REAL INFO $name: ${info.getOrNull()?.let { "${it.title} | ${it.durationSec}s | ${it.preview.map { p -> p.protocol }}" } ?: info.exceptionOrNull()?.message}")
                info.getOrNull()?.let { realPreview(name, it) }
                for (plan in Downloads.TrimPlan.entries) {
                    Downloads.forcedTrimPlan = plan
                    val started = System.currentTimeMillis()
                    val outcome = runCatching {
                        TestSupport.download(context, url, DownloadMode.VIDEO, VideoQuality.P480, timeout = 4.minutes, trim = Trim(2, 6))
                    }
                    val seconds = (System.currentTimeMillis() - started) / 1000
                    outcome.onSuccess { task ->
                        val file = task.files.singleOrNull()
                        Log.i(
                            TAG,
                            "REAL TRIM $name $plan: ${task.status} em ${seconds}s | " +
                                "${file?.let { "${it.name} ${it.size} B ${durationMs(it)} ms ${videoMime(it)}" }} | " +
                                "${task.error} | ${task.errorDetails?.take(600)}",
                        )
                    }.onFailure { Log.w(TAG, "REAL TRIM $name $plan: ${it.message} (${seconds}s)") }
                }
            }
        } finally {
            Downloads.forcedTrimPlan = null
        }
        val search = runCatching { YouTubeSearch.search(context, "receita de bolo de cenoura", 1, 5) }
        Log.i(TAG, "REAL SEARCH: ${search.getOrNull()?.map { "${it.title} (${it.durationSec}s, ${it.channel})" } ?: search.exceptionOrNull()?.message}")
    }

    private suspend fun realPreview(name: String, info: com.joohn.baixavideos.engine.VideoInfo) {
        var reported: Double? = null
        val controller = withContext(Dispatchers.Main) { PreviewController(context, info) { reported = it } }
        try {
            val started = System.currentTimeMillis()
            var usedCopy = false
            withTimeoutOrNull(120.seconds) {
                while (withContext(Dispatchers.Main) { !controller.failed && reported == null }) {
                    if (withContext(Dispatchers.Main) { controller.loadingCopy }) usedCopy = true
                    delay(250)
                }
            }
            val failed = withContext(Dispatchers.Main) { controller.failed }
            val seconds = (System.currentTimeMillis() - started) / 1000
            Log.i(TAG, "REAL PREVIEW $name: falhou=$failed duração=$reported cópia=$usedCopy em ${seconds}s")
        } finally {
            withContext(Dispatchers.Main) { controller.release() }
        }
    }

    /** A prévia lê título e um endereço tocável, e o player da prévia abre esse endereço. */
    @Test
    fun readsInfoAndPlaysPreview(): Unit = runBlocking {
        TestSupport.awaitEngine(context)
        TestHttpServer(TestSupport.testAssets, "dash").use { server ->
            val info = VideoInfoFetcher.fetch(context, server.url("Clipe%20de%20teste.mpd"))
            Log.i(TAG, "Info: ${info.title} | ${info.durationSec} | ${info.preview.map { it.protocol }}")
            assertEquals("Clipe de teste", info.title)
            assertTrue("sem endereço de prévia", info.preview.isNotEmpty())

            var reported: Double? = null
            val controller = withContext(Dispatchers.Main) { PreviewController(context, info) { reported = it } }
            try {
                val ready = withTimeoutOrNull(30.seconds) {
                    while (true) {
                        val done = withContext(Dispatchers.Main) { controller.failed || (!controller.buffering && reported != null) }
                        if (done) break
                        delay(200)
                    }
                    true
                }
                val failed = withContext(Dispatchers.Main) { controller.failed }
                Log.i(TAG, "Prévia: pronta=$ready falhou=$failed duração=$reported")
                assertFalse("o player da prévia falhou", failed)
                assertTrue("a prévia não carregou", ready == true)
                assertTrue("duração da prévia: $reported", (reported ?: 0.0) in 2.5..3.5)
            } finally {
                withContext(Dispatchers.Main) { controller.release() }
            }
        }
    }

    @Test
    fun brokenLinkFailsWithFriendlyMessage(): Unit = runBlocking {
        TestSupport.awaitEngine(context)
        TestHttpServer(TestSupport.testAssets, "dash").use { server ->
            val task = TestSupport.download(context, server.url("nao-existe.mp4"), DownloadMode.VIDEO)
            assertEquals(TaskStatus.FAILED, task.status)
            assertFalse(task.error.isNullOrBlank())
            Log.i(TAG, "Erro amigável: ${task.error} | ${task.errorDetails}")
        }
    }

    /** Sites reais: só registra o resultado no log (IPs de CI costumam ser bloqueados). */
    @Test
    fun realSitesBestEffort(): Unit = runBlocking {
        TestSupport.awaitEngine(context)
        val sites = listOf(
            "YouTube" to "https://www.youtube.com/watch?v=jNQXAC9IVRw",
            "TikTok" to "https://www.tiktok.com/@scout2015/video/6718335390845095173",
            "Instagram" to "https://www.instagram.com/p/aye83DjauH/",
        )
        for ((name, url) in sites) {
            val outcome = runCatching {
                TestSupport.download(context, url, DownloadMode.VIDEO, VideoQuality.P480, timeout = 4.minutes)
            }
            outcome.onSuccess { task ->
                Log.i(
                    TAG,
                    "REAL $name: ${task.status} | ${task.title} | ${task.files.map { "${it.name} ${it.size} B" }} | " +
                        "${task.error} | ${task.errorDetails?.take(800)}",
                )
            }.onFailure { Log.w(TAG, "REAL $name: ${it.message}") }
        }
    }

    private fun durationMs(file: SavedFile): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(file.uri))
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
        } finally {
            retriever.release()
        }
    }

    private fun videoMime(file: SavedFile): String? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(file.uri), null)
            return (0 until extractor.trackCount)
                .mapNotNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                .firstOrNull { it.startsWith("video/") }
        } finally {
            extractor.release()
        }
    }

    private fun assertReadable(file: SavedFile) {
        val stream = context.contentResolver.openInputStream(Uri.parse(file.uri))
        stream.use { assertTrue("arquivo ilegível: ${file.uri}", it != null && it.read() >= 0) }
    }
}
