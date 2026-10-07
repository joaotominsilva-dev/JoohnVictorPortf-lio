package com.joohn.baixavideos

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.joohn.baixavideos.engine.DownloadMode
import com.joohn.baixavideos.engine.DownloadTask
import com.joohn.baixavideos.engine.Downloads
import com.joohn.baixavideos.engine.Engine
import com.joohn.baixavideos.engine.VideoQuality
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

const val TAG = "BaixaVideosTest"

object TestSupport {
    val testAssets get() = InstrumentationRegistry.getInstrumentation().context.assets

    /** Espera o motor iniciar e a atualização automática do yt-dlp (se houver) terminar. */
    suspend fun awaitEngine(context: Context): String {
        Engine.start(context)
        withTimeout(5.minutes) { Engine.state.first { it !is Engine.State.Starting } }
        val state = Engine.state.value
        check(state is Engine.State.Ready) { "O motor não iniciou: $state" }
        withTimeoutOrNull(20.seconds) { Engine.update.first { it !is Engine.UpdateState.Idle } }
        withTimeoutOrNull(5.minutes) { Engine.update.first { !it.inFlight } }
        val ready = Engine.state.value as Engine.State.Ready
        Log.i(TAG, "Motor: yt-dlp ${ready.version} | atualização: ${Engine.update.value}")
        return ready.version
    }

    suspend fun download(
        context: Context,
        url: String,
        mode: DownloadMode,
        quality: VideoQuality = VideoQuality.P720,
        timeout: Duration = 5.minutes,
    ): DownloadTask {
        val task = Downloads.enqueue(context, url, mode, quality)
        val stages = mutableListOf<String>()
        val result = withTimeoutOrNull(timeout) {
            Downloads.tasks
                .map { list -> list.firstOrNull { it.id == task.id } }
                .filterNotNull()
                .onEach { t -> t.stage?.let { if (stages.lastOrNull() != it) stages += it } }
                .first { !it.status.isActive }
        }
        if (result == null) {
            Downloads.cancel(task.id)
            error("Tempo esgotado baixando $url (etapas: $stages)")
        }
        Log.i(TAG, "Etapas ($mode $url): $stages")
        return result
    }

    /** Captura a tela e guarda em Download/BaixaVideosCI (sobrevive à desinstalação no fim do teste). */
    fun screenshot(device: UiDevice, context: Context, name: String) {
        val tmp = File(context.cacheDir, "$name.png")
        device.takeScreenshot(tmp)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/BaixaVideosCI")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Log.w(TAG, "Não consegui salvar o screenshot $name")
            return
        }
        context.contentResolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
        tmp.delete()
        Log.i(TAG, "Screenshot: $name")
    }
}
