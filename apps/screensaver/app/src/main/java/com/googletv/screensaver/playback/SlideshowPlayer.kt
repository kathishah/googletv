package com.googletv.screensaver.playback

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Loads photo metadata and images off the UI thread, then hands decoded frames to the renderer. */
class SlideshowPlayer(
    private val photoSource: PhotoSource,
    intervalMillis: () -> Long,
    private val showSlide: (Slide, Bitmap) -> Unit,
    private val showLoading: () -> Unit,
    private val showEmptyState: () -> Unit,
    private val showError: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "photo-source-loader").apply { isDaemon = true }
    }
    private var slides: List<Slide> = emptyList()
    private var currentIndex = 0
    private var active = false
    private var disposed = false
    @Volatile private var generation = 0
    private var pending: Future<*>? = null
    private var schedulerStarted = false
    private var loadingImage = false
    private val scheduler = SlideshowScheduler(intervalMillis, ::showNextSlide)

    fun start() {
        if (active || disposed) return
        active = true
        schedulerStarted = false
        loadingImage = false
        val runGeneration = ++generation
        showLoading()
        pending = worker.submit {
            val result = runCatching { photoSource.loadSlides() }
            mainHandler.post {
                if (!isCurrent(runGeneration)) return@post
                result.fold(
                    onSuccess = { loadedSlides ->
                        slides = loadedSlides
                        currentIndex = 0
                        if (slides.isEmpty()) {
                            showEmptyState()
                        } else {
                            loadSlide(currentIndex, runGeneration)
                        }
                    },
                    onFailure = { error ->
                        Log.w(LOG_TAG, "Photo listing failed (${error.javaClass.simpleName})")
                        showError(if (error.hasGuestSigningFailure()) {
                            "This server requires SMB signing, which Guest cannot provide. Use a Guest-compatible server or a read-only account."
                        } else {
                            "Could not read photos. Check the SMB server, folder, and Guest access."
                        })
                    },
                )
            }
        }
    }

    fun stop() {
        if (!active) return
        active = false
        generation += 1
        pending?.cancel(true)
        pending = null
        scheduler.stop()
        loadingImage = false
        slides = emptyList()
    }

    fun dispose() {
        if (disposed) return
        stop()
        disposed = true
        worker.shutdownNow()
    }

    private fun showNextSlide() {
        if (!active || slides.isEmpty()) return
        currentIndex = (currentIndex + 1) % slides.size
        loadSlide(currentIndex, generation)
    }

    private fun loadSlide(index: Int, runGeneration: Int) {
        if (loadingImage) return
        val slide = slides[index]
        loadingImage = true
        pending = worker.submit {
            val result = runCatching { photoSource.loadImage(slide) }
            mainHandler.post {
                if (!isCurrent(runGeneration)) return@post
                loadingImage = false
                result.fold(
                    onSuccess = { bitmap -> showSlide(slide, bitmap) },
                    onFailure = { error ->
                        Log.w(LOG_TAG, "Photo load failed (${error.javaClass.simpleName})")
                        showError("Could not load this photo. It may be unavailable or unsupported.")
                    },
                )
                if (!schedulerStarted) {
                    schedulerStarted = true
                    scheduler.start()
                }
            }
        }
    }

    private fun isCurrent(runGeneration: Int): Boolean = active && generation == runGeneration

    private fun Throwable.hasGuestSigningFailure(): Boolean =
        generateSequence(this) { it.cause }.any { it is SMB2GuestSigningRequiredException }

    companion object {
        private const val LOG_TAG = "PhotoSource"
    }
}
