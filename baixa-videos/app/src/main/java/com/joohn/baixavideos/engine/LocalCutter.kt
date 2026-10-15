package com.joohn.baixavideos.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStreamReader

class CutException(message: String, val details: String) : Exception(message)

/**
 * Corta (ou acerta as pontas de) um arquivo já baixado com o FFmpeg do youtubedl-android, no
 * próprio aparelho. Para vídeo é a reserva do [TransformerCutter] (x264 no modo mais rápido);
 * para MP3 é o caminho normal.
 */
internal object LocalCutter {
    private const val TAG = "LocalCutter"
    private const val TAIL_LINES = 25

    private enum class VideoEncoder { SOFTWARE }

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
     * Grava em [output] [lengthSec] segundos de [input], a partir de [startSec] (ou do começo do
     * arquivo, quando ele já é o trecho baixado). O MP4 sai em H.264 + AAC e o MP3 em MP3.
     * Recodificar é o que faz o corte cair no segundo exato. Bloqueia até terminar.
     */
    fun cut(
        context: Context,
        input: File,
        output: File,
        startSec: Long?,
        lengthSec: Long,
        quality: VideoQuality,
        register: (Process) -> Unit,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ) {
        val audio = output.extension.equals("mp3", ignoreCase = true)
        val attempts = if (audio) listOf<VideoEncoder?>(null) else listOf(VideoEncoder.SOFTWARE)
        val failures = mutableListOf<String>()
        for (encoder in attempts) {
            if (isCancelled()) throw CutException("Cancelado.", "")
            val started = System.currentTimeMillis()
            val result = run(context, args(context, input, output, startSec, lengthSec, quality, encoder), lengthSec, register, onProgress)
            val seconds = (System.currentTimeMillis() - started) / 1000.0
            if (result.code == 0 && output.isFile && output.length() > 0L) {
                Log.i(TAG, "Corte pronto com ${encoder ?: "mp3"} em ${seconds}s: ${output.name}")
                return
            }
            Log.w(TAG, "Corte com ${encoder ?: "mp3"} falhou (código ${result.code}) em ${seconds}s:\n${result.tail}")
            failures += "[${encoder ?: "mp3"}] código ${result.code}\n${result.tail}"
            output.delete()
        }
        throw CutException("Não consegui cortar o trecho no aparelho.", failures.joinToString("\n\n"))
    }

    private fun args(
        context: Context,
        input: File,
        output: File,
        startSec: Long?,
        lengthSec: Long,
        quality: VideoQuality,
        encoder: VideoEncoder?,
    ): List<String> = buildList {
        add(binary(context).absolutePath)
        addAll(listOf("-y", "-hide_banner", "-nostdin"))
        if (startSec != null && startSec > 0) addAll(listOf("-ss", startSec.toString()))
        addAll(listOf("-i", input.absolutePath, "-t", lengthSec.toString(), "-map_metadata", "0"))
        when (encoder) {
            null -> addAll(listOf("-map", "0:a:0", "-c:a", "libmp3lame", "-b:a", "192k", "-id3v2_version", "3"))
            VideoEncoder.SOFTWARE -> addAll(
                listOf(
                    "-map", "0:v:0?", "-map", "0:a:0?",
                    "-c:v", "libx264", "-preset", "ultrafast", "-crf", crf(quality), "-pix_fmt", "yuv420p",
                ),
            )
        }
        if (encoder != null) addAll(listOf("-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart"))
        add(output.absolutePath)
    }

    /** O modo mais rápido do x264 comprime menos; um CRF um pouco maior segura o tamanho. */
    private fun crf(quality: VideoQuality): String = if (quality == VideoQuality.BEST) "20" else "23"

    private class Result(val code: Int, val tail: String)

    private fun run(
        context: Context,
        args: List<String>,
        lengthSec: Long,
        register: (Process) -> Unit,
        onProgress: (Float) -> Unit,
    ): Result {
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
                        val time = OutputParser.ffmpegTime(text)
                        if (time != null) {
                            onProgress((time / lengthSec.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f))
                        } else {
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
        return Result(process.waitFor(), tail.joinToString("\n"))
    }
}
