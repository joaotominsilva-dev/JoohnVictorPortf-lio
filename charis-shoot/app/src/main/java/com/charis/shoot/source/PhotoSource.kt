package com.charis.shoot.source

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil.request.ImageRequest
import android.content.Context
import com.charis.shoot.drive.Photo
import kotlin.math.max

/** Foto aberta para edição. [note] aparece embaixo da imagem (ex.: RAW editado pela prévia). */
class Loaded(val original: ByteArray?, val full: Bitmap, val note: String? = null)

/** De onde vêm as fotos: Google Drive ou uma pasta do aparelho (memória, cartão SD, HD/pendrive USB). */
interface PhotoSource {
    val title: String
    val isLocal: Boolean
    /** Nome do lugar onde as marcações ficam salvas (para mensagens). */
    val storageName: String

    suspend fun list(): List<Photo>
    suspend fun patch(p: Photo, values: Map<String, String?>): Map<String, String>
    suspend fun analysisBitmap(p: Photo): Bitmap?
    fun imageRequest(ctx: Context, p: Photo, size: Int, token: String?): ImageRequest?

    /** Cria (ou acha) a subpasta de destino e devolve o identificador dela. */
    suspend fun prepareTarget(name: String): String
    suspend fun transfer(p: Photo, targetId: String, targetName: String, move: Boolean)

    suspend fun loadForEdit(p: Photo): Loaded
    suspend fun saveEdited(p: Photo, jpeg: ByteArray): String

    /** Grava o que estiver pendente (fonte local). */
    suspend fun flush() {}
}

internal fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    if (o.outWidth <= 0) return null
    var sample = 1
    while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
