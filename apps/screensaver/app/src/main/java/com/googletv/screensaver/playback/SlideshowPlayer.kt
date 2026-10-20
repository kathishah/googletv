package com.googletv.screensaver.playback

import android.graphics.Bitmap
import android.os.SystemClock
import com.googletv.screensaver.PairScene
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

private fun newPlayerExecutor(threadName: String): ExecutorService =
    Executors.newSingleThreadExecutor { runnable -> Thread(runnable, threadName).apply { isDaemon = true } }

private inline fun <T> captureWorkerFailure(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: Exception) {
    if (error is InterruptedException) {
        Thread.currentThread().interrupt()
        throw error
    }
    Result.failure(error)
}

/**
 * Turns on Portrait pairs: [showPair] draws two photos and their background, and [screenWidth] by
 * [screenHeight] is the size the pair is laid out for (the display, as for decoding). Without it the player
 * never pairs, and every code path below is the single-photo one.
 */
class PairingConfig(
    val showPair: (Slide, Bitmap, Slide, Bitmap, PairScene) -> Unit,
    val screenWidth: Int,
    val screenHeight: Int,
)

/** Loads photo metadata and images off the UI thread, then hands decoded frames to the renderer. */
class SlideshowPlayer internal constructor(
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
    /** Pairs portrait photos on one slide (Layout = Portrait pairs). Null = Layout Single. */
    private val pairing: PairingConfig? = null,
    private val callbacks: CallbackDispatcher = HandlerCallbackDispatcher(),
    private val worker: ExecutorService = newPlayerExecutor("photo-source-loader"),
    private val fallbackWorker: ExecutorService = newPlayerExecutor("fallback-photo-loader"),
    private val clockMillis: () -> Long = SystemClock::elapsedRealtime,
    private val logger: PlayerLogger = AndroidPlayerLogger,
) {
    // Stored photos are local, so they get their own thread and never wait behind an SMB timeout.
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
    private var prepared: PreparedSlide? = null
    private var lastShownAt = 0L
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
    private val scheduler = SlideshowScheduler(intervalMillis, ::showNextSlide, callbacks)

    private class PreparedSlide(val slide: Slide, val content: LoadedContent)

    /**
     * What was decoded for a slide: one photo, or a pair with the scene it is laid out in. When one photo of
     * a pair failed, [photo] is the one that loaded ([survivorIsFirst] says which) and [failure] is the other's error.
     */
    private class LoadedContent(
        val photo: LoadedPhoto,
        val partner: LoadedPhoto? = null,
        val scene: PairScene? = null,
        val survivorIsFirst: Boolean = true,
        val failure: Throwable? = null,
    ) {
        val isPair: Boolean get() = partner != null && scene != null

        fun recycle() {
            photo.bitmap.recycle()
            partner?.bitmap?.recycle()
            scene?.recycle()
        }
    }

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
        lastShownAt = 0L
        val runGeneration = ++generation
        showLoading()
        requestListing(runGeneration, attempt = 0)
    }

    private fun requestListing(runGeneration: Int, attempt: Int) {
        pending = worker.submit {
            val result = captureWorkerFailure {
                ensureCurrent(runGeneration)
                photoSource.loadSlides()
            }
            callbacks.post {
                if (!isCurrent(runGeneration)) return@post
                result.fold(
                    onSuccess = { loadedSlides ->
                        slides.clear()
                        slides.addAll(planPairs(loadedSlides))
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

    /** With pairing on, some pairable photos are joined into pair slides; otherwise the list is returned as it is. */
    private fun planPairs(loaded: List<Slide>): List<Slide> {
        if (pairing == null) return loaded
        val planned = PairPlanner.plan(loaded, photoSource::isPairable)
        val pairs = planned.count { it.partner != null }
        logger.info("Layout Portrait pairs: ${planned.size} slides from ${loaded.size} (${pairs} pairs, ${loaded.count { photoSource.isPairable(it) }} portrait photos)")
        return planned
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
        logger.warning("Photo listing attempt ${attempt + 1} failed ($kind, ${error.safeCauseChain()}, " +
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
        logger.info("Retrying photo listing in $delayMillis ms")
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
        if (clockMillis() < imageRetryNotBefore) return
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
            presentSlide(nextIndex, nextSlide, ready.content)
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
            val result = captureWorkerFailure { loadContent(slide, runGeneration) }
            callbacks.post {
                if (!isCurrent(runGeneration)) {
                    result.getOrNull()?.recycle()
                    return@post
                }
                loadingImage = false
                pending = null
                result.fold(
                    onSuccess = { content ->
                        noteLoaded(content)
                        val actualIndex = slides.indexOf(slide)
                        if (actualIndex < 0) {
                            content.recycle()
                        } else {
                            advanceRequestedFor = null
                            presentSlide(actualIndex, slide, content)
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

    private fun presentSlide(index: Int, slide: Slide, content: LoadedContent) {
        exitFallback()
        var shown = slide
        content.failure?.let { failure ->
            // One photo of the pair failed: the other is shown alone in the pair's place, and the failed
            // one is dropped (it can never load) or sent to the back (the network said no, not the photo).
            val kind = failure.smbFailureKind()
            shown = PairFailureRules.repair(slides, index, content.survivorIsFirst, kind)
            // Like a single photo, only a network-type failure counts toward the three that bring up the stored artworks.
            if (kind != SmbFailureKind.PER_FILE) imageFailureStreak += 1
            logger.warning("One photo of a pair failed (${if (kind == SmbFailureKind.PER_FILE) "dropped" else "kept, sent to the back"}: " +
                    "$kind, ${failure.safeCauseChain()}, ${failure.safeSmbStatus()}), showing the other alone, streak $imageFailureStreak",
            )
        }
        currentIndex = index
        currentSlide = shown
        val now = clockMillis()
        val partner = shown.partner
        if (content.isPair && partner != null) {
            pairing?.showPair?.invoke(
                shown.copy(description = content.photo.label.orEmpty()), content.photo.bitmap,
                partner.copy(description = content.partner?.label.orEmpty()), content.partner!!.bitmap,
                content.scene!!,
            )
        } else {
            showSlide(shown.copy(description = content.photo.label.orEmpty()), content.photo.bitmap)
        }
        if (pairing != null) {
            logger.info("Slide shown: ${if (content.isPair) "pair" else "single"}, ${if (lastShownAt == 0L) 0 else now - lastShownAt} ms after the previous")
        }
        lastShownAt = now
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
            clockMillis() < imageRetryNotBefore
        ) return
        val nextIndex = (currentIndex + 1) % slides.size
        val slide = slides[nextIndex]
        if (slide.kind == SlideKind.VIDEO) return // videos are prepared when their turn comes
        preloadingSlide = slide
        pending = worker.submit {
            val result = runCatching { loadContent(slide, runGeneration) }
            callbacks.post {
                if (!isCurrent(runGeneration)) {
                    result.getOrNull()?.recycle()
                    return@post
                }
                preloadingSlide = null
                pending = null
                val actualIndex = slides.indexOf(slide)
                if (actualIndex < 0) {
                    result.getOrNull()?.recycle()
                    return@post
                }
                result.fold(
                    onSuccess = { content ->
                        noteLoaded(content)
                        if (advanceRequestedFor == slide) {
                            advanceRequestedFor = null
                            presentSlide(actualIndex, slide, content)
                        } else {
                            prepared = PreparedSlide(slide, content)
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
     * Decodes what a slide shows, on the worker thread: one photo, or both photos of a pair and the scene
     * they are laid out in. The worker decodes one photo at a time, so a pair takes two decodes; the
     * previous slide stays up until the whole pair is ready.
     */
    private fun loadContent(slide: Slide, runGeneration: Int): LoadedContent {
        ensureCurrent(runGeneration)
        val partnerSlide = slide.partner
        val config = pairing
        if (partnerSlide == null || config == null) {
            ensureCurrent(runGeneration)
            return LoadedContent(photoSource.loadImage(slide))
        }
        val startedAt = clockMillis()
        ensureCurrent(runGeneration)
        val first = captureWorkerFailure { photoSource.loadImage(slide) }
        val firstDoneAt = clockMillis()
        if (!isCurrent(runGeneration)) {
            first.getOrNull()?.bitmap?.recycle()
            throw InterruptedException("Slideshow stopped before pair decode completed.")
        }
        ensureCurrent(runGeneration)
        val second = captureWorkerFailure { photoSource.loadImage(partnerSlide) }
        val decodedAt = clockMillis()
        return when (val outcome = PairFailureRules.classify(first.exceptionOrNull(), second.exceptionOrNull())) {
            is PairFailureRules.Outcome.NoneLoaded -> throw outcome.error
            is PairFailureRules.Outcome.OneLoaded -> {
                val survivor = (if (outcome.survivorIsFirst) first else second).getOrThrow()
                LoadedContent(survivor, survivorIsFirst = outcome.survivorIsFirst, failure = outcome.failure)
            }
            PairFailureRules.Outcome.BothLoaded -> {
                val a = first.getOrThrow()
                val b = second.getOrThrow()
                val scene = PairScene.build(a.bitmap, b.bitmap, slide.look, config.screenWidth, config.screenHeight)
                if (scene == null) {
                    b.bitmap.recycle()
                    return LoadedContent(a, survivorIsFirst = true, failure = IllegalStateException("No pair layout"))
                }
                logger.info("Pair loaded: decode ${firstDoneAt - startedAt} + ${decodedAt - firstDoneAt} ms, " +
                        "background ${clockMillis() - decodedAt} ms, style ${slide.look?.style ?: "black"}",
                )
                LoadedContent(a, b, scene)
            }
        }
    }

    /** A fully loaded slide ends the failure streak; a pair that lost a photo does not (it is counted when shown). */
    private fun noteLoaded(content: LoadedContent) {
        if (content.failure == null) noteImageLoaded()
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
            val result = captureWorkerFailure {
                ensureCurrent(runGeneration)
                photoSource.prepareVideo(slide)
            }
            callbacks.post {
                if (!isCurrent(runGeneration)) {
                    result.getOrNull()?.closeAsync()
                    return@post
                }
                loadingImage = false
                pending = null
                result.fold(
                    onSuccess = { video -> beginVideo(slide, video, stage, runGeneration) },
                    onFailure = { error ->
                        val skipped = error as? VideoSkippedException
                        // Low storage says nothing about the clip, so it stays in the pool for a later try.
                        val permanent = skipped == null && error !is InsufficientStorageException &&
                            error.smbFailureKind() == SmbFailureKind.PER_FILE
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
            video.closeAsync()
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
                // The first frame proves only that playback began. A later stall must still count.
                if (current()) logger.info("Video started")
            }

            override fun onDownloadComplete() {
                if (current()) preloadNextSlide(runGeneration)
            }

            override fun onFinished() {
                if (!current()) return
                videoActive = false
                videoPlayback = null
                noteImageLoaded()
                // A pool of one clip replays it; otherwise the next slide follows at once.
                if (slides.size == 1) startVideoSlide(0, slide) else showNextSlide()
            }

            override fun onFailed(reason: VideoFailure, cause: Throwable?) {
                if (!current()) return
                videoActive = false
                videoPlayback = null
                val detail = cause?.let { "${it.safeCauseChain()}, ${it.safeSmbStatus()}" } ?: "player: $reason"
                handleVideoFailure(slides.indexOf(slide), slide, reason == VideoFailure.UNPLAYABLE, detail, runGeneration)
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
        if (index < 0) return
        val failedWasCurrent = currentSlide == slide
        imageFailureStreak += 1
        logger.warning("Skipping video (${if (permanent) "dropped" else "retry later"}: $detail), streak $imageFailureStreak")
        slides.removeAt(index)
        if (!permanent) slides.add(slide)
        if (index < currentIndex) currentIndex--
        if (slides.isEmpty()) {
            scheduler.stop()
            showError("Could not play any videos. They may be unavailable or unsupported.")
            return
        }
        if (imageFailureStreak >= ERROR_AFTER_FAILURES) showUnavailable(runGeneration)

        // The successor occupies the failed slot unless it was the last item (or a transient failure
        // was moved to the end). Keep a decoded photo only when it is that successor.
        val successorIndex = if (index < slides.size && (permanent || index < slides.lastIndex)) index else 0
        val successor = slides[successorIndex]
        if (prepared != null && prepared?.slide != successor) clearPreparedPhoto()

        if (failedWasCurrent) {
            if (slides.size == 1) {
                currentIndex = 0
                if (successor == slide) {
                    retrySoleVideo(slide, runGeneration)
                } else {
                    showOnlySlide(successor, runGeneration)
                }
            } else {
                currentIndex = (successorIndex - 1 + slides.size) % slides.size
                callbacks.post { if (isCurrent(runGeneration)) showNextSlide() }
            }
            return
        }
        if (currentSlide == null) {
            currentIndex = successorIndex
            if (slides.size == 1 && successor == slide) retrySoleVideo(slide, runGeneration)
            else loadSlide(currentIndex, runGeneration)
        } else if (slides.size >= 2) {
            callbacks.post { if (isCurrent(runGeneration)) showNextSlide() }
        }
    }

    private fun showOnlySlide(slide: Slide, runGeneration: Int) {
        val ready = prepared?.takeIf { it.slide == slide }
        if (ready != null) {
            prepared = null
            presentSlide(0, slide, ready.content)
        } else {
            if (prepared != null) clearPreparedPhoto()
            loadSlide(0, runGeneration)
        }
    }

    private fun retrySoleVideo(slide: Slide, runGeneration: Int) {
        currentIndex = 0
        runDelayed(backoffMillis(imageFailureStreak - 1)) {
            if (isCurrent(runGeneration) && slides.singleOrNull() == slide) startVideoSlide(0, slide)
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
        logger.warning("Skipping unreadable photo (${error.safeCauseChain()}, ${error.safeSmbStatus()})")
        if (prepared?.slide == slide) clearPreparedPhoto()
        slides.removeAt(index)
        if (slides.isEmpty()) {
            scheduler.stop()
            runDelayed(backoffMillis(0)) {
                if (isCurrent(runGeneration)) requestListing(runGeneration, attempt = 0)
            }
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
        imageRetryNotBefore = clockMillis() + delayMillis
        logger.warning("Photo load failed, keeping it ($kind, streak $imageFailureStreak, " +
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
        logger.info("Showing stored photos until the photo source recovers")
        showNextFallback(runGeneration)
    }

    private fun showNextFallback(runGeneration: Int) {
        val source = fallbackSource ?: return
        if (!fallbackActive || !isCurrent(runGeneration)) return
        fallbackWorker.submit {
            val result = captureWorkerFailure {
                ensureCurrent(runGeneration)
                val all = fallbackSlides ?: source.loadSlides().also { fallbackSlides = it }
                val slide = all[fallbackCursor++ % all.size]
                ensureCurrent(runGeneration)
                slide to source.loadImage(slide)
            }
            callbacks.post {
                if (!fallbackActive || !isCurrent(runGeneration)) {
                    result.getOrNull()?.second?.bitmap?.recycle()
                    return@post
                }
                result.fold(
                    onSuccess = { (slide, photo) ->
                        showSlide(slide.copy(description = FALLBACK_LABEL), photo.bitmap)
                    },
                    onFailure = { error ->
                        logger.warning("Stored photo failed (${error.safeCauseChain()})")
                        if (currentSlide == null) showError(UNAVAILABLE_MESSAGE)
                    },
                )
                callbacks.postDelayed(fallbackTick, intervalMillis())
            }
        }
    }

    private fun exitFallback() {
        if (!fallbackActive) return
        fallbackActive = false
        logger.info("Stored photos hidden")
        callbacks.removeCallbacks(fallbackTick)
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
        callbacks.postDelayed(runnable, delayMillis)
    }

    private fun cancelDelayed() {
        delayedAction?.let(callbacks::removeCallbacks)
        delayedAction = null
    }

    private fun backoffMillis(failures: Int): Long =
        (INITIAL_BACKOFF_MILLIS shl failures.coerceIn(0, 4)).coerceAtMost(MAX_BACKOFF_MILLIS)

    private fun clearPreparedPhoto() {
        prepared?.content?.recycle()
        prepared = null
    }

    private fun isCurrent(runGeneration: Int): Boolean = active && generation == runGeneration

    private fun ensureCurrent(runGeneration: Int) {
        if (!isCurrent(runGeneration)) throw InterruptedException("Slideshow operation is no longer current.")
    }

    private fun Throwable.hasGuestSigningFailure(): Boolean =
        generateSequence(this) { it.cause }.any { it is SMB2GuestSigningRequiredException }

    companion object {
        private const val INITIAL_BACKOFF_MILLIS = 2_000L
        private const val MAX_BACKOFF_MILLIS = 15_000L
        private const val ERROR_AFTER_FAILURES = 3
        private const val UNAVAILABLE_MESSAGE = "Can't reach the photos right now. Still trying…"
        private const val FALLBACK_LABEL = "SMB failure. Loading…"
    }
}
