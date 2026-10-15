package com.joohn.baixavideos.engine

import android.content.Context
import android.util.Log
import com.joohn.baixavideos.util.Links
import com.joohn.baixavideos.util.Platform
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class VideoInfoException(message: String) : Exception(message)

/**
 * Lê título, miniatura, duração e um endereço de reprodução de um link, sem baixar o vídeo.
 * Alimenta a prévia do link e a prévia do corte.
 */
object VideoInfoFetcher {
    private const val TAG = "VideoInfo"
    private const val INFO_PREFIX = "__BV_INFO__"
    private const val STREAMS_PREFIX = "__BV_PREV__"
    private const val SINGLE_PREFIX = "__BV_ONE__"
    private const val STREAM_FIELDS = "url,protocol,manifest_url,http_headers,cookies"
    private const val CACHE_SIZE = 30

    private val counter = AtomicInteger()
    private val cache = object : LinkedHashMap<String, VideoInfo>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VideoInfo>) = size > CACHE_SIZE
    }

    fun cached(url: String): VideoInfo? = synchronized(cache) { cache[url] }

    suspend fun fetch(context: Context, url: String): VideoInfo {
        cached(url)?.let { return it }
        Engine.awaitReady()
        val app = context.applicationContext
        val processId = "info-${counter.incrementAndGet()}"
        val workDir = File(app.cacheDir, "info/$processId")
        val lines = ConcurrentLinkedQueue<String>()
        try {
            workDir.mkdirs()
            val platform = Links.platformOf(url)
            val cookies = if (platform == Platform.INSTAGRAM) InstagramSession.copyFor(app, workDir) else null
            val request = buildRequest(app, url, cookies)
            val failure = Engine.withProcess {
                try {
                    runInterruptible(Dispatchers.IO) {
                        YoutubeDL.execute(request, processId, true) { _, _, line -> lines += line }
                    }
                    null
                } catch (e: YoutubeDL.CanceledException) {
                    e
                } catch (e: com.yausername.youtubedl_android.YoutubeDLException) {
                    e
                }
            }
            val output = lines.toList()
            val infoLine = output.lastOrNull { it.startsWith(INFO_PREFIX) }
            if (infoLine == null) {
                val raw = output.lastOrNull { it.startsWith("ERROR:") }?.let(ErrorTranslator::clean)
                    ?: failure?.message?.trim().orEmpty().ifEmpty { "O link não respondeu." }
                Log.w(TAG, "Sem dados para $url: $raw")
                throw VideoInfoException(ErrorTranslator.friendly(raw, platform, InstagramSession.loggedIn.value))
            }
            val info = parse(
                url = url,
                info = infoLine.removePrefix(INFO_PREFIX),
                streams = output.lastOrNull { it.startsWith(STREAMS_PREFIX) }?.removePrefix(STREAMS_PREFIX),
                single = output.lastOrNull { it.startsWith(SINGLE_PREFIX) }?.removePrefix(SINGLE_PREFIX),
            )
            synchronized(cache) { cache[url] = info }
            return info
        } finally {
            withContext(NonCancellable) { workDir.deleteRecursively() }
        }
    }

    private fun buildRequest(app: Context, url: String, cookies: File?) = YoutubeDLRequest(url).apply {
        addOption("--ignore-config")
        addOption("--no-update")
        addOption("--skip-download")
        addOption("--no-playlist")
        addOption("--playlist-items", "1")
        addOption("--cache-dir", File(app.cacheDir, "yt-dlp").absolutePath)
        addOption("--remote-components", "ejs:github")
        // Para a prévia: arquivo único em http(s) e resolução baixa, que abrem rápido no player.
        addOption("-S", "proto,res:480,+codec:avc:m4a")
        addOption("-f", "b/bv*+ba")
        addOption("--print", "$INFO_PREFIX%(.{id,title,thumbnail,duration,uploader,channel})j")
        addOption("--print", "$STREAMS_PREFIX%(requested_formats.:.{$STREAM_FIELDS}|null)j")
        addOption("--print", "$SINGLE_PREFIX%(.{$STREAM_FIELDS})j")
        if (cookies != null) addOption("--cookies", cookies.absolutePath)
    }

    internal fun parse(url: String, info: String, streams: String?, single: String?): VideoInfo {
        val o = JSONObject(info)
        val requested = streams?.let { runCatching { JSONArray(it) }.getOrNull() }
        val preview = buildList {
            if (requested != null && requested.length() > 0) {
                for (i in 0 until requested.length()) requested.optJSONObject(i)?.let(::stream)?.let(::add)
            } else {
                single?.let { runCatching { JSONObject(it) }.getOrNull() }?.let(::stream)?.let(::add)
            }
        }
        return VideoInfo(
            url = url,
            title = o.text("title"),
            uploader = o.text("uploader") ?: o.text("channel"),
            thumbnail = o.text("thumbnail"),
            durationSec = o.optDouble("duration").takeIf { !it.isNaN() && it > 0 },
            preview = preview,
        )
    }

    private fun stream(o: JSONObject): PreviewStream? {
        val streamUrl = o.text("url") ?: return null
        val headers = o.optJSONObject("http_headers")?.let { h ->
            h.keys().asSequence().associateWith { h.optString(it) }
        }.orEmpty()
        return PreviewStream(
            url = streamUrl,
            protocol = o.text("protocol").orEmpty(),
            manifestUrl = o.text("manifest_url"),
            headers = headers,
            cookieHeader = o.text("cookies")?.let(::cookieHeader),
        )
    }

    /** Converte o campo "cookies" do yt-dlp (formato Set-Cookie) no cabeçalho Cookie. */
    internal fun cookieHeader(raw: String): String? {
        val attributes = setOf("domain", "path", "expires", "max-age", "version", "secure", "httponly", "samesite")
        return raw.split(';')
            .map { it.trim() }
            .filter { '=' in it && it.substringBefore('=').trim().lowercase() !in attributes }
            .joinToString("; ")
            .ifEmpty { null }
    }

    private fun JSONObject.text(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
