package com.googletv.screensaver.playback

import android.content.Context
import com.googletv.screensaver.PhotoSourceMode
import com.googletv.screensaver.SlideshowPreferences

object PhotoSourceFactory {
    fun create(context: Context, preferences: SlideshowPreferences): PhotoSource {
        val metrics = context.resources.displayMetrics
        return when (preferences.photoSourceMode()) {
            PhotoSourceMode.BUNDLED -> BundledSamplePhotoSource(
                context.resources,
                metrics.widthPixels,
                metrics.heightPixels,
            )
            PhotoSourceMode.SMB_GUEST -> SmbGuestPhotoSource(
                preferences.smbSourceConfig(),
                metrics.widthPixels,
                metrics.heightPixels,
            )
        }
    }
}
