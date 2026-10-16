package com.joohn.baixavideos.engine.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.joohn.baixavideos.engine.Trim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * Deixa o MP3 com cara de música de verdade: nome do arquivo e título só com o nome da música,
 * artista, álbum, ano, gênero, faixa, capa do álbum e letra (sincronizada quando existe).
 * Nunca derruba o download: o que não for encontrado fica de fora.
 */
object MusicTagger {
    private const val TAG = "MusicTagger"
    private const val COVER_SIZE = 1000

    data class Result(
        val file: File,
        val title: String,
        val artist: String?,
        val album: String?,
        val coverUrl: String?,
        val hasCover: Boolean,
        val lyrics: Lyrics?,
    )

    /** [ytDlpJson] é a linha __BV_MUSIC__ do yt-dlp; [fallbackTitle] serve quando ela não veio. */
    suspend fun tag(file: File, ytDlpJson: String?, fallbackTitle: String?, trim: Trim?): Result = withContext(Dispatchers.IO) {
        val guess = ytDlpJson?.let(SongGuesser::fromYtDlp) ?: run {
            val (artist, track) = SongGuesser.splitTitle(fallbackTitle ?: file.nameWithoutExtension.substringBefore(" ["))
            SongGuess(SongGuesser.cleanTrack(track), artist, null, null, null, null, null, null, null, false)
        }
        val catalog = runCatching { MusicLookup.findInCatalog(guess, Locale.getDefault().country) }
            .onFailure { Log.w(TAG, "Catálogo indisponível", it) }
            .getOrNull()

        // "Música (Remastered 2011)" vira só "Música"; "(feat. Fulano)" fica.
        val title = catalog?.track?.let(SongGuesser::cleanTrack)?.takeIf { it.isNotBlank() } ?: guess.track
        val artist = catalog?.artist ?: guess.artist
        val album = catalog?.album ?: guess.album

        var coverUrl = catalog?.artworkUrl
        var cover = coverUrl?.let { MusicLookup.download(it) }
        if (cover == null) {
            val (url, bytes) = videoCover(guess) ?: (null to null)
            coverUrl = url
            cover = bytes
        }

        val lyrics = runCatching {
            MusicLookup.findLyrics(title, artist, album, catalog?.durationSec ?: guess.durationSec)
        }.onFailure { Log.w(TAG, "Letra indisponível", it) }.getOrNull()
        val lyricsText = lyrics?.let {
            if (trim != null && it.synced) MusicMatch.shiftLrc(it.text, trim.startSec.toDouble(), trim.lengthSec.toDouble()) else it.text
        }?.takeIf { it.isNotBlank() }

        Id3Writer.write(
            file,
            MusicTags(
                title = title,
                artist = artist,
                album = album,
                albumArtist = catalog?.albumArtist ?: artist,
                year = catalog?.year ?: guess.year,
                genre = catalog?.genre ?: guess.genre,
                trackNumber = catalog?.trackNumber ?: guess.trackNumber,
                cover = cover,
                lyrics = lyricsText,
            ),
        )
        val named = File(file.parentFile, safeFileName(title) + "." + file.extension)
        val finalFile = if (named.absolutePath == file.absolutePath || named.exists()) file
        else if (file.renameTo(named)) named else file
        Log.i(
            TAG,
            "Música: \"$title\" | $artist | $album | capa=${cover != null} (${if (catalog != null) "catálogo" else "vídeo"}) | " +
                "letra=${lyrics?.let { if (it.synced) "sincronizada" else "simples" } ?: "não"}",
        )
        Result(finalFile, title, artist, album, coverUrl, cover != null, lyrics)
    }

    /** Capa tirada do vídeo, recortada em quadrado no centro (a capa dos clipes "Topic" fica ali). */
    private fun videoCover(guess: SongGuess): Pair<String, ByteArray>? {
        val candidates = buildList {
            if (guess.youtube && guess.videoId != null) {
                add("https://i.ytimg.com/vi/${guess.videoId}/maxresdefault.jpg")
                add("https://i.ytimg.com/vi/${guess.videoId}/hqdefault.jpg")
            }
            guess.thumbnail?.let { add(it) }
        }
        for (url in candidates) {
            val bytes = MusicLookup.download(url) ?: continue
            squareJpeg(bytes)?.let { return url to it }
        }
        return null
    }

    private fun squareJpeg(bytes: ByteArray): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val side = minOf(bitmap.width, bitmap.height)
        if (side < 64) return null
        val cropped = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val scaled = if (side > COVER_SIZE) Bitmap.createScaledBitmap(cropped, COVER_SIZE, COVER_SIZE, true) else cropped
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }

    /** Nome de arquivo válido em qualquer sistema, sem perder acentos. */
    fun safeFileName(title: String): String =
        title.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim().trim('.')
            .take(120)
            .ifBlank { "Música" }
}
