package com.joohn.baixavideos.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStreamReader

class CutException(message: String, val details: String) : Exception(message)

/**
 * Corta um arquivo já baixado com o FFmpeg do youtubedl-android, no próprio aparelho.
 * É o caminho mais seguro para o corte: o download inteiro usa o baixador do yt-dlp
 * (rápido, com progresso e novas tentativas) e o corte local não depende da rede.
 */
internal object LocalCutter {
    private const val TAG = "LocalCutter"
    private const val TAIL_LINES = 25

    /** O binário que o youtubedl-android extrai para a pasta de bibliotecas nativas. */
    fun binary(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    /** As mesmas pastas de bibliotecas que o youtubedl-android usa ao chamar o FFmpeg. */
    fun libraryPath(context: Context): String =
        File(context.noBackupFilesDir, "youtubedl-android/packages").listFiles().orEmpty()
            .map { File(it, "usr/lib") }
            .filter { it.isDirectory }
            .sortedBy { it.absolutePath }
            .joinToString(":") { it.absolutePath }

    /**
     * Grava em [output] só o trecho [trim] de [input]. O vídeo sai em H.264 + AAC (MP4) e o
     * áudio em MP3; recodificar as pontas é o que faz o corte cair no segundo exato.
     * Bloqueia até terminar. [register] recebe o processo para que ele possa ser cancelado.
     */
    fun cut(
        context: Context,
        input: File,
        output: File,
        trim: Trim,
        register: (Process) -> Unit,
        onProgress: (Float) -> Unit,
    ) {
        val audio = output.extension.equals("mp3", ignoreCase = true)
        val args = mutableListOf(
            binary(context).absolutePath, "-y", "-hide_banner", "-nostdin",
            "-ss", trim.startSec.toString(), "-i", input.absolutePath, "-t", trim.lengthSec.toString(),
            "-map_metadata", "0",
        )
        args += if (audio) {
            listOf("-map", "0:a:0", "-c:a", "libmp3lame", "-b:a", "192k", "-id3v2_version", "3")
        } else {
            listOf(
                "-map", "0:v:0?", "-map", "0:a:0?",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart",
            )
        }
        args += output.absolutePath

        val builder = ProcessBuilder(args).redirectErrorStream(true)
        builder.environment()["LD_LIBRARY_PATH"] = libraryPath(context)
        val process = builder.start()
        register(process)

        val tail = ArrayDeque<String>()
        InputStreamReader(process.inputStream, Charsets.UTF_8).use { reader ->
            val line = StringBuilder()
            while (true) {
                val c = reader.read()
                if (c == -1 || c == '\r'.code || c == '\n'.code) {
                    if (line.isNotEmpty()) {
                        val text = line.toString()
                        OutputParser.ffmpegTime(text)?.let { onProgress((it / trim.lengthSec).toFloat().coerceIn(0f, 1f)) }
                            ?: run {
                                tail.addLast(text)
                                if (tail.size > TAIL_LINES) tail.removeFirst()
                            }
                        line.setLength(0)
                    }
                    if (c == -1) break
                } else {
                    line.append(c.toChar())
                }
            }
        }
        val code = process.waitFor()
        if (code != 0 || !output.isFile || output.length() == 0L) {
            val details = tail.joinToString("\n")
            Log.w(TAG, "Corte falhou (código $code): $details")
            throw CutException("Não consegui cortar o trecho no aparelho (FFmpeg, código $code).", details)
        }
    }
}
