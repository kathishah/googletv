package com.googletv.screensaver

import android.content.Context
import com.googletv.screensaver.playback.SmbSourceConfig

enum class PhotoSourceMode { BUNDLED, SMB_GUEST }
enum class PhotoSelectionMode { ALL, ALBUMS }

enum class PhotoPoolSize(val count: Int, val label: String) {
    PHOTOS_100(100, "100"),
    PHOTOS_250(250, "250"),
    PHOTOS_500(500, "500"),
    PHOTOS_1000(1_000, "1,000");

    companion object {
        val all = entries
        val default = PHOTOS_250
    }
}

enum class PhotoLabelMode(val label: String) {
    LOCATION_AND_DATE("Location + date"),
    DATE_ONLY("Date only"),
    NONE("No label");

    companion object {
        val all = entries
        val default = LOCATION_AND_DATE
    }
}

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

    fun photoSelectionMode(): PhotoSelectionMode =
        preferences.getString(KEY_PHOTO_SELECTION_MODE, null)?.let { value ->
            runCatching { PhotoSelectionMode.valueOf(value) }.getOrNull()
        } ?: if (selectedAlbumIds().isNotEmpty()) PhotoSelectionMode.ALBUMS else PhotoSelectionMode.ALL

    fun setPhotoSelectionMode(mode: PhotoSelectionMode) {
        preferences.edit().putString(KEY_PHOTO_SELECTION_MODE, mode.name).apply()
    }

    fun photoPoolSize(): PhotoPoolSize {
        val count = preferences.getInt(KEY_PHOTO_POOL_SIZE, PhotoPoolSize.default.count)
        return PhotoPoolSize.all.firstOrNull { it.count == count } ?: PhotoPoolSize.default
    }

    fun setPhotoPoolSize(size: PhotoPoolSize) {
        preferences.edit().putInt(KEY_PHOTO_POOL_SIZE, size.count).apply()
    }

    fun photoLabelMode(): PhotoLabelMode =
        runCatching { PhotoLabelMode.valueOf(preferences.getString(KEY_PHOTO_LABEL_MODE, null) ?: "") }
            .getOrDefault(PhotoLabelMode.default)

    fun setPhotoLabelMode(mode: PhotoLabelMode) {
        preferences.edit().putString(KEY_PHOTO_LABEL_MODE, mode.name).apply()
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

    fun selectedAlbumIds(): Set<String> = preferences.getStringSet(KEY_ALBUM_IDS, emptySet()).orEmpty().toSet()

    fun setSelectedAlbumIds(ids: Set<String>) {
        preferences.edit().putStringSet(KEY_ALBUM_IDS, ids.toSet()).apply()
    }

    fun selectedAlbumPhotoCount(): Int = preferences.getInt(KEY_ALBUM_PHOTO_COUNT, 0)

    fun setAlbumSelection(ids: Set<String>, photoCount: Int) {
        preferences.edit().putStringSet(KEY_ALBUM_IDS, ids.toSet())
            .putInt(KEY_ALBUM_PHOTO_COUNT, photoCount.coerceAtLeast(0)).apply()
    }

    companion object {
        private const val KEY_INTERVAL_SECONDS = "interval_seconds"
        private const val KEY_SOURCE_MODE = "photo_source_mode"
        private const val KEY_PHOTO_SELECTION_MODE = "photo_selection_mode"
        private const val KEY_PHOTO_POOL_SIZE = "photo_pool_size"
        private const val KEY_PHOTO_LABEL_MODE = "photo_label_mode"
        private const val KEY_SMB_HOST = "smb_host"
        private const val KEY_SMB_SHARE = "smb_share"
        private const val KEY_SMB_PATH = "smb_path"
        private const val KEY_ALBUM_IDS = "smb_album_ids"
        private const val KEY_ALBUM_PHOTO_COUNT = "smb_album_photo_count"
        const val DEFAULT_SMB_SHARE = "Photos"
        const val DEFAULT_SMB_PATH = "Photos Library.photoslibrary/originals"
    }
}
