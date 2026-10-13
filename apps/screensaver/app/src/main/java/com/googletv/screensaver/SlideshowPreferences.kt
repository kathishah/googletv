package com.googletv.screensaver

import android.content.Context
import com.googletv.screensaver.playback.SmbSourceConfig

enum class PhotoSourceMode { BUNDLED, SMB_GUEST }

data class IntervalPreset(val seconds: Int, val label: String) {
    val millis: Long get() = seconds * 1_000L

    companion object {
        val all = listOf(
            IntervalPreset(10, "10 sec"),
            IntervalPreset(30, "30 sec"),
            IntervalPreset(60, "1 min"),
            IntervalPreset(300, "5 min"),
            IntervalPreset(900, "15 min"),
        )
        val default = all[1]
    }
}

class SlideshowPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("slideshow", Context.MODE_PRIVATE)

    fun intervalSeconds(): Int = preferences.getInt(KEY_INTERVAL_SECONDS, IntervalPreset.default.seconds)

    fun intervalMillis(): Long = intervalSeconds() * 1_000L

    fun setInterval(preset: IntervalPreset) {
        preferences.edit().putInt(KEY_INTERVAL_SECONDS, preset.seconds).apply()
    }

    fun photoSourceMode(): PhotoSourceMode =
        runCatching { PhotoSourceMode.valueOf(preferences.getString(KEY_SOURCE_MODE, null) ?: "") }
            .getOrDefault(PhotoSourceMode.BUNDLED)

    fun setPhotoSourceMode(mode: PhotoSourceMode) {
        preferences.edit().putString(KEY_SOURCE_MODE, mode.name).apply()
    }

    fun smbSourceConfig(): SmbSourceConfig = SmbSourceConfig(
        host = preferences.getString(KEY_SMB_HOST, "").orEmpty(),
        share = preferences.getString(KEY_SMB_SHARE, DEFAULT_SMB_SHARE).orEmpty(),
        rootPath = preferences.getString(KEY_SMB_PATH, DEFAULT_SMB_PATH).orEmpty(),
    )

    fun saveSmbSourceConfig(config: SmbSourceConfig) {
        preferences.edit()
            .putString(KEY_SMB_HOST, config.host.trim())
            .putString(KEY_SMB_SHARE, config.share.trim())
            .putString(KEY_SMB_PATH, config.rootPath.trim())
            .apply()
    }

    companion object {
        private const val KEY_INTERVAL_SECONDS = "interval_seconds"
        private const val KEY_SOURCE_MODE = "photo_source_mode"
        private const val KEY_SMB_HOST = "smb_host"
        private const val KEY_SMB_SHARE = "smb_share"
        private const val KEY_SMB_PATH = "smb_path"
        const val DEFAULT_SMB_SHARE = "Photos"
        const val DEFAULT_SMB_PATH = "Photos Library.photoslibrary/originals"
    }
}
