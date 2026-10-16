package com.joohn.baixavideos.engine.music

import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.abs

/** O que dá para saber da música só pelo YouTube (ou outro site), antes de procurar no catálogo. */
data class SongGuess(
    val track: String,
    val artist: String?,
    val album: String?,
    val year: String?,
    val genre: String?,
    val trackNumber: Int?,
    val durationSec: Double?,
    val thumbnail: String?,
    val videoId: String?,
    val youtube: Boolean,
)

/** Uma faixa encontrada no catálogo (iTunes). */
data class CatalogTrack(
    val track: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val year: String?,
    val genre: String?,
    val trackNumber: Int?,
    val durationSec: Double?,
    val artworkUrl: String?,
)

/** Letra encontrada; [synced] em formato LRC ("[00:12.34] verso"). */
data class Lyrics(val text: String, val synced: Boolean)

object SongGuesser {
    // Partes entre parênteses ou colchetes que não fazem parte do nome da música.
    private val noiseWords = listOf(
        "official", "oficial", "videoclip", "clipe", "lyric", "letra", "legendad", "visualizer",
        "visualiser", "video", "vídeo", "audio", "áudio", "explicit", "remaster",
    )

    // Palavras curtas só contam inteiras ("hd" não pode apagar "(Shadow Remix)").
    private val noiseShort = Regex("""(^|[^\p{L}\p{N}])(hd|hq|4k|mv|m/v|dvd)([^\p{L}\p{N}]|$)""")
    private val bracket = Regex("""\s*[(\[【]([^)\]】]*)[)\]】]""")
    private val dashes = Regex("""\s+[-–—|]\s+""")
    private val topic = Regex("""\s*-\s*topic$""", RegexOption.IGNORE_CASE)
    private val vevo = Regex("""vevo$""", RegexOption.IGNORE_CASE)

    /** Monta o palpite a partir da linha JSON que o yt-dlp imprime (veja Downloads). */
    fun fromYtDlp(json: String): SongGuess? = runCatching { fromYtDlp(JSONObject(json)) }.getOrNull()

    fun fromYtDlp(o: JSONObject): SongGuess? {
        val title = o.text("title") ?: return null
        val uploader = (o.text("channel") ?: o.text("uploader"))?.let(::cleanUploader)
        val metaArtist = o.optJSONArray("artists")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.joinToString(", ")
        }?.takeIf { it.isNotBlank() } ?: o.text("artist") ?: o.text("creator")
        val metaTrack = o.text("track")

        val (parsedArtist, parsedTrack) = splitTitle(title)
        val track = cleanTrack(metaTrack ?: parsedTrack)
        val artist = metaArtist ?: parsedArtist ?: uploader
        val year = o.text("release_year") ?: o.text("release_date")?.take(4)
        val extractor = o.text("extractor_key").orEmpty()
        return SongGuess(
            track = track.ifBlank { title },
            artist = artist?.takeIf { it.isNotBlank() },
            album = o.text("album"),
            year = year?.takeIf { it.length == 4 },
            genre = o.text("genre") ?: o.optJSONArray("genres")?.optString(0)?.takeIf { it.isNotBlank() },
            trackNumber = if (o.has("track_number") && !o.isNull("track_number")) o.optInt("track_number") else null,
            durationSec = o.optDouble("duration").takeIf { !it.isNaN() && it > 0 },
            thumbnail = o.text("thumbnail"),
            videoId = o.text("id"),
            youtube = extractor.startsWith("Youtube", ignoreCase = true),
        )
    }

    /** "Artista - Música (Official Video)" vira ("Artista", "Música"). Sem traço, só a música. */
    fun splitTitle(title: String): Pair<String?, String> {
        val withoutNoise = stripNoise(title)
        val parts = withoutNoise.split(dashes, limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            parts[0].trim() to parts[1].trim()
        } else {
            null to withoutNoise.trim()
        }
    }

    /** Tira do nome da música o que é do vídeo: "(Official Video)", "[Letra]", " | Clipe" etc. */
    fun cleanTrack(track: String): String {
        var t = stripNoise(track)
        // Depois de " | " costuma vir o nome do canal ou do álbum, não da música.
        t = t.split(Regex("""\s+\|\s+"""), limit = 2)[0]
        return t.trim().trim('-', '–', '—', '|').trim()
    }

    private fun stripNoise(text: String): String = bracket.replace(text) { m ->
        val inside = m.groupValues[1].lowercase()
        if (noiseWords.any { inside.contains(it) } || noiseShort.containsMatchIn(inside)) "" else m.value
    }.replace(Regex("""\s{2,}"""), " ").trim()

    private fun cleanUploader(name: String): String =
        name.replace(topic, "").replace(vevo, "").replace(Regex("""\s*(official|oficial)\s*$""", RegexOption.IGNORE_CASE), "").trim()

    private fun JSONObject.text(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).trim().takeIf { it.isNotBlank() && it != "NA" }
}

object MusicMatch {
    /** Compara nomes ignorando acentos, caixa, pontuação e "feat. Fulano". */
    fun normalize(text: String): String {
        val noAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
        return noAccents.lowercase()
            .replace(Regex("""[(\[].*?[)\]]"""), " ")
            .replace(Regex("""\b(feat|ft|featuring|participacao)\b.*$"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    fun similar(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y || x.contains(y) || y.contains(x)) return true
        val tx = x.split(' ').toSet()
        val ty = y.split(' ').toSet()
        val common = tx.intersect(ty).size.toDouble()
        return common / maxOf(tx.size, ty.size) >= 0.6
    }

    /**
     * Escolhe, entre os resultados do iTunes, a faixa que é mesmo a música (ou nenhuma).
     * Também tenta com artista e música trocados ("Música - Artista" é comum nos títulos).
     * Sem artista não escolhe nada: só o nome pegaria a música errada de outra pessoa.
     */
    fun pickCatalog(guess: SongGuess, results: List<CatalogTrack>): CatalogTrack? {
        val artist = guess.artist ?: return null
        return pick(guess, artist, results) ?: pick(guess.copy(track = artist, artist = guess.track), guess.track, results)
    }

    private fun pick(guess: SongGuess, artist: String, results: List<CatalogTrack>): CatalogTrack? =
        results
            .filter { similar(it.track, guess.track) && artistMatches(artist, it.artist) }
            .maxByOrNull { score(guess, it) }

    private fun artistMatches(expected: String, found: String): Boolean =
        similar(expected, found) || expected.split(Regex("""\s*(,|&| e | x | feat\.?|ft\.?)\s*""", RegexOption.IGNORE_CASE))
            .any { part -> part.isNotBlank() && similar(part, found) }

    private fun score(guess: SongGuess, c: CatalogTrack): Double {
        var s = 0.0
        if (normalize(c.track) == normalize(guess.track)) s += 3
        if (guess.artist != null && normalize(c.artist) == normalize(guess.artist)) s += 2
        if (guess.album != null && similar(c.album, guess.album)) s += 2
        // Versões ao vivo, remix etc. só ganham se o vídeo também for.
        val extra = listOf("live", "ao vivo", "remix", "acoustic", "acustico", "instrumental", "karaoke")
        val cName = c.track.lowercase() + " " + c.album.orEmpty().lowercase()
        val gName = guess.track.lowercase()
        extra.forEach { word -> if (cName.contains(word) && !gName.contains(word)) s -= 2 }
        val d1 = guess.durationSec
        val d2 = c.durationSec
        if (d1 != null && d2 != null) s += maxOf(0.0, 2 - abs(d1 - d2) / 10)
        return s
    }

    /**
     * Guarda só os versos do trecho cortado, com o tempo contado a partir do começo do corte.
     * Letras sem tempo voltam como estão.
     */
    fun shiftLrc(lrc: String, startSec: Double, lengthSec: Double): String {
        val line = Regex("""^\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?](.*)$""")
        val out = StringBuilder()
        for (raw in lrc.lines()) {
            val m = line.find(raw.trim()) ?: continue
            val (mm, ss, frac, text) = m.destructured
            val seconds = mm.toInt() * 60 + ss.toInt() + if (frac.isEmpty()) 0.0 else ("0.$frac").toDouble()
            val t = seconds - startSec
            if (t < -0.5 || t > lengthSec) continue
            val c = maxOf(0.0, t)
            val min = (c / 60).toInt()
            val sec = c - min * 60
            out.append(String.format(java.util.Locale.US, "[%02d:%05.2f]%s\n", min, sec, text))
        }
        return out.toString().trimEnd()
    }
}
