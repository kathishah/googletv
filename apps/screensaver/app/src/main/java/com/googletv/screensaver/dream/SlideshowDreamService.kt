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
    private lateinit var player: SlideshowPlayer

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true

        slideshowView = SlideshowView(this)
        setContentView(slideshowView)
        player = SlideshowPlayer(
            photoSource = PhotoSourceFactory.create(this, SlideshowPreferences(this), forDream = true),
            intervalMillis = SlideshowPreferences(this)::intervalMillis,
            showSlide = slideshowView::show,
            showLoading = slideshowView::showLoadingState,
            showEmptyState = slideshowView::showEmptyState,
            showError = slideshowView::showErrorState,
            fallbackSource = PhotoSourceFactory.createFallback(this, SlideshowPreferences(this)),
            // Layout applies to the screensaver too: its photos are photos only, so pairs always apply.
            pairing = if (SlideshowPreferences(this).layoutMode() == LayoutMode.PORTRAIT_PAIRS) {
                PairingConfig(slideshowView::showPair, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            } else null,
        )
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        player.start()
    }

    override fun onDreamingStopped() {
        player.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        if (::player.isInitialized) player.dispose()
        super.onDetachedFromWindow()
    }
}
