package com.googletv.screensaver.dream

import android.service.dreams.DreamService
import com.googletv.screensaver.SlideshowPreferences
import com.googletv.screensaver.SlideshowView
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
            photoSource = PhotoSourceFactory.create(this, SlideshowPreferences(this)),
            intervalMillis = SlideshowPreferences(this)::intervalMillis,
            showSlide = slideshowView::show,
            showLoading = slideshowView::showLoadingState,
            showEmptyState = slideshowView::showEmptyState,
            showError = slideshowView::showErrorState,
            fallbackSource = PhotoSourceFactory.createFallback(this, SlideshowPreferences(this)),
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
