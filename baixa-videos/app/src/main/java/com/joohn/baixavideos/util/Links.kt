package com.joohn.baixavideos.util

enum class Platform(val label: String, val badge: String) {
    YOUTUBE("YouTube", "YT"),
    INSTAGRAM("Instagram", "IG"),
    TIKTOK("TikTok", "TT"),
    OTHER("Link", "WEB"),
}

object Links {
    private val urlRegex = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
    private val hostRegex = Regex("""^https?://(?:[^@/?#]*@)?([^/?#:]+)""", RegexOption.IGNORE_CASE)
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}”’\"'"

    /** Primeiro link http(s) encontrado em um texto (o "compartilhar" dos apps traz texto junto). */
    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = urlRegex.find(text) ?: return null
        return match.value.trimEnd { it in TRAILING_PUNCTUATION }.takeIf { hostOf(it) != null }
    }

    fun hostOf(url: String): String? =
        hostRegex.find(url)?.groupValues?.get(1)?.lowercase()?.takeIf { '.' in it }

    fun platformOf(url: String): Platform {
        val host = hostOf(url) ?: return Platform.OTHER
        fun matches(domain: String) = host == domain || host.endsWith(".$domain")
        return when {
            matches("youtube.com") || matches("youtu.be") || matches("youtube-nocookie.com") -> Platform.YOUTUBE
            matches("instagram.com") || matches("instagr.am") -> Platform.INSTAGRAM
            matches("tiktok.com") -> Platform.TIKTOK
            else -> Platform.OTHER
        }
    }
}
