package com.charis.shoot.source

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.max

/** Miniatura de um arquivo local, gerada e guardada no cache do app. */
data class LocalThumb(val uri: String, val name: String, val mime: String, val modified: Long, val size: Int)

object LocalImages {
    val RAW_EXT = setOf("cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf", "rw2", "dng", "pef", "srw", "x3f", "3fr", "iiq", "erf", "kdc", "mos")
    private val IMG_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isRaw(name: String) = ext(name) in RAW_EXT
    fun isPhoto(name: String, mime: String) = isRaw(name) || ext(name) in IMG_EXT || mime.startsWith("image/")

    fun readAll(ctx: Context, uri: Uri): ByteArray =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Não abriu o arquivo")

    /**
     * Maior JPEG embutido no RAW (CR2, NEF, ARW, RAF, ORF, RW2, DNG… guardam uma prévia em tamanho cheio
     * ou quase). Procura os marcadores de início (FFD8FF) e fim (FFD9) de cada JPEG.
     */
    fun rawPreview(raw: ByteArray): ByteArray? {
        val starts = ArrayList<Int>()
        var i = 0
        val n = raw.size
        while (i < n - 3) {
            if (raw[i] == 0xFF.toByte() && raw[i + 1] == 0xD8.toByte() && raw[i + 2] == 0xFF.toByte()) starts += i
            i++
        }
        if (starts.isEmpty()) return null
        val candidates = starts.mapIndexed { idx, s ->
            val limit = if (idx + 1 < starts.size) starts[idx + 1] else n
            var e = limit - 2
            while (e > s && !(raw[e] == 0xFF.toByte() && raw[e + 1] == 0xD9.toByte())) e--
            s to (e + 2)
        }.filter { (s, e) -> e - s > 20_000 }.sortedByDescending { (s, e) -> e - s }
        for ((s, e) in candidates) {
            val jpeg = raw.copyOfRange(s, e)
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
            if (o.outWidth > 0) return jpeg
        }
        // Nenhum segmento isolado decodificou: tenta do primeiro início grande até o fim do arquivo.
        return null
    }

    private fun orientationDegrees(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees
    }.getOrDefault(0)

    fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    /** Prévia de RAW pronta para usar: JPEG embutido, girado conforme o EXIF do RAW. */
    fun rawBitmap(raw: ByteArray, maxSide: Int): Bitmap? {
        val jpeg = rawPreview(raw) ?: return null
        val bmp = decodeSampled(jpeg, maxSide) ?: return null
        val deg = orientationDegrees(raw).takeIf { it != 0 } ?: orientationDegrees(jpeg)
        return rotate(Editor2.fit(bmp, maxSide), deg)
    }

    /** Decodifica qualquer foto local com no máximo [maxSide] px, já na orientação certa. */
    fun decode(ctx: Context, uri: Uri, name: String, maxSide: Int): Bitmap? {
        if (isRaw(name)) return rawBitmap(readAll(ctx, uri), maxSide)
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val m = max(w, h)
            if (m > maxSide) {
                val s = maxSide.toFloat() / m
                dec.setTargetSize(max(1, (w * s).toInt()), max(1, (h * s).toInt()))
            }
        }
    }

    private fun key(t: LocalThumb): String {
        val d = MessageDigest.getInstance("SHA-1").digest("${t.uri}|${t.modified}|${t.size}".toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    /** Arquivo JPEG da miniatura (gera na primeira vez). */
    suspend fun thumbFile(ctx: Context, t: LocalThumb): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
        val f = File(dir, key(t) + ".jpg")
        if (f.exists() && f.length() > 0) return@withContext f
        val bmp = decode(ctx, Uri.parse(t.uri), t.name, t.size)
            ?: throw IllegalStateException("Sem prévia para ${t.name}")
        val tmp = File(dir, f.name + ".tmp")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        tmp.renameTo(f)
        f
    }

    class ThumbFetcher(private val ctx: Context, private val data: LocalThumb) : Fetcher {
        override suspend fun fetch(): FetchResult {
            val f = thumbFile(ctx, data)
            return SourceResult(
                source = ImageSource(f.toOkioPath()),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK,
            )
        }

        class Factory(private val ctx: Context) : Fetcher.Factory<LocalThumb> {
            override fun create(data: LocalThumb, options: Options, imageLoader: ImageLoader): Fetcher =
                ThumbFetcher(ctx, data)
        }
    }
}

private object Editor2 {
    fun fit(bmp: Bitmap, maxSide: Int): Bitmap {
        val m = max(bmp.width, bmp.height)
        if (m <= maxSide) return bmp
        val s = maxSide.toFloat() / m
        val out = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).toInt()), max(1, (bmp.height * s).toInt()), true)
        if (out !== bmp) bmp.recycle()
        return out
    }
}
