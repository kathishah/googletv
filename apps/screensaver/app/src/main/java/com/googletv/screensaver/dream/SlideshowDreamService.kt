package com.googletv.screensaver.dream

import android.service.dreams.DreamService
import com.googletv.screensaver.LayoutMode
import com.googletv.screensaver.SlideshowPreferences
import com.googletv.screensaver.SlideshowView
import com.googletv.screensaver.playback.PairingConfig
import com.googletv.screensaver.playback.PhotoSourceFactory
import com.googletv.screensaver.playback.SlideshowPlayer

class SlideshowDreamService : DreamService() {
    private lateinit var slideshowView: SlideshowView
    private var player: SlideshowPlayer? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true

        slideshowView = SlideshowView(this)
        setContentView(slideshowView)
        val preferences = SlideshowPreferences(this)
        try {
            player = SlideshowPlayer(
                photoSource = PhotoSourceFactory.create(this, preferences, forDream = true),
                intervalMillis = preferences::intervalMillis,
                showSlide = slideshowView::show,
                showLoading = slideshowView::showLoadingState,
                showEmptyState = slideshowView::showEmptyState,
                showError = slideshowView::showErrorState,
                fallbackSource = PhotoSourceFactory.createFallback(this, preferences),
                // Layout applies to the screensaver too: its photos are photos only, so pairs always apply.
                pairing = if (preferences.layoutMode() == LayoutMode.PORTRAIT_PAIRS) {
                    PairingConfig(slideshowView::showPair, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                } else null,
            )
        } catch (error: IllegalArgumentException) {
            slideshowView.showErrorState(error.message ?: getString(com.googletv.screensaver.R.string.smb_config_error))
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        player?.start()
    }

    override fun onDreamingStopped() {
        player?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        player?.dispose()
        super.onDetachedFromWindow()
    }
}
