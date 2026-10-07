package com.joohn.baixavideos.engine

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Copia o arquivo final (baixado na pasta privada do app) para Download/BaixaVideos,
 * onde a galeria, o player de música e o gerenciador de arquivos enxergam.
 */
object MediaSaver {
    const val FOLDER = "BaixaVideos"
    val publicPath = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER"

    fun needsLegacyPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    suspend fun save(context: Context, file: File): SavedFile {
        val mime = mimeOf(file)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, file, mime)
        } else {
            saveLegacy(context, file, mime)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(context: Context, file: File, mime: String): SavedFile {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, publicPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("O Android não deixou criar o arquivo em Download.")
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("Não foi possível gravar o arquivo.")
            output.use { out -> file.inputStream().use { it.copyTo(out, BUFFER) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        val name = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: file.name
        return SavedFile(uri.toString(), name, mime, file.length())
    }

    private suspend fun saveLegacy(context: Context, file: File, mime: String): SavedFile {
        // Android 7 a 9: pasta pública com permissão de escrita; sem ela, fica na pasta do app.
        val baseDir = if (needsLegacyPermission(context)) {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        } else {
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }
        val dir = File(baseDir, FOLDER).apply { mkdirs() }
        val dest = uniqueFile(dir, file.name)
        file.copyTo(dest)
        val scanned = withTimeoutOrNull(10_000) { scan(context, dest, mime) }
        val uri = scanned ?: FileProvider.getUriForFile(context, "${context.packageName}.files", dest)
        return SavedFile(uri.toString(), dest.name, mime, dest.length())
    }

    private suspend fun scan(context: Context, file: File, mime: String): Uri? =
        suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime)) { _, uri ->
                if (cont.isActive) cont.resume(uri)
            }
        }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (candidate.exists()) candidate = File(dir, "$base (${n++})$ext")
        return candidate
    }

    fun mimeOf(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus", "ogg" -> "audio/ogg"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    /** Apaga do aparelho um arquivo salvo pelo app. Retorna false se o Android não deixar. */
    fun delete(context: Context, saved: SavedFile): Boolean = try {
        context.contentResolver.delete(Uri.parse(saved.uri), null, null) > 0
    } catch (_: Exception) {
        false
    }

    private const val BUFFER = 256 * 1024
}
