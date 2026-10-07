package com.joohn.baixavideos

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joohn.baixavideos.engine.DownloadMode
import com.joohn.baixavideos.engine.SavedFile
import com.joohn.baixavideos.engine.TaskStatus
import com.joohn.baixavideos.engine.VideoQuality
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes

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

    private fun assertReadable(file: SavedFile) {
        val stream = context.contentResolver.openInputStream(Uri.parse(file.uri))
        stream.use { assertTrue("arquivo ilegível: ${file.uri}", it != null && it.read() >= 0) }
    }
}
