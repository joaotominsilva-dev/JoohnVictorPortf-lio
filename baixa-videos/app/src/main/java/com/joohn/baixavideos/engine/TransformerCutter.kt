package com.joohn.baixavideos.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Corta vídeo com o Media3 Transformer, dentro do app: usa o codificador de hardware do celular
 * e, com a otimização de corte, recodifica só o começo do trecho (até o próximo quadro-chave) e
 * copia o resto sem recodificar. Também recorta o quadro (vertical ou horizontal), e aí recodifica
 * tudo. É o caminho rápido; o FFmpeg fica de reserva.
 */
@OptIn(UnstableApi::class)
internal object TransformerCutter {
    private const val TAG = "TransformerCutter"
    private const val PROGRESS_INTERVAL_MS = 300L

    /** Recorte do quadro: a janela e o tamanho final em pixels (pares, como o H.264 pede). */
    class FrameCrop(val window: CropWindow, val width: Int, val height: Int)

    /**
     * Grava em [output] (MP4 com H.264 + AAC) o trecho [startMs]..[endMs] de [input] (o vídeo
     * inteiro quando os dois são null), recortado por [crop] quando ele vem.
     */
    suspend fun cut(
        context: Context,
        input: File,
        output: File,
        startMs: Long?,
        endMs: Long?,
        crop: FrameCrop? = null,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        val started = System.currentTimeMillis()
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val holder = ProgressHolder()
            lateinit var transformer: Transformer
            val poll = object : Runnable {
                override fun run() {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress / 100f)
                    }
                    handler.postDelayed(this, PROGRESS_INTERVAL_MS)
                }
            }
            transformer = Transformer.Builder(context.applicationContext)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                // A otimização só copia o meio do trecho; com recorte, todo quadro muda.
                .experimentalSetTrimOptimizationEnabled(crop == null && startMs != null)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        val seconds = (System.currentTimeMillis() - started) / 1000.0
                        Log.i(
                            TAG,
                            "Corte pronto em ${seconds}s (codificador ${exportResult.videoEncoderName}, " +
                                "otimização ${exportResult.optimizationResult}): ${output.name}",
                        )
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        handler.removeCallbacks(poll)
                        Log.w(TAG, "Corte falhou: ${exportException.errorCodeName}", exportException)
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            val item = MediaItem.Builder().setUri(Uri.fromFile(input)).apply {
                if (startMs != null && endMs != null) {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs)
                            .setEndPositionMs(endMs)
                            .build(),
                    )
                }
            }.build()
            if (crop == null) {
                transformer.start(item, output.absolutePath)
            } else {
                // O Crop usa coordenadas de -1 a 1, com o y crescendo para cima.
                val w = crop.window
                val left = -1f + 2f * w.left
                val top = 1f - 2f * w.top
                val effects = listOf(
                    Crop(left, left + 2f * w.width, top - 2f * w.height, top),
                    Presentation.createForWidthAndHeight(crop.width, crop.height, Presentation.LAYOUT_STRETCH_TO_FIT),
                )
                val edited = EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), effects)).build()
                transformer.start(edited, output.absolutePath)
            }
            handler.post(poll)
            cont.invokeOnCancellation {
                handler.post {
                    handler.removeCallbacks(poll)
                    transformer.cancel()
                }
            }
        }
    }
}
