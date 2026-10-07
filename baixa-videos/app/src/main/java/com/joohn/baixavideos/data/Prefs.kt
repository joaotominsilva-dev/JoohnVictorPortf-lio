package com.joohn.baixavideos.data

import android.content.Context
import android.content.SharedPreferences
import com.joohn.baixavideos.engine.DownloadMode
import com.joohn.baixavideos.engine.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val mode: DownloadMode = DownloadMode.VIDEO,
    val quality: VideoQuality = VideoQuality.P1080,
    val autoUpdate: Boolean = true,
    val nightly: Boolean = false,
    val autoStartOnShare: Boolean = false,
)

object Prefs {
    private lateinit var sp: SharedPreferences
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("baixavideos", Context.MODE_PRIVATE)
        _settings.value = Settings(
            mode = enumOr(sp.getString(KEY_MODE, null), DownloadMode.VIDEO),
            quality = enumOr(sp.getString(KEY_QUALITY, null), VideoQuality.P1080),
            autoUpdate = sp.getBoolean(KEY_AUTO_UPDATE, true),
            nightly = sp.getBoolean(KEY_NIGHTLY, false),
            autoStartOnShare = sp.getBoolean(KEY_AUTO_START, false),
        )
    }

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        _settings.value = next
        sp.edit()
            .putString(KEY_MODE, next.mode.name)
            .putString(KEY_QUALITY, next.quality.name)
            .putBoolean(KEY_AUTO_UPDATE, next.autoUpdate)
            .putBoolean(KEY_NIGHTLY, next.nightly)
            .putBoolean(KEY_AUTO_START, next.autoStartOnShare)
            .apply()
    }

    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_LAST_CHECK, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_CHECK, value).apply()

    var askedNotificationPermission: Boolean
        get() = sp.getBoolean(KEY_ASKED_NOTIFICATIONS, false)
        set(value) = sp.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, value).apply()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private const val KEY_MODE = "mode"
    private const val KEY_QUALITY = "quality"
    private const val KEY_AUTO_UPDATE = "auto_update"
    private const val KEY_NIGHTLY = "nightly"
    private const val KEY_AUTO_START = "auto_start_on_share"
    private const val KEY_LAST_CHECK = "ytdlp_last_check"
    private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
}
