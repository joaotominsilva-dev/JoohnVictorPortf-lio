package com.charis.shoot.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.pow

data class Adjust(
    val exposure: Float = 0f,     // -2..2 EV
    val contrast: Float = 0f,     // -1..1
    val saturation: Float = 0f,   // -1..1
    val temperature: Float = 0f,  // -1..1 (frio .. quente)
    val tint: Float = 0f,         // -1..1 (verde .. magenta)
) {
    val isIdentity get() = this == Adjust()

    /** Matriz 4x5 (formato do Android, deslocamentos em 0..255). */
    fun matrix(): FloatArray {
        val cm = ColorMatrix()
        val e = 2f.pow(exposure)
        cm.setScale(e, e, e, 1f)

        val c = 1f + contrast
        val t = 128f * (1f - c)
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        cm.postConcat(ColorMatrix().apply { setSaturation(1f + saturation) })
        val r = 1f + 0.22f * temperature
        val b = 1f - 0.22f * temperature
        val g = 1f - 0.12f * tint
        cm.postConcat(ColorMatrix().apply { setScale(r, g, b, 1f) })
        return cm.array
    }
}

object Editor {
    const val MAX_SIDE = 4096

    /** Decodifica já com a orientação certa e no máximo [maxSide] px no lado maior. */
    fun decode(bytes: ByteArray, maxSide: Int = MAX_SIDE): Bitmap {
        val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
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

    fun scaled(bmp: Bitmap, maxSide: Int): Bitmap {
        val m = max(bmp.width, bmp.height)
        if (m <= maxSide) return bmp
        val s = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).toInt()), max(1, (bmp.height * s).toInt()), true)
    }

    fun apply(src: Bitmap, adj: Adjust): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(adj.matrix())) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
    )

    /** JPEG final, com os dados da câmera do original. */
    fun encodeJpeg(bmp: Bitmap, original: ByteArray?, cacheDir: File): ByteArray {
        val tmp = File(cacheDir, "charis-edit.jpg")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        runCatching {
            val dst = ExifInterface(tmp)
            if (original != null) {
                val src = ExifInterface(ByteArrayInputStream(original))
                EXIF_TAGS.forEach { tag -> src.getAttribute(tag)?.let { dst.setAttribute(tag, it) } }
            }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, "Charis Shoot")
            dst.saveAttributes()
        }
        return tmp.readBytes().also { tmp.delete() }
    }
}
