package com.joohn.baixavideos.engine

import android.content.Context
import com.joohn.baixavideos.BuildConfig
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Atualiza o yt-dlp direto das releases do GitHub. Usa os redirecionamentos de
 * github.com/.../releases/latest em vez da API, que tem limite de 60 consultas por hora
 * por IP (e IPs de operadora móvel são compartilhados por muita gente).
 */
internal object YtDlpUpdater {
    private fun repo(nightly: Boolean) = if (nightly) "yt-dlp/yt-dlp-nightly-builds" else "yt-dlp/yt-dlp"

    fun latestTag(nightly: Boolean): String {
        val conn = open("https://github.com/${repo(nightly)}/releases/latest", followRedirects = false)
        try {
            val code = conn.responseCode
            val location = conn.getHeaderField("Location")
            if (code !in 300..399 || location.isNullOrBlank()) throw IOException("O GitHub respondeu HTTP $code")
            val tag = location.substringAfter("/releases/tag/", "").substringBefore('?').trim('/')
            if (tag.isEmpty()) throw IOException("Resposta inesperada do GitHub")
            return URLDecoder.decode(tag, "UTF-8")
        } finally {
            conn.disconnect()
        }
    }

    fun download(context: Context, tag: String, nightly: Boolean): File {
        val tmp = File(context.cacheDir, "yt-dlp-update.tmp")
        val conn = open("https://github.com/${repo(nightly)}/releases/download/$tag/yt-dlp", followRedirects = true)
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("Falha ao baixar a atualização (HTTP $code)")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        // O yt-dlp para Unix é um zip com "#!/usr/bin/env python3" na frente.
        val head = tmp.inputStream().use { stream -> ByteArray(2).also { stream.read(it) } }
        if (tmp.length() < 1_000_000 || head[0] != '#'.code.toByte() || head[1] != '!'.code.toByte()) {
            tmp.delete()
            throw IOException("O arquivo baixado não é um yt-dlp válido")
        }
        return tmp
    }

    /** Troca o binário usado pelo youtubedl-android; se a versão nova não rodar, volta a antiga. */
    fun install(context: Context, newBinary: File, verify: () -> String): String {
        val dir = File(File(context.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName)
        val target = File(dir, YoutubeDL.ytdlpBin)
        val backup = File(dir, YoutubeDL.ytdlpBin + ".bak")
        dir.mkdirs()
        backup.delete()
        if (target.exists() && !target.renameTo(backup)) throw IOException("Não foi possível preparar a atualização")
        try {
            newBinary.copyTo(target, overwrite = true)
            val version = verify()
            backup.delete()
            return version
        } catch (e: Exception) {
            target.delete()
            if (backup.exists()) backup.renameTo(target)
            throw IOException("A versão nova do yt-dlp não funcionou neste aparelho", e)
        }
    }

    private fun open(url: String, followRedirects: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = followRedirects
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "BaixaVideos/${BuildConfig.VERSION_NAME} (Android)")
        }
}
