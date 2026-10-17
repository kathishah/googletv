package com.googletv.screensaver.playback

import android.content.Context
import com.googletv.screensaver.PhotoLabelMode
import com.googletv.screensaver.PhotoSourceMode
import com.googletv.screensaver.PhotoSelectionMode
import com.googletv.screensaver.SlideshowPreferences

object PhotoSourceFactory {
    fun create(context: Context, preferences: SlideshowPreferences): PhotoSource {
        val metrics = context.resources.displayMetrics
        val placeNameLookup = if (preferences.photoLabelMode() == PhotoLabelMode.LOCATION_AND_DATE) {
            PlaceNameResolver(context.applicationContext)::lookup
        } else {
            null
        }
        return when (preferences.photoSourceMode()) {
            PhotoSourceMode.BUNDLED -> BundledSamplePhotoSource(
                context.resources,
                metrics.widthPixels,
                metrics.heightPixels,
                preferences.photoLabelMode(),
                placeNameLookup,
            )
            PhotoSourceMode.SMB_GUEST -> SmbGuestPhotoSource(
                preferences.smbSourceConfig(),
                metrics.widthPixels,
                metrics.heightPixels,
                preferences.photoPoolSize().count,
                preferences.photoLabelMode(),
                placeNameLookup,
                context.cacheDir,
                catalogCacheDirectory = context.filesDir,
                selectedAlbumIds = preferences.selectedAlbumIds(),
                selectedAlbumMode = preferences.photoSelectionMode() == PhotoSelectionMode.ALBUMS,
            )
        }
    }

    /** Stored photos shown, with a status caption, while a network source is failing. */
    fun createFallback(context: Context, preferences: SlideshowPreferences): PhotoSource? {
        if (preferences.photoSourceMode() == PhotoSourceMode.BUNDLED) return null
        val metrics = context.resources.displayMetrics
        return BundledSamplePhotoSource(
            context.resources,
            metrics.widthPixels,
            metrics.heightPixels,
            PhotoLabelMode.NONE,
        )
    }
}
