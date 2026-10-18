package com.googletv.screensaver.playback

import android.content.Context
import android.util.Log
import com.googletv.screensaver.DreamPlan
import com.googletv.screensaver.MediaType
import com.googletv.screensaver.MediaTypeRules
import com.googletv.screensaver.PhotoLabelMode
import com.googletv.screensaver.PhotoSourceMode
import com.googletv.screensaver.PhotoSelectionMode
import com.googletv.screensaver.SlideshowPreferences

object PhotoSourceFactory {
    /**
     * [forDream] is true for the DreamService. The screensaver never plays videos: when the in-app
     * Type includes videos it shows a random pool of [MediaTypeRules.DREAM_FALLBACK_POOL] photos from
     * the whole library and ignores the saved albums and pool.
     */
    fun create(context: Context, preferences: SlideshowPreferences, forDream: Boolean = false): PhotoSource {
        val randomPhotos = forDream && MediaTypeRules.dreamPlan(preferences.mediaType()) == DreamPlan.RANDOM_PHOTOS
        if (randomPhotos && preferences.photoSourceMode() == PhotoSourceMode.SMB_GUEST) {
            Log.i("PhotoSource", "Screensaver shows ${MediaTypeRules.DREAM_FALLBACK_POOL} random photos because Type includes videos")
        }
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
                if (randomPhotos) MediaTypeRules.DREAM_FALLBACK_POOL else preferences.photoPoolSize().count,
                preferences.photoLabelMode(),
                placeNameLookup,
                context.cacheDir,
                catalogCacheDirectory = context.filesDir,
                selectedAlbumIds = if (randomPhotos) emptySet() else preferences.selectedAlbumIds(),
                selectedAlbumMode = !randomPhotos && preferences.photoSelectionMode() == PhotoSelectionMode.ALBUMS,
                mediaType = if (forDream) MediaType.PHOTOS else preferences.mediaType(),
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
