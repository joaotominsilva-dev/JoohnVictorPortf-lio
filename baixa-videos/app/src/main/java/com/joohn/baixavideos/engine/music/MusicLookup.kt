package com.joohn.baixavideos.engine.music

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Consultas públicas e gratuitas (sem conta) para completar as músicas:
 * - iTunes Search (Apple): nome oficial, artista, álbum, ano, gênero, faixa e a capa em 1000x1000;
 * - LRCLIB: letra, sincronizada quando existe.
 * Tudo é opcional: se a rede falhar, a música sai com o que o YouTube informou.
 */
object MusicLookup {
    private const val TIMEOUT_MS = 10_000
    private const val USER_AGENT = "CharisDownloader (https://github.com/joaotominsilva-dev/JoohnVictorPortf-lio)"

    /** Procura a faixa no catálogo do iTunes, primeiro na loja do país do aparelho. */
    fun findInCatalog(guess: SongGuess, country: String): CatalogTrack? {
        if (guess.artist == null) return null
        val term = listOfNotNull(guess.artist, guess.track).joinToString(" ")
        val stores = listOf(country.ifBlank { "US" }.uppercase(), "US").distinct()
        for (store in stores) {
            val url = "https://itunes.apple.com/search?media=music&entity=song&limit=15" +
                "&country=$store&term=${encode(term)}"
            val results = runCatching { parseCatalog(get(url)) }.getOrElse { return null }
            MusicMatch.pickCatalog(guess, results)?.let { return it }
        }
        return null
    }

    internal fun parseCatalog(body: String): List<CatalogTrack> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("kind") != "song") return@mapNotNull null
            val track = o.text("trackName") ?: return@mapNotNull null
            val artist = o.text("artistName") ?: return@mapNotNull null
            CatalogTrack(
                track = track,
                artist = artist,
                album = o.text("collectionName")?.let(::cleanAlbum),
                albumArtist = o.text("collectionArtistName") ?: artist,
                year = o.text("releaseDate")?.take(4),
                genre = o.text("primaryGenreName"),
                trackNumber = if (o.has("trackNumber")) o.optInt("trackNumber") else null,
                durationSec = if (o.has("trackTimeMillis")) o.optLong("trackTimeMillis") / 1000.0 else null,
                // A loja entrega a capa em 100x100; o mesmo endereço serve em 1000x1000.
                artworkUrl = o.text("artworkUrl100")?.replace(Regex("""/\d+x\d+bb\."""), "/1000x1000bb."),
            )
        }
    }

    /** "Álbum - Single" e "Álbum - EP" viram só "Álbum", como os players mostram. */
    private fun cleanAlbum(name: String): String = name.replace(Regex("""\s+-\s+(Single|EP)$"""), "")

    /** Letra no LRCLIB; prefere a sincronizada. Sem artista, não arrisca a letra de outra música. */
    fun findLyrics(track: String, artist: String?, album: String?, durationSec: Double?): Lyrics? {
        if (artist == null) return null
        if (durationSec != null) {
            val url = "https://lrclib.net/api/get?track_name=${encode(track)}&artist_name=${encode(artist)}" +
                (album?.let { "&album_name=${encode(it)}" } ?: "") + "&duration=${durationSec.toInt()}"
            runCatching { lyricsOf(JSONObject(get(url))) }.getOrNull()?.let { return it }
        }
        val search = "https://lrclib.net/api/search?track_name=${encode(track)}&artist_name=${encode(artist)}"
        val candidates = runCatching { JSONArray(get(search)) }.getOrNull() ?: return null
        val list = (0 until candidates.length()).mapNotNull { candidates.optJSONObject(it) }
            .filter { MusicMatch.similar(it.text("trackName"), track) && MusicMatch.similar(it.text("artistName"), artist) }
        val best = list.sortedWith(
            compareBy<JSONObject>(
                { if (it.text("syncedLyrics") != null) 0 else 1 },
                { o -> durationSec?.let { abs(o.optDouble("duration", 0.0) - it) } ?: 0.0 },
            ),
        ).firstOrNull() ?: return null
        return lyricsOf(best)
    }

    internal fun lyricsOf(o: JSONObject): Lyrics? {
        o.text("syncedLyrics")?.let { return Lyrics(it, synced = true) }
        o.text("plainLyrics")?.let { return Lyrics(it, synced = false) }
        return null
    }

    fun download(url: String, maxBytes: Int = 5 * 1024 * 1024): ByteArray? = runCatching {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { input ->
                val bytes = input.readBytes()
                bytes.takeIf { it.size in 1..maxBytes }
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun get(url: String): String {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept", "application/json, image/*")
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

    private fun JSONObject.text(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).trim().takeIf { it.isNotBlank() }
}
