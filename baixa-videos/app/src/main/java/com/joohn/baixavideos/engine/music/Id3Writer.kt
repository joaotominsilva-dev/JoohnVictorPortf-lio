package com.joohn.baixavideos.engine.music

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/** Etiquetas de uma música, gravadas no MP3 como ID3v2.3 (lido por qualquer player). */
data class MusicTags(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val trackNumber: Int? = null,
    val cover: ByteArray? = null,
    val lyrics: String? = null,
    val lyricsLanguage: String = "por",
)

/**
 * Grava as etiquetas ID3v2.3 de um MP3: título, artista, álbum, artista do álbum, ano, gênero,
 * faixa, capa (APIC) e letra (USLT). Substitui a etiqueta ID3v2 que já existir.
 */
object Id3Writer {
    private const val PADDING = 1024

    fun write(file: File, tags: MusicTags) {
        val audioStart = existingTagSize(file)
        val tag = buildTag(tags)
        val tmp = File(file.parentFile, file.name + ".tag")
        tmp.outputStream().buffered().use { out ->
            out.write(tag)
            file.inputStream().use { input ->
                input.skipFully(audioStart)
                input.copyTo(out)
            }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    internal fun buildTag(tags: MusicTags): ByteArray {
        val frames = ByteArrayOutputStream()
        frames.textFrame("TIT2", tags.title)
        tags.artist?.let { frames.textFrame("TPE1", it) }
        tags.album?.let { frames.textFrame("TALB", it) }
        tags.albumArtist?.let { frames.textFrame("TPE2", it) }
        tags.year?.let { frames.textFrame("TYER", it) }
        tags.genre?.let { frames.textFrame("TCON", it) }
        tags.trackNumber?.let { frames.textFrame("TRCK", it.toString()) }
        tags.cover?.let { frames.frame("APIC", apic(it)) }
        tags.lyrics?.takeIf { it.isNotBlank() }?.let { frames.frame("USLT", uslt(it, tags.lyricsLanguage)) }
        val body = frames.toByteArray()
        val size = body.size + PADDING
        return ByteArrayOutputStream().apply {
            write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
            write(synchsafe(size))
            write(body)
            write(ByteArray(PADDING))
        }.toByteArray()
    }

    /** Tamanho da etiqueta ID3v2 no começo do arquivo (0 se não houver). */
    private fun existingTagSize(file: File): Long {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 10) return 0
            val header = ByteArray(10)
            raf.readFully(header)
            if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return 0
            val size = ((header[6].toInt() and 0x7F) shl 21) or ((header[7].toInt() and 0x7F) shl 14) or
                ((header[8].toInt() and 0x7F) shl 7) or (header[9].toInt() and 0x7F)
            val footer = if (header[3].toInt() == 4 && (header[5].toInt() and 0x10) != 0) 10 else 0
            return 10L + size + footer
        }
    }

    private fun ByteArrayOutputStream.textFrame(id: String, text: String) {
        // Codificação 1: UTF-16 com BOM, que o ID3v2.3 aceita para acentos.
        frame(id, byteArrayOf(1) + utf16(text))
    }

    private fun ByteArrayOutputStream.frame(id: String, body: ByteArray) {
        write(id.toByteArray(Charsets.ISO_8859_1))
        write(int32(body.size))
        write(byteArrayOf(0, 0))
        write(body)
    }

    private fun apic(image: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        val mime = if (image.size > 3 && image[1] == 'P'.code.toByte() && image[2] == 'N'.code.toByte()) "image/png" else "image/jpeg"
        write(0) // ISO-8859-1
        write(mime.toByteArray(Charsets.ISO_8859_1))
        write(0)
        write(3) // capa (frente)
        write(0) // descrição vazia
        write(image)
    }.toByteArray()

    private fun uslt(lyrics: String, language: String): ByteArray = ByteArrayOutputStream().apply {
        write(1)
        write(language.padEnd(3).take(3).toByteArray(Charsets.ISO_8859_1))
        write(utf16("")) // descrição vazia
        write(byteArrayOf(0, 0))
        write(utf16(lyrics))
    }.toByteArray()

    private fun utf16(text: String): ByteArray = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)

    private fun int32(value: Int) = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun synchsafe(value: Int) = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(), ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(), (value and 0x7F).toByte(),
    )

    private fun java.io.InputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                if (read() == -1) return
                left--
            } else {
                left -= skipped
            }
        }
    }
}
