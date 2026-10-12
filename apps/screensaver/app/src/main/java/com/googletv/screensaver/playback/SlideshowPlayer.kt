package com.googletv.screensaver.playback

/** Coordinates a source and scheduler, leaving the UI responsible only for displaying a slide. */
class SlideshowPlayer(
    private val photoSource: PhotoSource,
    intervalMillis: () -> Long,
    private val showSlide: (Slide) -> Unit,
    private val showEmptyState: () -> Unit,
) {
    private var slides: List<Slide> = emptyList()
    private var currentIndex = 0
    private val scheduler = SlideshowScheduler(intervalMillis, ::showNextSlide)

    fun start() {
        slides = photoSource.loadSlides()
        currentIndex = 0
        if (slides.isEmpty()) {
            showEmptyState()
            return
        }
        showSlide(slides[currentIndex])
        scheduler.start()
    }

    fun stop() {
        scheduler.stop()
    }

    private fun showNextSlide() {
        if (slides.isEmpty()) return
        currentIndex = (currentIndex + 1) % slides.size
        showSlide(slides[currentIndex])
    }
}
