package com.joohn.baixavideos.engine

import android.media.MediaMetadataRetriever
import java.io.File

/** Medidas de um vídeo já baixado, lidas pelo próprio Android. */
internal object VideoShape {
    /** Largura e altura como o vídeo aparece na tela (já girado), ou null se não deu para ler. */
    fun size(file: File): Pair<Int, Int>? = read(file) { r ->
        val width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        when {
            width <= 0 || height <= 0 -> null
            rotation % 180 != 0 -> height to width
            else -> width to height
        }
    }

    fun durationSec(file: File): Double? = read(file) { r ->
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }?.div(1000.0)
    }

    private fun <T> read(file: File, block: (MediaMetadataRetriever) -> T?): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            block(retriever)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
