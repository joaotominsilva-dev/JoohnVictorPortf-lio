package com.charis.shoot

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Atualização pelo próprio app: lê apk/versao.json no GitHub (gravado a cada compilação),
 * baixa o APK novo e abre o instalador do Android. A assinatura é sempre a mesma, então
 * a versão nova instala por cima sem perder nada.
 */
object Updater {
    private const val REPO = "https://raw.githubusercontent.com/joaotominsilva-dev/JoohnVictorPortf-lio"
    private val BRANCHES = listOf("main", "ccr-08046427-ioi2e1")

    data class Info(val code: Long, val name: String, val notes: String, val apkUrl: String)

    fun installedCode(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    fun installedName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    /** Versão mais nova publicada (entre as branches), ou null se não achou. */
    suspend fun latest(http: OkHttpClient): Info? = withContext(Dispatchers.IO) {
        BRANCHES.mapNotNull { b ->
            val base = "$REPO/$b/charis-shoot/apk"
            runCatching {
                val req = Request.Builder().url("$base/versao.json?t=${System.currentTimeMillis() / 60000}").build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val o = JSONObject(r.body!!.string())
                    Info(
                        code = o.getLong("versionCode"),
                        name = o.optString("versionName"),
                        notes = o.optString("notas"),
                        apkUrl = "$base/" + o.optString("apk", "CharisShoot.apk"),
                    )
                }
            }.getOrNull()
        }.maxByOrNull { it.code }
    }

    suspend fun download(ctx: Context, http: OkHttpClient, info: Info, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "CharisShoot-${info.code}.apk")
            http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("Download falhou (${r.code})")
                val body = r.body!!
                val total = body.contentLength()
                var read = 0L
                body.byteStream().use { input ->
                    FileOutputStream(out).use { o ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress(read.toFloat() / total)
                        }
                    }
                }
            }
            out
        }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Abre a tela do Android para liberar "instalar apps desconhecidos" para o Charis Shoot. */
    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
