package com.joohn.baixavideos.engine

import com.joohn.baixavideos.util.Links
import com.joohn.baixavideos.util.Platform

enum class DownloadMode { VIDEO, AUDIO }

enum class VideoQuality(val label: String, val maxHeight: Int?) {
    BEST("Máxima", null),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
}

enum class TaskStatus {
    QUEUED, PREPARING, DOWNLOADING, PROCESSING, SAVING, DONE, FAILED, CANCELED;

    val isActive: Boolean get() = this in ACTIVE

    private companion object {
        val ACTIVE = setOf(QUEUED, PREPARING, DOWNLOADING, PROCESSING, SAVING)
    }
}

/** Trecho escolhido para baixar, em segundos inteiros contados do início do vídeo. */
data class Trim(val startSec: Long, val endSec: Long) {
    init {
        require(startSec >= 0 && endSec > startSec) { "Trecho inválido: $startSec a $endSec" }
    }

    val lengthSec: Long get() = endSec - startSec
}

/** Formato do vídeo final: como veio ou recortado para ficar em pé (9:16) ou deitado (16:9). */
enum class Framing(val label: String, val ratio: String?, val aspect: Float?) {
    ORIGINAL("Original", null, null),
    VERTICAL("Vertical", "9:16", 9f / 16f),
    HORIZONTAL("Horizontal", "16:9", 16f / 9f);

    /** "vertical" / "horizontal" no meio de uma frase. */
    val word: String get() = label.lowercase()
}

/**
 * Recorte do quadro para o formato [framing]. [position] diz que parte fica: 0 é a esquerda (ou o
 * topo), 1 a direita (ou a base) e 0,5 o centro.
 */
data class Reframe(val framing: Framing, val position: Float = 0.5f) {
    init {
        require(framing != Framing.ORIGINAL) { "Original não recorta" }
        require(position in 0f..1f) { "Posição fora do quadro: $position" }
    }

    val aspect: Float get() = framing.aspect!!

    /** Parte do quadro que fica, ou null quando o vídeo já tem esse formato. */
    fun window(sourceAspect: Float): CropWindow? {
        if (sourceAspect <= 0f || kotlin.math.abs(sourceAspect - aspect) / aspect < 0.02f) return null
        return if (sourceAspect > aspect) {
            val width = aspect / sourceAspect
            CropWindow(left = (1 - width) * position, top = 0f, width = width, height = 1f)
        } else {
            val height = sourceAspect / aspect
            CropWindow(left = 0f, top = (1 - height) * position, width = 1f, height = height)
        }
    }

    /** Se o ajuste da posição anda na horizontal (vídeo mais largo que o formato) ou na vertical. */
    fun movesHorizontally(sourceAspect: Float?): Boolean =
        if (sourceAspect == null || sourceAspect <= 0f) framing == Framing.VERTICAL else sourceAspect > aspect
}

/** Janela de recorte em frações do quadro (0 a 1), a partir do canto de cima à esquerda. */
data class CropWindow(val left: Float, val top: Float, val width: Float, val height: Float)

/** Dados lidos antes do download: alimentam a prévia e o corte. */
data class VideoInfo(
    val url: String,
    val title: String?,
    val uploader: String?,
    val thumbnail: String?,
    /** Duração em segundos; null quando o site não informa (lives e alguns links diretos). */
    val durationSec: Double?,
    /** Um endereço (vídeo com áudio) ou dois (vídeo e áudio separados) para tocar a prévia. */
    val preview: List<PreviewStream> = emptyList(),
    /** Largura sobre altura do vídeo, quando o site informa. */
    val aspect: Float? = null,
)

/** Endereço de reprodução de um formato, com os cabeçalhos que o site exige. */
data class PreviewStream(
    val url: String,
    val protocol: String,
    val manifestUrl: String?,
    val headers: Map<String, String>,
    val cookieHeader: String?,
)

data class SavedFile(
    val uri: String,
    val name: String,
    val mime: String,
    val size: Long,
)

data class DownloadTask(
    val id: String,
    val url: String,
    val mode: DownloadMode,
    val quality: VideoQuality,
    val trim: Trim? = null,
    /** Recorte para vertical ou horizontal; null mantém o formato original. */
    val reframe: Reframe? = null,
    /** Duração do vídeo inteiro, quando a prévia informou; decide como o corte é baixado. */
    val durationSec: Double? = null,
    val status: TaskStatus = TaskStatus.QUEUED,
    val title: String? = null,
    val uploader: String? = null,
    val thumbnail: String? = null,
    /** Progresso de 0 a 1; null quando a etapa não tem porcentagem (extração, conversão). */
    val progress: Float? = null,
    val stage: String? = null,
    val speedBps: Double? = null,
    val etaSeconds: Long? = null,
    val itemIndex: Int? = null,
    val itemCount: Int? = null,
    val files: List<SavedFile> = emptyList(),
    val error: String? = null,
    val errorDetails: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
) {
    val platform: Platform get() = Links.platformOf(url)
    val totalSize: Long get() = files.sumOf { it.size }
}
