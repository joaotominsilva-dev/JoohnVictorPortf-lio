package com.joohn.baixavideos.engine

import android.content.Context
import com.joohn.baixavideos.util.Platform
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Um vídeo da busca do YouTube. */
data class SearchResult(
    val id: String,
    val title: String,
    val channel: String?,
    val durationSec: Double?,
    val viewCount: Long?,
    val live: Boolean,
) {
    val url: String get() = "https://www.youtube.com/watch?v=$id"
    val thumbnail: String get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
}

class SearchException(message: String) : Exception(message)

/**
 * Busca no YouTube pelo próprio yt-dlp ("ytsearchN:termo"), só com a lista de resultados
 * (--flat-playlist): um pedido rápido, sem abrir cada vídeo.
 */
object YouTubeSearch {
    private const val PREFIX = "__BV_SR__"
    private val counter = AtomicInteger()

    /** Resultados [from] (contando de 1) até [to] da busca por [query]. */
    suspend fun search(context: Context, query: String, from: Int, to: Int): List<SearchResult> {
        Engine.awaitReady()
        val app = context.applicationContext
        val processId = "search-${counter.incrementAndGet()}"
        val request = YoutubeDLRequest("ytsearch$to:${query.trim()}").apply {
            addOption("--ignore-config")
            addOption("--no-update")
            addOption("--flat-playlist")
            addOption("--playlist-items", "$from:$to")
            addOption("--cache-dir", File(app.cacheDir, "yt-dlp").absolutePath)
            addOption("--print", "$PREFIX%(.{id,title,channel,uploader,duration,view_count,live_status})j")
        }
        val lines = ConcurrentLinkedQueue<String>()
        val failure = Engine.withProcess {
            try {
                runInterruptible(Dispatchers.IO) {
                    YoutubeDL.execute(request, processId, true) { _, _, line -> lines += line }
                }
                null
            } catch (e: YoutubeDL.CanceledException) {
                e
            } catch (e: YoutubeDLException) {
                e
            }
        }
        val results = lines.mapNotNull { line -> line.substringAfter(PREFIX, "").takeIf { it.isNotEmpty() }?.let(::parse) }
        if (results.isEmpty() && failure != null) {
            val raw = lines.lastOrNull { it.startsWith("ERROR:") }?.let(ErrorTranslator::clean)
                ?: failure.message?.trim().orEmpty().ifEmpty { "A busca não respondeu." }
            throw SearchException(ErrorTranslator.friendly(raw, Platform.YOUTUBE, instagramLoggedIn = false))
        }
        return results
    }

    internal fun parse(json: String): SearchResult? = try {
        val o = JSONObject(json)
        val id = o.optString("id").takeIf { it.isNotBlank() && !o.isNull("id") }
        id?.let {
            SearchResult(
                id = it,
                title = o.text("title") ?: "Vídeo sem título",
                channel = o.text("channel") ?: o.text("uploader"),
                durationSec = o.optDouble("duration").takeIf { d -> !d.isNaN() && d > 0 },
                viewCount = if (o.isNull("view_count") || !o.has("view_count")) null else o.optLong("view_count"),
                live = o.text("live_status") == "is_live",
            )
        }
    } catch (_: JSONException) {
        null
    }

    private fun JSONObject.text(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
