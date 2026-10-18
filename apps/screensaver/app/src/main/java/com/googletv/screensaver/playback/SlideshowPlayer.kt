package com.googletv.screensaver.playback

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Loads photo metadata and images off the UI thread, then hands decoded frames to the renderer. */
class SlideshowPlayer(
    private val photoSource: PhotoSource,
    private val intervalMillis: () -> Long,
    private val showSlide: (Slide, Bitmap) -> Unit,
    private val showLoading: () -> Unit,
    private val showEmptyState: () -> Unit,
    private val showError: (String) -> Unit,
    /** Stored photos shown with [FALLBACK_LABEL] while [photoSource] keeps failing. Null = error text. */
    private val fallbackSource: PhotoSource? = null,
    /** Plays video slides. Only the in-app slideshow passes one; without it video slides are dropped. */
    private val videoStage: VideoStage? = null,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "photo-source-loader").apply { isDaemon = true }
    }
    // Stored photos are local, so they get their own thread and never wait behind an SMB timeout.
    private val fallbackWorker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fallback-photo-loader").apply { isDaemon = true }
    }
    private var fallbackSlides: List<Slide>? = null // fallback worker thread only
    private var fallbackCursor = 0 // fallback worker thread only
    private var fallbackActive = false
    private val fallbackTick = Runnable { showNextFallback(generation) }
    private val slides = mutableListOf<Slide>()
    private var currentIndex = 0
    private var currentSlide: Slide? = null
    private var active = false
    private var disposed = false
    @Volatile private var generation = 0
    private var pending: Future<*>? = null
    private var prepared: PreparedPhoto? = null
    private var preloadingSlide: Slide? = null
    private var advanceRequestedFor: Slide? = null
    private var schedulerStarted = false
    private var loadingImage = false
    private var imageFailureStreak = 0
    private var imageRetryNotBefore = 0L
    private var delayedAction: Runnable? = null
    private var videoPlayback: VideoPlayback? = null
    private var videoActive = false
    private var videoToken = 0
    private val scheduler = SlideshowScheduler(intervalMillis, ::showNextSlide)

    private data class PreparedPhoto(val slide: Slide, val photo: LoadedPhoto)

    fun start() {
        if (active || disposed) return
        active = true
        schedulerStarted = false
        loadingImage = false
        currentSlide = null
        clearPreparedPhoto()
        preloadingSlide = null
        advanceRequestedFor = null
        imageFailureStreak = 0
        imageRetryNotBefore = 0L
        val runGeneration = ++generation
        showLoading()
        requestListing(runGeneration, attempt = 0)
    }

    private fun requestListing(runGeneration: Int, attempt: Int) {
        pending = worker.submit {
            val result = runCatching { photoSource.loadSlides() }
            mainHandler.post {
                if (!isCurrent(runGeneration)) return@post
                result.fold(
                    onSuccess = { loadedSlides ->
                        slides.clear()
                        slides.addAll(loadedSlides)
                        currentIndex = 0
                        if (slides.isEmpty()) {
                            exitFallback()
                            showEmptyState()
                        } else {
                            loadSlide(currentIndex, runGeneration)
                        }
                    },
                    onFailure = { error -> handleListingFailure(error, runGeneration, attempt) },
                )
            }
        }
    }

    /**
     * A listing failure ends the session only when retrying cannot help. Network trouble is retried
     * with backoff for as long as the dream runs; the error text appears only if it persists.
     */
    private fun handleListingFailure(error: Throwable, runGeneration: Int, attempt: Int) {
        if (error is AlbumIndexException) {
            exitFallback()
            showError(error.message ?: "The selected album index is unavailable. Check the Mac index and saved selection.")
            return
        }
        val kind = error.smbFailureKind()
        Log.w(
            LOG_TAG,
            "Photo listing attempt ${attempt + 1} failed ($kind, ${error.safeCauseChain()}, " +
                "${error.safeSmbStatus()})",
        )
        if (kind == SmbFailureKind.CONFIGURATION) {
            exitFallback()
            showError(if (error.hasGuestSigningFailure()) {
                "This server requires SMB signing, which Guest cannot provide. Use a Guest-compatible server or a read-only account."
            } else {
                "Could not read photos. Check the SMB server, folder, and Guest access."
            })
            return
        }
        if (attempt + 1 >= ERROR_AFTER_FAILURES) showUnavailable(runGeneration)
        val delayMillis = backoffMillis(attempt)
        Log.i(LOG_TAG, "Retrying photo listing in $delayMillis ms")
        runDelayed(delayMillis) {
            if (isCurrent(runGeneration)) requestListing(runGeneration, attempt + 1)
        }
    }

    fun stop() {
        if (!active) return
        active = false
        generation += 1
        pending?.cancel(true)
        pending = null
        cancelDelayed()
        exitFallback()
        cancelVideo()
        photoSource.close()
        scheduler.stop()
        loadingImage = false
        currentSlide = null
        clearPreparedPhoto()
        preloadingSlide = null
        advanceRequestedFor = null
        slides.clear()
    }

    fun dispose() {
        if (disposed) return
        stop()
        disposed = true
        worker.shutdownNow()
        fallbackWorker.shutdownNow()
        photoSource.close()
    }

    private fun showNextSlide() {
        if (!active || slides.size < 2 || loadingImage || videoActive) return
        if (SystemClock.elapsedRealtime() < imageRetryNotBefore) return
        val nextIndex = (currentIndex + 1) % slides.size
        val nextSlide = slides[nextIndex]
        if (nextSlide.kind == SlideKind.VIDEO) {
            startVideoSlide(nextIndex, nextSlide)
            return
        }
        val ready = prepared?.takeIf { it.slide == nextSlide }
        if (ready != null) {
            prepared = null
            advanceRequestedFor = null
            presentSlide(nextIndex, nextSlide, ready.photo)
            return
        }

        advanceRequestedFor = nextSlide
        if (preloadingSlide == nextSlide) return
        loadSlide(nextIndex, generation)
    }

    private fun loadSlide(index: Int, runGeneration: Int) {
        if (!active || loadingImage || videoActive || index !in slides.indices) return
        val slide = slides[index]
        if (slide.kind == SlideKind.VIDEO) {
            startVideoSlide(index, slide)
            return
        }
        loadingImage = true
        pending = worker.submit {
            val result = runCatching { photoSource.loadImage(slide) }
            mainHandler.post {
                if (!isCurrent(runGeneration)) {
                    result.getOrNull()?.bitmap?.recycle()
                    return@post
                }
                loadingImage = false
                pending = null
                result.fold(
                    onSuccess = { photo ->
                        noteImageLoaded()
                        val actualIndex = slides.indexOf(slide)
                        if (actualIndex < 0) {
                            photo.bitmap.recycle()
                        } else {
                            advanceRequestedFor = null
                            presentSlide(actualIndex, slide, photo)
                        }
                    },
                    onFailure = { error ->
                        val wasRequested = advanceRequestedFor == slide
                        if (wasRequested) advanceRequestedFor = null
                        handleUnreadableSlide(index, slide, error, runGeneration)
                        if (wasRequested && isCurrent(runGeneration)) showNextSlide()
                    },
                )
            }
        }
    }

    private fun presentSlide(index: Int, slide: Slide, photo: LoadedPhoto) {
        exitFallback()
        currentIndex = index
        currentSlide = slide
        showSlide(slide.copy(description = photo.label.orEmpty()), photo.bitmap)
        if (!schedulerStarted) {
            schedulerStarted = true
            scheduler.start()
        } else {
            scheduler.resetInterval()
        }
        preloadNextSlide(generation)
    }

    private fun preloadNextSlide(runGeneration: Int) {
        if (!isCurrent(runGeneration) || slides.size < 2 || loadingImage ||
            preloadingSlide != null || prepared != null ||
            SystemClock.elapsedRealtime() < imageRetryNotBefore
        ) return
        val nextIndex = (currentIndex + 1) % slides.size
        val slide = slides[nextIndex]
        if (slide.kind == SlideKind.VIDEO) return // videos are prepared when their turn comes
        preloadingSlide = slide
        pending = worker.submit {
            val result = runCatching { photoSource.loadImage(slide) }
            mainHandler.post {
                if (!isCurrent(runGeneration)) {
                    result.getOrNull()?.bitmap?.recycle()
                    return@post
                }
                preloadingSlide = null
                pending = null
                val actualIndex = slides.indexOf(slide)
                if (actualIndex < 0) {
                    result.getOrNull()?.bitmap?.recycle()
                    return@post
                }
                result.fold(
                    onSuccess = { photo ->
                        noteImageLoaded()
                        if (advanceRequestedFor == slide) {
                            advanceRequestedFor = null
                            presentSlide(actualIndex, slide, photo)
                        } else {
                            prepared = PreparedPhoto(slide, photo)
                        }
                    },
                    onFailure = { error ->
                        val wasRequested = advanceRequestedFor == slide
                        if (wasRequested) advanceRequestedFor = null
                        handleUnreadableSlide(actualIndex, slide, error, runGeneration)
                        if (wasRequested && isCurrent(runGeneration)) showNextSlide()
                    },
                )
            }
        }
    }

    /**
     * Prepares a video off the main thread (the previous slide stays on screen while the reader gets
     * ahead), then hands it to the stage. The slideshow ignores its interval while the video is
     * preparing or playing; the next slide follows as soon as the clip ends.
     */
    private fun startVideoSlide(index: Int, slide: Slide) {
        val stage = videoStage
        if (stage == null) {
            // This source listed a video but nothing can show it: drop it rather than stall.
            handleVideoFailure(index, slide, permanent = true, detail = "no video stage", runGeneration = generation)
            return
        }
        if (!active || loadingImage || videoActive) return
        val runGeneration = generation
        loadingImage = true
        pending = worker.submit {
            val result = runCatching { photoSource.prepareVideo(slide) }
            mainHandler.post {
                if (!isCurrent(runGeneration)) {
                    result.getOrNull()?.close()
                    return@post
                }
                loadingImage = false
                pending = null
                result.fold(
                    onSuccess = { video -> beginVideo(slide, video, stage, runGeneration) },
                    onFailure = { error ->
                        val skipped = error as? VideoSkippedException
                        val permanent = skipped == null && error.smbFailureKind() == SmbFailureKind.PER_FILE
                        val detail = skipped?.message ?: "${error.safeCauseChain()}, ${error.safeSmbStatus()}"
                        handleVideoFailure(slides.indexOf(slide), slide, permanent, detail, runGeneration)
                    },
                )
            }
        }
    }

    private fun beginVideo(slide: Slide, video: PreparedVideo, stage: VideoStage, runGeneration: Int) {
        val index = slides.indexOf(slide)
        if (index < 0) {
            video.close()
            return
        }
        exitFallback()
        currentIndex = index
        currentSlide = slide
        videoActive = true
        val token = ++videoToken
        fun current() = isCurrent(runGeneration) && videoToken == token
        videoPlayback = stage.play(video, object : VideoEvents {
            override fun onStarted() {
                if (current()) noteImageLoaded()
            }

            override fun onDownloadComplete() {
                if (current()) preloadNextSlide(runGeneration)
            }

            override fun onFinished() {
                if (!current()) return
                videoActive = false
                videoPlayback = null
                // A pool of one clip replays it; otherwise the next slide follows at once.
                if (slides.size == 1) startVideoSlide(0, slide) else showNextSlide()
            }

            override fun onFailed(reason: VideoFailure) {
                if (!current()) return
                videoActive = false
                videoPlayback = null
                handleVideoFailure(slides.indexOf(slide), slide, reason == VideoFailure.UNPLAYABLE, "player: $reason", runGeneration)
            }
        })
    }

    /**
     * An unplayable clip leaves the pool; one that was only too slow or stalled goes to the back to be
     * tried again later. Either way the slideshow moves on at once, and three failures in a row show
     * the stored photos like a failing photo source does.
     */
    private fun handleVideoFailure(index: Int, slide: Slide, permanent: Boolean, detail: String, runGeneration: Int) {
        if (!isCurrent(runGeneration)) return
        imageFailureStreak += 1
        Log.w(LOG_TAG, "Skipping video (${if (permanent) "dropped" else "retry later"}: $detail), streak $imageFailureStreak")
        if (index >= 0) {
            slides.removeAt(index)
            if (!permanent) slides.add(slide)
            if (index < currentIndex) currentIndex--
        }
        if (slides.isEmpty()) {
            scheduler.stop()
            showError("Could not play any videos. They may be unavailable or unsupported.")
            return
        }
        if (imageFailureStreak >= ERROR_AFTER_FAILURES) showUnavailable(runGeneration)
        if (currentSlide == null) {
            currentIndex = index.coerceIn(0, slides.lastIndex)
            loadSlide(currentIndex, runGeneration)
        } else {
            mainHandler.post { if (isCurrent(runGeneration)) showNextSlide() }
        }
    }

    private fun cancelVideo() {
        videoToken += 1
        videoPlayback?.cancel()
        videoPlayback = null
        videoActive = false
    }

    private fun handleUnreadableSlide(
        index: Int,
        slide: Slide,
        error: Throwable,
        runGeneration: Int,
    ) {
        if (!isCurrent(runGeneration) || slides.getOrNull(index) != slide) return
        val kind = error.smbFailureKind()
        if (kind != SmbFailureKind.PER_FILE) {
            handleTransientImageFailure(index, slide, kind, error, runGeneration)
            return
        }
        Log.w(LOG_TAG, "Skipping unreadable photo (${error.safeCauseChain()}, ${error.safeSmbStatus()})")
        if (prepared?.slide == slide) clearPreparedPhoto()
        slides.removeAt(index)
        if (slides.isEmpty()) {
            scheduler.stop()
            showError("Could not load any photos. They may be unavailable or unsupported.")
            return
        }

        if (index < currentIndex) currentIndex--
        if (currentSlide == null) {
            currentIndex = index.coerceAtMost(slides.lastIndex)
            loadSlide(currentIndex, runGeneration)
        } else {
            preloadNextSlide(runGeneration)
        }
    }

    /**
     * A connection or timeout failure says nothing about the photo, so it stays in the pool. It moves
     * to the back so one stubborn photo cannot block the rest, and the next attempt waits for a
     * backoff while the current photo stays on screen. A configuration-type error here (Guest
     * rejected mid-session) is handled the same way rather than emptying the pool.
     */
    private fun handleTransientImageFailure(
        index: Int,
        slide: Slide,
        kind: SmbFailureKind,
        error: Throwable,
        runGeneration: Int,
    ) {
        imageFailureStreak += 1
        val delayMillis = backoffMillis(imageFailureStreak - 1)
        imageRetryNotBefore = SystemClock.elapsedRealtime() + delayMillis
        Log.w(
            LOG_TAG,
            "Photo load failed, keeping it ($kind, streak $imageFailureStreak, " +
                "${error.safeCauseChain()}, ${error.safeSmbStatus()}); next attempt in $delayMillis ms",
        )
        if (prepared?.slide == slide) clearPreparedPhoto()
        slides.removeAt(index)
        slides.add(slide)
        if (index < currentIndex) currentIndex--
        val nothingShownYet = currentSlide == null
        if (nothingShownYet) currentIndex = index.coerceAtMost(slides.lastIndex)
        if (imageFailureStreak >= ERROR_AFTER_FAILURES) showUnavailable(runGeneration)
        runDelayed(delayMillis) {
            if (!isCurrent(runGeneration)) return@runDelayed
            if (nothingShownYet && currentSlide == null) {
                loadSlide(currentIndex, runGeneration)
            } else {
                preloadNextSlide(runGeneration)
            }
        }
    }

    /** Persistent trouble: show stored photos (or, without any, an error) while retries continue. */
    private fun showUnavailable(runGeneration: Int) {
        if (fallbackSource == null) {
            if (currentSlide == null) showError(UNAVAILABLE_MESSAGE)
            return
        }
        if (fallbackActive) return
        fallbackActive = true
        Log.i(LOG_TAG, "Showing stored photos until the photo source recovers")
        showNextFallback(runGeneration)
    }

    private fun showNextFallback(runGeneration: Int) {
        val source = fallbackSource ?: return
        if (!fallbackActive || !isCurrent(runGeneration)) return
        fallbackWorker.submit {
            val result = runCatching {
                val all = fallbackSlides ?: source.loadSlides().also { fallbackSlides = it }
                val slide = all[fallbackCursor++ % all.size]
                slide to source.loadImage(slide)
            }
            mainHandler.post {
                if (!fallbackActive || !isCurrent(runGeneration)) {
                    result.getOrNull()?.second?.bitmap?.recycle()
                    return@post
                }
                result.fold(
                    onSuccess = { (slide, photo) ->
                        showSlide(slide.copy(description = FALLBACK_LABEL), photo.bitmap)
                    },
                    onFailure = { error ->
                        Log.w(LOG_TAG, "Stored photo failed (${error.safeCauseChain()})")
                        if (currentSlide == null) showError(UNAVAILABLE_MESSAGE)
                    },
                )
                mainHandler.postDelayed(fallbackTick, intervalMillis())
            }
        }
    }

    private fun exitFallback() {
        if (!fallbackActive) return
        fallbackActive = false
        Log.i(LOG_TAG, "Stored photos hidden")
        mainHandler.removeCallbacks(fallbackTick)
    }

    private fun noteImageLoaded() {
        imageFailureStreak = 0
        imageRetryNotBefore = 0L
    }

    private fun runDelayed(delayMillis: Long, action: () -> Unit) {
        cancelDelayed()
        val runnable = Runnable {
            delayedAction = null
            action()
        }
        delayedAction = runnable
        mainHandler.postDelayed(runnable, delayMillis)
    }

    private fun cancelDelayed() {
        delayedAction?.let(mainHandler::removeCallbacks)
        delayedAction = null
    }

    private fun backoffMillis(failures: Int): Long =
        (INITIAL_BACKOFF_MILLIS shl failures.coerceIn(0, 4)).coerceAtMost(MAX_BACKOFF_MILLIS)

    private fun clearPreparedPhoto() {
        prepared?.photo?.bitmap?.recycle()
        prepared = null
    }

    private fun isCurrent(runGeneration: Int): Boolean = active && generation == runGeneration

    private fun Throwable.hasGuestSigningFailure(): Boolean =
        generateSequence(this) { it.cause }.any { it is SMB2GuestSigningRequiredException }

    companion object {
        private const val LOG_TAG = "PhotoSource"
        private const val INITIAL_BACKOFF_MILLIS = 2_000L
        private const val MAX_BACKOFF_MILLIS = 15_000L
        private const val ERROR_AFTER_FAILURES = 3
        private const val UNAVAILABLE_MESSAGE = "Can't reach the photos right now. Still trying…"
        private const val FALLBACK_LABEL = "SMB failure. Loading…"
    }
}
