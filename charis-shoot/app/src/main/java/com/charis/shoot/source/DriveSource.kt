package com.charis.shoot.source

import android.content.Context
import android.graphics.Bitmap
import coil.request.ImageRequest
import com.charis.shoot.drive.DriveClient
import com.charis.shoot.drive.Photo
import com.charis.shoot.edit.Editor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DriveSource(
    private val drive: DriveClient,
    private val folderId: String,
    override val title: String,
) : PhotoSource {
    override val isLocal = false
    override val storageName = "Google Drive"

    override suspend fun list() = drive.listPhotos(folderId)

    override suspend fun patch(p: Photo, values: Map<String, String?>) = drive.patchProps(p.id, values)

    override suspend fun analysisBitmap(p: Photo): Bitmap? = withContext(Dispatchers.IO) {
        val link = p.thumb(1024)
        val bytes = when {
            link != null -> drive.fetchThumb(link)
            p.decodable && p.size in 1..60_000_000 -> drive.downloadMedia(p.id)
            else -> return@withContext null
        }
        decodeSampled(bytes, 1024)
    }

    override fun imageRequest(ctx: Context, p: Photo, size: Int, token: String?): ImageRequest? {
        val url = p.thumb(size) ?: return null
        return ImageRequest.Builder(ctx)
            .data(url)
            .apply { token?.let { addHeader("Authorization", "Bearer $it") } }
            .memoryCacheKey("${p.id}_$size")
            .diskCacheKey("${p.id}_$size")
            .crossfade(true)
            .build()
    }

    override suspend fun prepareTarget(name: String) = drive.ensureFolder(folderId, name)

    override suspend fun transfer(p: Photo, targetId: String, targetName: String, move: Boolean) {
        if (move) drive.move(p.id, folderId, targetId) else drive.copy(p.id, targetId, p.name)
    }

    override suspend fun loadForEdit(p: Photo): Loaded = withContext(Dispatchers.IO) {
        if (p.decodable) {
            val bytes = drive.downloadMedia(p.id)
            Loaded(bytes, Editor.decode(bytes))
        } else {
            val link = p.thumb(3000) ?: throw IllegalStateException("Este arquivo não tem prévia no Drive para editar.")
            Loaded(
                null, Editor.decode(drive.fetchThumb(link)),
                "Arquivo RAW: a edição usa a prévia do Drive (até 3000 px) e salva um JPEG.",
            )
        }
    }

    override suspend fun saveEdited(p: Photo, jpeg: ByteArray): String {
        val target = drive.ensureFolder(folderId, EDITED)
        val name = p.name.substringBeforeLast('.') + "_charis.jpg"
        drive.uploadJpeg(target, name, jpeg)
        return name
    }

    companion object {
        const val EDITED = "Charis Editadas"
    }
}
