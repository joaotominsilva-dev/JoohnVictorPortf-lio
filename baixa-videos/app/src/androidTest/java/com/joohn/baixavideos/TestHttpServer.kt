package com.joohn.baixavideos

import android.content.res.AssetManager
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.URLDecoder
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Servidor HTTP mínimo que serve os assets de teste (um vídeo DASH) em 127.0.0.1.
 * Qualquer caminho terminado em .mpd devolve o manifesto, então o nome pedido vira o título do
 * vídeo no yt-dlp. [delayMs] e [chunkDelayMs] deixam o download lento o bastante para ser fotografado.
 */
class TestHttpServer(
    private val assets: AssetManager,
    private val root: String,
    private val delayMs: Long = 0,
    private val chunkDelayMs: Long = 0,
) : Closeable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort

    init {
        thread(isDaemon = true, name = "test-http") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                thread(isDaemon = true) { handle(client) }
            }
        }
    }

    fun url(path: String) = "http://127.0.0.1:$port/$path"

    private fun handle(client: Socket) = client.use { s ->
        val reader = s.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return@use
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0).orEmpty()
        val requested = URLDecoder.decode(parts.getOrNull(1).orEmpty().substringBefore('?').trimStart('/'), "UTF-8")
        val path = if (requested.endsWith(".mpd")) "manifest.mpd" else requested
        val body = try {
            assets.open("$root/$path").use { it.readBytes() }
        } catch (_: IOException) {
            null
        }
        if (delayMs > 0) Thread.sleep(delayMs)
        val out = s.getOutputStream()
        if (body == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } else {
            val type = when {
                path.endsWith(".mpd") -> "application/dash+xml"
                path.endsWith(".m4s") -> "video/iso.segment"
                else -> "application/octet-stream"
            }
            out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            if (method != "HEAD") {
                if (chunkDelayMs <= 0) {
                    out.write(body)
                } else {
                    body.toList().chunked(2048).forEach { chunk ->
                        out.write(chunk.toByteArray())
                        out.flush()
                        Thread.sleep(chunkDelayMs)
                    }
                }
            }
        }
        out.flush()
    }

    override fun close() = socket.close()
}
