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
