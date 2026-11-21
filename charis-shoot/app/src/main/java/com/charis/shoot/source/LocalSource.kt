package com.charis.shoot.source

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import coil.request.ImageRequest
import com.charis.shoot.drive.Photo
import com.charis.shoot.edit.Editor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Pasta escolhida pelo seletor do Android (Storage Access Framework): memória interna, cartão SD,
 * HD/pendrive no USB-OTG ou leitor de cartão da câmera. As marcações ficam em
 * "charis-shoot.json" dentro da própria pasta (se ela aceitar gravação) ou no app.
 */
class LocalSource(private val ctx: Context, private val tree: Uri, override val title: String) : PhotoSource {
    override val isLocal = true
    override var storageName = "pasta"

    private val cr = ctx.contentResolver
    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)

    /** caminho relativo -> propriedades */
    private val marks = HashMap<String, MutableMap<String, String>>()
    private val lock = Mutex()
    private var dirty = false
    private var marksDoc: Uri? = null
    private val appFile = File(ctx.filesDir, "local-" + sha1(tree.toString()) + ".json")

    private data class Child(val id: String, val name: String, val mime: String, val size: Long, val modified: Long)

    private fun children(docId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<Child>()
        cr.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Child(
                    c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "",
                    if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4),
                )
            }
        }
        return out
    }

    override suspend fun list(): List<Photo> = withContext(Dispatchers.IO) {
        loadMarks()
        val out = ArrayList<Photo>()
        fun walk(docId: String, prefix: String, depth: Int) {
            for (ch in children(docId)) {
                if (ch.mime == Document.MIME_TYPE_DIR) {
                    // Desce em subpastas (ex.: DCIM/100CANON), mas não nas que o próprio app cria.
                    if (depth < 3 && !ch.name.startsWith("Charis ") && !ch.name.startsWith(".")) {
                        walk(ch.id, "$prefix${ch.name}/", depth + 1)
                    }
                } else if (LocalImages.isPhoto(ch.name, ch.mime) && !ch.name.startsWith(".")) {
                    val rel = prefix + ch.name
                    out += Photo(
                        id = DocumentsContract.buildDocumentUriUsingTree(tree, ch.id).toString(),
                        name = ch.name,
                        mime = ch.mime.ifEmpty { "image/*" },
                        thumbLink = null,
                        time = ch.modified.takeIf { it > 0 },
                        width = 0, height = 0,
                        size = ch.size,
                        props = marks[rel]?.toMap() ?: emptyMap(),
                        parentId = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString(),
                        relPath = rel,
                    )
                }
            }
        }
        walk(rootId, "", 0)
        out.sortedWith(compareBy<Photo>({ it.time ?: Long.MAX_VALUE }, { it.name }))
    }

    // ---------- marcações ----------

    private fun loadMarks() {
        marks.clear()
        val inFolder = children(rootId).firstOrNull { it.name == MARKS_FILE }
        val text = if (inFolder != null) {
            marksDoc = DocumentsContract.buildDocumentUriUsingTree(tree, inFolder.id)
            runCatching { cr.openInputStream(marksDoc!!)?.use { it.readBytes().decodeToString() } }.getOrNull()
        } else null
        val json = text ?: appFile.takeIf { it.exists() }?.readText()
        storageName = if (inFolder != null) "a pasta ($MARKS_FILE)" else "o app"
        if (json.isNullOrBlank()) return
        runCatching {
            val photos = JSONObject(json).getJSONObject("photos")
            photos.keys().forEach { rel ->
                val o = photos.getJSONObject(rel)
                val m = HashMap<String, String>()
                o.keys().forEach { k -> m[k] = o.getString(k) }
                marks[rel] = m
            }
        }
    }

    override suspend fun patch(p: Photo, values: Map<String, String?>): Map<String, String> = lock.withLock {
        val rel = p.relPath ?: return@withLock p.props
        val m = marks.getOrPut(rel) { HashMap() }
        values.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v }
        if (m.isEmpty()) marks.remove(rel)
        dirty = true
        m.toMap()
    }

    override suspend fun flush() {
        withContext(Dispatchers.IO) { lock.withLock {
            if (!dirty) return@withLock
            val photos = JSONObject()
            marks.forEach { (rel, m) -> photos.put(rel, JSONObject(m as Map<*, *>)) }
            val text = JSONObject().put("app", "Charis Shoot").put("version", 1).put("photos", photos).toString(1)
            val okFolder = runCatching {
                val doc = marksDoc ?: DocumentsContract.createDocument(cr, rootDoc, "application/json", MARKS_FILE)
                    ?.also { marksDoc = it } ?: error("sem gravação")
                cr.openOutputStream(doc, "wt")!!.use { it.write(text.toByteArray()) }
            }.isSuccess
            storageName = if (okFolder) "a pasta ($MARKS_FILE)" else "o app"
            runCatching { appFile.writeText(text) }
            dirty = false
        } }
    }

    // ---------- imagens ----------

    private fun thumb(p: Photo, size: Int) = LocalThumb(p.id, p.name, p.mime, p.time ?: 0L, size)

    override suspend fun analysisBitmap(p: Photo): Bitmap? = withContext(Dispatchers.IO) {
        val f = LocalImages.thumbFile(ctx, thumb(p, 1024))
        android.graphics.BitmapFactory.decodeFile(f.absolutePath)
    }

    override fun imageRequest(ctx: Context, p: Photo, size: Int, token: String?): ImageRequest =
        ImageRequest.Builder(ctx)
            .data(thumb(p, size))
            .memoryCacheKey("${p.id}_$size")
            .crossfade(true)
            .build()

    // ---------- separar ----------

    private fun findOrCreateDir(name: String): Uri {
        children(rootId).firstOrNull { it.name == name && it.mime == Document.MIME_TYPE_DIR }?.let {
            return DocumentsContract.buildDocumentUriUsingTree(tree, it.id)
        }
        return DocumentsContract.createDocument(cr, rootDoc, Document.MIME_TYPE_DIR, name)
            ?: throw IllegalStateException("Esta pasta não aceita gravação. Escolha uma pasta com permissão de escrita.")
    }

    override suspend fun prepareTarget(name: String): String = withContext(Dispatchers.IO) {
        findOrCreateDir(name).toString()
    }

    override suspend fun transfer(p: Photo, targetId: String, targetName: String, move: Boolean) {
      withContext(Dispatchers.IO) {
        val src = Uri.parse(p.id)
        val target = Uri.parse(targetId)
        var done = false
        if (move && p.parentId != null) {
            done = runCatching { DocumentsContract.moveDocument(cr, src, Uri.parse(p.parentId), target) != null }
                .getOrDefault(false)
        }
        if (!done) {
            val mime = p.mime.takeIf { it.contains('/') && !it.endsWith("*") } ?: "application/octet-stream"
            val dst = DocumentsContract.createDocument(cr, target, mime, p.name)
                ?: throw IllegalStateException("Não criou a cópia de ${p.name}")
            cr.openInputStream(src)!!.use { input -> cr.openOutputStream(dst)!!.use { input.copyTo(it, 1 shl 16) } }
            if (move) DocumentsContract.deleteDocument(cr, src)
        }
        // As marcações acompanham a foto.
        val rel = p.relPath
        if (rel != null) lock.withLock {
            val m = marks[rel]
            if (m != null) {
                marks["$targetName/${p.name}"] = HashMap(m)
                if (move) marks.remove(rel)
                dirty = true
            }
        }
      }
    }

    // ---------- edição ----------

    override suspend fun loadForEdit(p: Photo): Loaded = withContext(Dispatchers.IO) {
        val bytes = LocalImages.readAll(ctx, Uri.parse(p.id))
        if (LocalImages.isRaw(p.name)) {
            val bmp = LocalImages.rawBitmap(bytes, Editor.MAX_SIDE)
                ?: throw IllegalStateException("Este RAW não tem prévia JPEG embutida.")
            Loaded(bytes, bmp, "Arquivo RAW: a edição usa a prévia JPEG embutida no RAW e salva um JPEG.")
        } else {
            Loaded(bytes, Editor.decode(bytes))
        }
    }

    override suspend fun saveEdited(p: Photo, jpeg: ByteArray): String = withContext(Dispatchers.IO) {
        val dir = findOrCreateDir(DriveSource.EDITED)
        val name = p.name.substringBeforeLast('.') + "_charis.jpg"
        val doc = DocumentsContract.createDocument(cr, dir, "image/jpeg", name)
            ?: throw IllegalStateException("Não conseguiu criar o arquivo")
        cr.openOutputStream(doc)!!.use { it.write(jpeg) }
        name
    }

    companion object {
        const val MARKS_FILE = "charis-shoot.json"

        private fun sha1(s: String) =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

        fun displayName(ctx: Context, tree: Uri): String = runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            ctx.contentResolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: "Pasta do aparelho"
    }
}
