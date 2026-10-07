package com.joohn.baixavideos.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object Formats {
    private val ptBr = Locale.forLanguageTag("pt-BR")
    private val oneDecimal = DecimalFormat("0.0", DecimalFormatSymbols(ptBr))

    fun bytes(value: Long?): String {
        if (value == null || value < 0) return "--"
        val units = listOf("B", "KB", "MB", "GB")
        var size = value.toDouble()
        var unit = 0
        while (size >= 1024 && unit < units.lastIndex) {
            size /= 1024
            unit++
        }
        return if (unit == 0) "$value B" else "${oneDecimal.format(size)} ${units[unit]}"
    }

    fun speed(bytesPerSecond: Double?): String? =
        bytesPerSecond?.takeIf { it > 0 }?.let { "${bytes(it.toLong())}/s" }

    /** Timecode estilo câmera: 00:42 ou 1:02:03. */
    fun timecode(seconds: Long?): String? {
        if (seconds == null || seconds < 0) return null
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun percent(fraction: Float?): String? = fraction?.let { "${(it * 100).toInt().coerceIn(0, 100)}%" }
}
