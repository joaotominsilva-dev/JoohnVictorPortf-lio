package com.joohn.baixavideos.engine

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Interpreta a saída do yt-dlp. O app pede ao yt-dlp linhas próprias (via --print e
 * --progress-template) com prefixos fixos e valores em JSON, então não depende do texto
 * de log do yt-dlp, que muda entre versões.
 */
internal object OutputParser {
    const val META = "__BV_META__"
    const val PROGRESS = "__BV_PROG__"
    const val POSTPROCESS = "__BV_PP__"
    const val FILE = "__BV_FILE__"

    val metaTemplate =
        "before_dl:$META{\"id\":%(id|null)j,\"title\":%(title|null)j,\"uploader\":%(uploader|null)j," +
            "\"thumbnail\":%(thumbnail|null)j,\"index\":%(playlist_index|null)j,\"count\":%(n_entries|null)j}"

    val progressTemplate =
        "download:$PROGRESS[%(progress.downloaded_bytes|null)j,%(progress.total_bytes|null)j," +
            "%(progress.total_bytes_estimate|null)j,%(progress.speed|null)j,%(progress.eta|null)j," +
            "%(info.vcodec|null)j,%(info.acodec|null)j]"

    val postprocessTemplate = "postprocess:$POSTPROCESS[%(progress.status|null)j,%(progress.postprocessor|null)j]"

    val fileTemplate = "after_move:$FILE%(filepath|null)j"

    enum class Stream { VIDEO_ONLY, AUDIO_ONLY, MUXED }

    sealed interface Event {
        data class Meta(
            val title: String?,
            val uploader: String?,
            val thumbnail: String?,
            val index: Int?,
            val count: Int?,
        ) : Event

        data class Progress(
            val downloaded: Long?,
            val total: Long?,
            val speed: Double?,
            val eta: Long?,
            val stream: Stream,
        ) : Event {
            val fraction: Float?
                get() {
                    val done = downloaded ?: return null
                    val all = total?.takeIf { it > 0 } ?: return null
                    return (done.toDouble() / all).toFloat().coerceIn(0f, 1f)
                }
        }

        data class PostProcess(val status: String, val name: String) : Event
        data class File(val path: String) : Event
        data class Error(val message: String) : Event
    }

    fun parse(line: String): Event? = try {
        when {
            PROGRESS in line -> parseProgress(line.substringAfter(PROGRESS))
            META in line -> parseMeta(line.substringAfter(META))
            POSTPROCESS in line -> parsePostProcess(line.substringAfter(POSTPROCESS))
            FILE in line -> JSONArray("[" + line.substringAfter(FILE).trim() + "]")
                .optString(0).takeIf { it.isNotBlank() && it != "null" }?.let { Event.File(it) }
            line.startsWith("ERROR:") -> Event.Error(line.removePrefix("ERROR:").trim())
            else -> null
        }
    } catch (_: JSONException) {
        null
    }

    private fun parseMeta(json: String): Event.Meta {
        val o = JSONObject(json.trim())
        return Event.Meta(
            title = o.str("title"),
            uploader = o.str("uploader"),
            thumbnail = o.str("thumbnail"),
            index = o.num("index")?.toInt(),
            count = o.num("count")?.toInt(),
        )
    }

    private fun parseProgress(json: String): Event.Progress {
        val a = JSONArray(json.trim())
        val total = a.num(1) ?: a.num(2)
        val vcodec = a.str(5)
        val acodec = a.str(6)
        val stream = when {
            vcodec == "none" -> Stream.AUDIO_ONLY
            acodec == "none" && vcodec != null -> Stream.VIDEO_ONLY
            else -> Stream.MUXED
        }
        return Event.Progress(
            downloaded = a.num(0)?.toLong(),
            total = total?.toLong(),
            speed = a.num(3),
            eta = a.num(4)?.toLong(),
            stream = stream,
        )
    }

    private fun parsePostProcess(json: String): Event.PostProcess? {
        val a = JSONArray(json.trim())
        val status = a.str(0) ?: return null
        return Event.PostProcess(status, a.str(1).orEmpty())
    }

    private fun JSONObject.str(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private fun JSONObject.num(name: String): Double? =
        if (isNull(name)) null else optDouble(name).takeIf { !it.isNaN() }

    private fun JSONArray.str(index: Int): String? =
        if (isNull(index)) null else optString(index).takeIf { it.isNotBlank() }

    private fun JSONArray.num(index: Int): Double? =
        if (isNull(index)) null else optDouble(index).takeIf { !it.isNaN() }

    fun postProcessLabel(name: String, mode: DownloadMode): String = when {
        name == "Merger" -> "Juntando vídeo e áudio"
        name == "ExtractAudio" -> if (mode == DownloadMode.AUDIO) "Convertendo para MP3" else "Extraindo áudio"
        name == "Metadata" -> "Gravando informações"
        name == "EmbedThumbnail" -> "Inserindo capa"
        name == "MoveFiles" -> "Finalizando"
        name.startsWith("Fixup") -> "Ajustando arquivo"
        name.startsWith("Video") -> "Convertendo vídeo"
        else -> "Processando"
    }
}
