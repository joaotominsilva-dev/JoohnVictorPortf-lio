package com.charis.shoot.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DriveFolder(val id: String, val name: String)

data class DriveUser(val name: String, val email: String)

/** Foto no Drive. Avaliação, rótulo e resultado da análise ficam em appProperties (na nuvem). */
data class Photo(
    val id: String,
    val name: String,
    val mime: String,
    val thumbLink: String?,
    val time: Long?,
    val width: Int,
    val height: Int,
    val size: Long,
    val props: Map<String, String>,
    /** Só na fonte local: pasta onde o arquivo está e caminho relativo à pasta escolhida. */
    val parentId: String? = null,
    val relPath: String? = null,
) {
    val rating: Int get() = props[K_RATING]?.toIntOrNull() ?: 0
    val color: String get() = props[K_COLOR] ?: ""
    val flag: String get() = props[K_FLAG] ?: ""
    val analyzed: Boolean get() = props[K_VERSION] != null
    val sharpness: Int? get() = props[K_SHARP]?.toIntOrNull()
    val eyes: String get() = props[K_EYES] ?: ""
    val faces: Int get() = props[K_FACES]?.toIntOrNull() ?: 0
    val hash: ULong? get() = props[K_HASH]?.toULongOrNull(16)

    val isPick get() = flag == FLAG_PICK
    val isReject get() = flag == FLAG_REJECT
    val decodable get() = mime in DECODABLE

    fun thumb(size: Int): String? {
        val link = thumbLink ?: return null
        val re = Regex("=s\\d+(-[a-z0-9-]+)?$")
        return if (re.containsMatchIn(link)) link.replace(re, "=s$size") else link
    }

    companion object {
        const val K_RATING = "cs_rating"
        const val K_COLOR = "cs_color"
        const val K_FLAG = "cs_flag"
        const val K_VERSION = "cs_v"
        const val K_SHARP = "cs_sharp"
        const val K_EYES = "cs_eyes"
        const val K_FACES = "cs_faces"
        const val K_HASH = "cs_hash"
        const val FLAG_PICK = "pick"
        const val FLAG_REJECT = "reject"
        val DECODABLE = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")
    }
}

class DriveException(message: String, val code: Int = 0) : IOException(message)

/** Cliente REST do Google Drive v3. */
class DriveClient(private val tokens: suspend (forceRefresh: Boolean) -> String?) {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val api = "https://www.googleapis.com/drive/v3"
    private val upload = "https://www.googleapis.com/upload/drive/v3"
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private suspend fun execute(auth: Boolean = true, build: (Request.Builder) -> Request.Builder): Response =
        withContext(Dispatchers.IO) {
            var attempt = 0
            while (true) {
                val rb = Request.Builder()
                if (auth) {
                    val t = tokens(attempt > 0) ?: throw DriveException("Sessão do Google expirada. Entre de novo.", 401)
                    rb.header("Authorization", "Bearer $t")
                }
                val resp = http.newCall(build(rb).build()).execute()
                if (resp.code == 401 && auth && attempt == 0) {
                    resp.close(); attempt++; continue
                }
                if (!resp.isSuccessful) {
                    val body = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                    resp.close()
                    val msg = runCatching { JSONObject(body).getJSONObject("error").getString("message") }
                        .getOrDefault("Erro ${resp.code} no Google Drive")
                    throw DriveException(msg, resp.code)
                }
                return@withContext resp
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException()
        }

    private suspend fun json(build: (Request.Builder) -> Request.Builder): JSONObject =
        execute(build = build).use { JSONObject(it.body!!.string()) }

    private fun url(path: String, vararg q: Pair<String, String>): HttpUrl {
        val b = "$api/$path".toHttpUrl().newBuilder()
        b.addQueryParameter("supportsAllDrives", "true")
        q.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    suspend fun about(): DriveUser {
        val o = json { it.url(url("about", "fields" to "user(displayName,emailAddress)")) }
        val u = o.getJSONObject("user")
        return DriveUser(u.optString("displayName"), u.optString("emailAddress"))
    }

    private suspend fun listAll(q: String, fields: String, orderBy: String = "name"): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var page: String? = null
        do {
            val params = mutableListOf(
                "q" to q,
                "fields" to "nextPageToken,files($fields)",
                "pageSize" to "1000",
                "orderBy" to orderBy,
                "includeItemsFromAllDrives" to "true",
            )
            page?.let { params += "pageToken" to it }
            val o = json { it.url(url("files", *params.toTypedArray())) }
            val arr = o.optJSONArray("files") ?: JSONArray()
            for (i in 0 until arr.length()) out += arr.getJSONObject(i)
            page = o.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out
    }

    suspend fun listFolders(parentId: String?, sharedWithMe: Boolean): List<DriveFolder> {
        val q = if (sharedWithMe && parentId == null)
            "sharedWithMe and mimeType='$FOLDER' and trashed=false"
        else "'${parentId ?: "root"}' in parents and mimeType='$FOLDER' and trashed=false"
        return listAll(q, "id,name").map { DriveFolder(it.getString("id"), it.getString("name")) }
    }

    suspend fun listPhotos(folderId: String): List<Photo> {
        val q = "'$folderId' in parents and trashed=false and mimeType contains 'image/'"
        val fields = "id,name,mimeType,thumbnailLink,size,createdTime," +
            "imageMediaMetadata(time,width,height),appProperties"
        return listAll(q, fields).map { o ->
            val meta = o.optJSONObject("imageMediaMetadata")
            val props = HashMap<String, String>()
            o.optJSONObject("appProperties")?.let { ap -> ap.keys().forEach { k -> props[k] = ap.getString(k) } }
            Photo(
                id = o.getString("id"),
                name = o.getString("name"),
                mime = o.optString("mimeType"),
                thumbLink = o.optString("thumbnailLink").ifEmpty { null },
                time = parseExifTime(meta?.optString("time")) ?: parseIso(o.optString("createdTime")),
                width = meta?.optInt("width") ?: 0,
                height = meta?.optInt("height") ?: 0,
                size = o.optString("size").toLongOrNull() ?: 0L,
                props = props,
            )
        }.sortedWith(compareBy<Photo>({ it.time ?: Long.MAX_VALUE }, { it.name }))
    }

    /** Grava (ou apaga, com valor null) chaves em appProperties. Devolve o mapa atualizado. */
    suspend fun patchProps(fileId: String, values: Map<String, String?>): Map<String, String> {
        val ap = JSONObject()
        values.forEach { (k, v) -> ap.put(k, v ?: JSONObject.NULL) }
        val body = JSONObject().put("appProperties", ap).toString().toRequestBody(jsonType)
        val o = json { it.url(url("files/$fileId", "fields" to "id,appProperties")).patch(body) }
        val out = HashMap<String, String>()
        o.optJSONObject("appProperties")?.let { r -> r.keys().forEach { k -> out[k] = r.getString(k) } }
        return out
    }

    suspend fun ensureFolder(parentId: String, name: String): String {
        val esc = name.replace("\\", "\\\\").replace("'", "\\'")
        val found = listAll("'$parentId' in parents and name='$esc' and mimeType='$FOLDER' and trashed=false", "id")
        if (found.isNotEmpty()) return found[0].getString("id")
        val body = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER)
            .put("parents", JSONArray().put(parentId))
            .toString().toRequestBody(jsonType)
        return json { it.url(url("files", "fields" to "id")).post(body) }.getString("id")
    }

    suspend fun move(fileId: String, fromId: String, toId: String) {
        val body = "{}".toRequestBody(jsonType)
        execute {
            it.url(url("files/$fileId", "addParents" to toId, "removeParents" to fromId, "fields" to "id")).patch(body)
        }.close()
    }

    suspend fun copy(fileId: String, toId: String, name: String) {
        val body = JSONObject().put("name", name).put("parents", JSONArray().put(toId))
            .toString().toRequestBody(jsonType)
        execute { it.url(url("files/$fileId/copy", "fields" to "id")).post(body) }.close()
    }

    suspend fun downloadMedia(fileId: String): ByteArray =
        execute { it.url(url("files/$fileId", "alt" to "media")) }.use { it.body!!.bytes() }

    /** Baixa uma miniatura do Drive (tenta com e sem o token). */
    suspend fun fetchThumb(link: String): ByteArray = try {
        execute { it.url(link) }.use { it.body!!.bytes() }
    } catch (e: DriveException) {
        execute(auth = false) { it.url(link) }.use { it.body!!.bytes() }
    }

    suspend fun uploadJpeg(parentId: String, name: String, bytes: ByteArray): String {
        val meta = JSONObject().put("name", name).put("mimeType", "image/jpeg")
            .put("parents", JSONArray().put(parentId)).toString()
        val multipart = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(meta.toRequestBody(jsonType))
            .addPart(bytes.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val u = "$upload/files".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("supportsAllDrives", "true")
            .addQueryParameter("fields", "id")
            .build()
        return json { it.url(u).post(multipart) }.getString("id")
    }

    companion object {
        const val FOLDER = "application/vnd.google-apps.folder"

        private fun parseExifTime(s: String?): Long? {
            if (s.isNullOrBlank()) return null
            return runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(s)?.time }.getOrNull()
        }

        private fun parseIso(s: String?): Long? {
            if (s.isNullOrBlank()) return null
            return runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(s.take(19))?.time
            }.getOrNull()
        }
    }
}
