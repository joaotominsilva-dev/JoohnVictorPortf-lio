package com.joohn.baixavideos.data

import android.content.Context
import android.util.Log
import com.joohn.baixavideos.engine.DownloadMode
import com.joohn.baixavideos.engine.DownloadTask
import com.joohn.baixavideos.engine.SavedFile
import com.joohn.baixavideos.engine.TaskStatus
import com.joohn.baixavideos.engine.VideoQuality
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Histórico dos downloads concluídos, guardado em JSON no armazenamento interno. */
internal object History {
    private const val FILE_NAME = "history.json"
    private const val MAX_ITEMS = 300

    fun load(context: Context): List<DownloadTask> = try {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) {
            emptyList()
        } else {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { runCatching { fromJson(array.getJSONObject(it)) }.getOrNull() }
        }
    } catch (e: Exception) {
        Log.w("History", "Histórico ilegível, começando do zero", e)
        emptyList()
    }

    fun save(context: Context, tasks: List<DownloadTask>) {
        val array = JSONArray()
        tasks.filter { it.status == TaskStatus.DONE }.take(MAX_ITEMS).forEach { array.put(toJson(it)) }
        val file = File(context.filesDir, FILE_NAME)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(array.toString())
            tmp.delete()
        }
    }

    private fun toJson(task: DownloadTask) = JSONObject().apply {
        put("id", task.id)
        put("url", task.url)
        put("mode", task.mode.name)
        put("quality", task.quality.name)
        put("title", task.title)
        put("uploader", task.uploader)
        put("thumbnail", task.thumbnail)
        put("createdAt", task.createdAt)
        put("finishedAt", task.finishedAt)
        put("files", JSONArray().apply {
            task.files.forEach { f ->
                put(JSONObject().put("uri", f.uri).put("name", f.name).put("mime", f.mime).put("size", f.size))
            }
        })
    }

    private fun fromJson(o: JSONObject): DownloadTask {
        val filesJson = o.optJSONArray("files") ?: JSONArray()
        val files = (0 until filesJson.length()).map {
            val f = filesJson.getJSONObject(it)
            SavedFile(f.getString("uri"), f.getString("name"), f.getString("mime"), f.optLong("size"))
        }
        return DownloadTask(
            id = o.getString("id"),
            url = o.getString("url"),
            mode = runCatching { DownloadMode.valueOf(o.getString("mode")) }.getOrDefault(DownloadMode.VIDEO),
            quality = runCatching { VideoQuality.valueOf(o.getString("quality")) }.getOrDefault(VideoQuality.P1080),
            status = TaskStatus.DONE,
            title = o.text("title"),
            uploader = o.text("uploader"),
            thumbnail = o.text("thumbnail"),
            progress = 1f,
            files = files,
            createdAt = o.optLong("createdAt"),
            finishedAt = if (o.has("finishedAt")) o.optLong("finishedAt") else null,
        )
    }

    private fun JSONObject.text(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
