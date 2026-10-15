package com.joohn.baixavideos.engine

import android.content.Context
import com.joohn.baixavideos.util.Links
import com.joohn.baixavideos.util.Platform
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cópia leve (até 480p) de um vídeo curto para a prévia do corte. Usada quando o site não
 * deixa o player tocar direto do endereço (o TikTok responde 403 fora do próprio app).
 */
object PreviewFile {
    private const val PREFIX = "__BV_PFILE__"
    private const val KEEP = 4
    private val counter = AtomicInteger()

    suspend fun fetch(context: Context, url: String): File {
        val app = context.applicationContext
        val dir = File(app.cacheDir, "preview").apply { mkdirs() }
        val key = sha1(url).take(16)
        dir.listFiles { f -> f.isFile && f.name.startsWith(key) }?.firstOrNull()?.let { return it }
        prune(dir)
        Engine.awaitReady()

        val workDir = File(dir, "tmp-$key-${counter.incrementAndGet()}").apply { mkdirs() }
        try {
            val cookies = if (Links.platformOf(url) == Platform.INSTAGRAM) InstagramSession.copyFor(app, workDir) else null
            val request = YoutubeDLRequest(url).apply {
                addOption("--ignore-config")
                addOption("--no-update")
                addOption("--no-simulate")
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("--cache-dir", File(app.cacheDir, "yt-dlp").absolutePath)
                addOption("--remote-components", "ejs:github")
                addOption("-S", "res:480,+codec:avc:m4a")
                addOption("-f", "b/bv*+ba")
                addOption("--merge-output-format", "mp4")
                addOption("-o", File(workDir, "$key.%(ext)s").absolutePath)
                addOption("--print", "after_move:$PREFIX%(filepath)j")
                if (cookies != null) addOption("--cookies", cookies.absolutePath)
            }
            val lines = ConcurrentLinkedQueue<String>()
            val failure = Engine.withProcess {
                try {
                    runInterruptible(Dispatchers.IO) {
                        YoutubeDL.execute(request, "preview-${counter.get()}", true) { _, _, line -> lines += line }
                    }
                    null
                } catch (e: YoutubeDL.CanceledException) {
                    e
                } catch (e: YoutubeDLException) {
                    e
                }
            }
            val produced = lines.lastOrNull { PREFIX in it }
                ?.let { JSONArray("[" + it.substringAfter(PREFIX).trim() + "]").optString(0) }
                ?.let(::File)
                ?.takeIf { it.isFile }
                ?: throw IOException(failure?.message ?: "A prévia não foi gerada.")
            val target = File(dir, "$key.${produced.extension}")
            if (!produced.renameTo(target)) produced.copyTo(target, overwrite = true)
            return target
        } finally {
            withContext(NonCancellable) { workDir.deleteRecursively() }
        }
    }

    /** Guarda só as prévias mais recentes. */
    private fun prune(dir: File) {
        dir.listFiles()?.filter { it.isDirectory && it.name.startsWith("tmp-") }?.forEach { it.deleteRecursively() }
        dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
