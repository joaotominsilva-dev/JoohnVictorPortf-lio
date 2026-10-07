package com.joohn.baixavideos.engine

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Sessão do Instagram para o yt-dlp. O login é feito no próprio site (WebView) e os cookies
 * ficam só no armazenamento interno do app, em formato Netscape, que é o que o --cookies lê.
 */
object InstagramSession {
    private const val COOKIE_URL = "https://www.instagram.com"
    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    fun init(context: Context) {
        _loggedIn.value = hasSession(cookieFile(context))
    }

    private fun cookieFile(context: Context) = File(context.filesDir, "cookies/instagram.txt")

    /** Lê os cookies da WebView; retorna true quando já existe uma sessão logada. */
    fun captureFromWebView(context: Context): Boolean {
        val raw = CookieManager.getInstance().getCookie(COOKIE_URL) ?: return false
        val cookies = raw.split(';')
            .map { it.trim() }
            .filter { '=' in it }
            .map { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            .filter { (name, _) -> name.isNotEmpty() }
        if (cookies.none { (name, value) -> name == "sessionid" && value.isNotEmpty() }) return false

        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val body = buildString {
            append("# Netscape HTTP Cookie File\n")
            cookies.forEach { (name, value) ->
                append(".instagram.com\tTRUE\t/\tTRUE\t").append(expiry).append('\t')
                    .append(name).append('\t').append(value).append('\n')
            }
        }
        val file = cookieFile(context)
        file.parentFile?.mkdirs()
        file.writeText(body)
        CookieManager.getInstance().flush()
        _loggedIn.value = true
        return true
    }

    /**
     * Cópia descartável dos cookies para um download. O yt-dlp regrava o arquivo ao terminar,
     * e dois downloads simultâneos escrevendo no mesmo arquivo poderiam corrompê-lo.
     */
    fun copyFor(context: Context, workDir: File): File? {
        val source = cookieFile(context)
        if (!hasSession(source)) return null
        return source.copyTo(File(workDir, ".cookies.txt"), overwrite = true)
    }

    fun logout(context: Context) {
        cookieFile(context).delete()
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        _loggedIn.value = false
    }

    private fun hasSession(file: File): Boolean =
        file.isFile && file.readLines().any { it.split('\t').getOrNull(5) == "sessionid" }
}
