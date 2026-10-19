package com.googletv.screensaver.playback

import android.graphics.Bitmap
import java.io.File
import java.nio.file.Files
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testBitmap(): Bitmap {
    val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
    return (field.get(null) as sun.misc.Unsafe).allocateInstance(Bitmap::class.java) as Bitmap
}

class SlideshowPlayerTest {
    @Test
    fun listingFailureTransitionsCanRunWithJvmCallbacksAndExecutor() {
        val callbacks = QueuedCallbacks()
        val worker = DirectExecutorService()
        val fallbackWorker = DirectExecutorService()
        val errors = mutableListOf<String>()
        var loadingShown = 0
        val player = SlideshowPlayer(
            photoSource = object : PhotoSource {
                override fun loadSlides(): List<Slide> = throw AlbumIndexException("catalog unavailable")
                override fun loadImage(slide: Slide): LoadedPhoto = error("No image should load")
            },
            intervalMillis = { 1_000L },
            showSlide = { _, _ -> error("No slide should be shown") },
            showLoading = { loadingShown++ },
            showEmptyState = { error("No empty state should be shown") },
            showError = errors::add,
            callbacks = callbacks,
            worker = worker,
            fallbackWorker = fallbackWorker,
            clockMillis = { 0L },
            logger = QuietPlayerLogger,
        )

        player.start()
        assertEquals(1, loadingShown)
        assertTrue(errors.isEmpty())
        callbacks.runPosted()
        assertEquals(listOf("catalog unavailable"), errors)
        player.dispose()
        assertTrue(worker.isShutdown)
        assertTrue(fallbackWorker.isShutdown)
    }

    @Test
    fun failedCurrentVideosAdvanceFromTheirVacatedSlotAtEveryPosition() {
        val cases = listOf(
            Triple(listOf("A", "B", "C"), "A", "B"),
            Triple(listOf("A", "B", "C"), "B", "C"),
            Triple(listOf("A", "B", "C"), "C", "A"),
        )
        for (reason in VideoFailure.entries) {
            for ((ids, failed, expectedNext) in cases) {
                val fixture = videoFixture(ids)
                fixture.player.start()
                fixture.callbacks.runPosted()
                while (fixture.stage.played.last() != failed) {
                    fixture.stage.finishCurrent()
                    fixture.callbacks.runPosted()
                }

                fixture.stage.failCurrent(reason)
                fixture.callbacks.runPosted()

                assertEquals("$reason failure at $failed", expectedNext, fixture.stage.played.last())
                fixture.player.dispose()
            }
        }
    }

    @Test
    fun permanentFailureShowsTheOnlyRemainingPhoto() {
        val callbacks = QueuedCallbacks()
        val source = FixturePhotoSource(listOf(video("A"), photo("P")))
        val stage = FixtureVideoStage()
        val shown = mutableListOf<String>()
        val player = fixturePlayer(source, callbacks, stage, shown)
        player.start()
        callbacks.runPosted()

        stage.failCurrent(VideoFailure.UNPLAYABLE)
        callbacks.runPosted()

        assertEquals(listOf("P"), shown)
        player.dispose()
    }

    @Test
    fun aStalledLoneVideoIsRetriedAfterBackoff() {
        val fixture = videoFixture(listOf("A"))
        fixture.player.start()
        fixture.callbacks.runPosted()
        assertEquals(listOf("A"), fixture.stage.played)

        fixture.stage.failCurrent(VideoFailure.STALLED)
        fixture.callbacks.runPosted()
        assertEquals(listOf("A"), fixture.stage.played)

        fixture.callbacks.runNextDelayed()
        fixture.callbacks.runPosted()
        assertEquals(listOf("A", "A"), fixture.stage.played)
        fixture.player.dispose()
    }

    @Test
    fun aPreparedPhotoAtTheFailedVideoSlotIsShownBeforeLaterSlides() {
        val callbacks = QueuedCallbacks()
        val source = FixturePhotoSource(listOf(video("A"), photo("P"), video("B")))
        val stage = FixtureVideoStage()
        val shown = mutableListOf<String>()
        val player = fixturePlayer(source, callbacks, stage, shown)
        player.start()
        callbacks.runPosted()
        stage.finishDownload()
        callbacks.runPosted()
        assertEquals(listOf("P"), source.loadedPhotos)

        stage.failCurrent(VideoFailure.UNPLAYABLE)
        callbacks.runPosted()

        assertEquals(listOf("P"), shown)
        assertEquals(listOf("A"), stage.played)
        player.dispose()
    }

    @Test
    fun repeatedStartThenStallFailuresReachStoredPhotoFallback() {
        val fallback = FixturePhotoSource(listOf(photo("fallback")))
        val shown = mutableListOf<String>()
        val callbacks = QueuedCallbacks()
        val stage = FixtureVideoStage()
        val player = fixturePlayer(FixturePhotoSource(listOf(video("A"), video("B"), video("C"))), callbacks,
            stage, shown, fallback)
        player.start()
        callbacks.runPosted()

        repeat(3) {
            stage.failCurrent(VideoFailure.STALLED)
            callbacks.runPosted()
        }

        assertEquals(listOf("fallback"), shown)
        player.dispose()
    }

    @Test
    fun completedVideoResetsTheFailureStreakButStartingOneDoesNot() {
        val fallback = FixturePhotoSource(listOf(photo("fallback")))
        val callbacks = QueuedCallbacks()
        val source = FixturePhotoSource(listOf(video("A"), video("B"), video("C")))
        val stage = FixtureVideoStage()
        val shown = mutableListOf<String>()
        val player = fixturePlayer(source, callbacks, stage, shown, fallback)
        player.start()
        callbacks.runPosted()

        stage.failCurrent(VideoFailure.STALLED)
        callbacks.runPosted()
        stage.failCurrent(VideoFailure.STALLED)
        callbacks.runPosted()
        stage.finishCurrent()
        callbacks.runPosted()
        stage.failCurrent(VideoFailure.STALLED)
        callbacks.runPosted()
        stage.failCurrent(VideoFailure.STALLED)
        callbacks.runPosted()

        assertTrue(shown.isEmpty())
        stage.failCurrent(VideoFailure.STALLED)
        callbacks.runPosted()
        assertEquals(listOf("fallback"), shown)
        player.dispose()
    }

    @Test
    fun poolExhaustionRetriesListingThenShowsStoredPhotos() {
        var listingCalls = 0
        val source = FixturePhotoSource(
            slides = listOf(photo("1"), photo("2"), photo("3")),
            listing = {
                listingCalls++
                if (listingCalls == 1) listOf(photo("1"), photo("2"), photo("3"))
                else throw ShareUnreadableException(ReadableSample.SIZE)
            },
            image = { throw IllegalStateException("per-file refusal") },
        )
        val fallback = FixturePhotoSource(listOf(photo("fallback")))
        val callbacks = QueuedCallbacks()
        val shown = mutableListOf<String>()
        val player = fixturePlayer(source, callbacks, FixtureVideoStage(), shown, fallback)
        player.start()
        callbacks.runPosted()
        assertTrue(shown.isEmpty())

        repeat(3) {
            callbacks.runNextDelayed()
            callbacks.runPosted()
        }

        assertEquals(4, listingCalls)
        assertEquals(listOf("fallback"), shown)
        player.dispose()
    }

    @Test
    fun aPerFileFailureIsStillSkippedWhenOtherPhotosRemain() {
        val source = FixturePhotoSource(
            slides = listOf(photo("bad"), photo("good")),
            image = { slide ->
                if (slide.id == "bad") throw IllegalStateException("unreadable")
                LoadedPhoto(testBitmap(), slide.id)
            },
        )
        val callbacks = QueuedCallbacks()
        val shown = mutableListOf<String>()
        val player = fixturePlayer(source, callbacks, FixtureVideoStage(), shown)
        player.start()
        callbacks.runPosted()

        assertEquals(listOf("good"), shown)
        player.dispose()
    }

    private data class VideoFixture(
        val player: SlideshowPlayer,
        val callbacks: QueuedCallbacks,
        val stage: FixtureVideoStage,
    )

    private fun videoFixture(ids: List<String>): VideoFixture {
        val callbacks = QueuedCallbacks()
        val stage = FixtureVideoStage()
        val source = FixturePhotoSource(ids.map(::video))
        val player = fixturePlayer(source, callbacks, stage)
        return VideoFixture(player, callbacks, stage)
    }

    private fun fixturePlayer(
        source: FixturePhotoSource,
        callbacks: QueuedCallbacks,
        stage: FixtureVideoStage,
        shown: MutableList<String> = mutableListOf(),
        fallback: PhotoSource? = null,
    ): SlideshowPlayer = SlideshowPlayer(
        photoSource = source,
        intervalMillis = { 1_000L },
        showSlide = { slide, _ -> shown += slide.id },
        showLoading = {},
        showEmptyState = {},
        showError = { error("Unexpected player error: $it") },
        fallbackSource = fallback,
        videoStage = stage,
        callbacks = callbacks,
        worker = DirectExecutorService(),
        fallbackWorker = DirectExecutorService(),
        clockMillis = { 0L },
        logger = QuietPlayerLogger,
    )

    private class FixturePhotoSource(
        private val slides: List<Slide>,
        private val listing: (() -> List<Slide>)? = null,
        private val image: ((Slide) -> LoadedPhoto)? = null,
    ) : PhotoSource {
        val loadedPhotos = mutableListOf<String>()

        override fun loadSlides(): List<Slide> = listing?.invoke() ?: slides

        override fun loadImage(slide: Slide): LoadedPhoto {
            loadedPhotos += slide.id
            return image?.invoke(slide) ?: LoadedPhoto(testBitmap(), slide.id)
        }

        override fun prepareVideo(slide: Slide): PreparedVideo {
            val remote = object : RemoteVideoFile {
                override val sizeBytes = 128L
                override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int = -1
                override fun abort() = Unit
            }
            val directory = Files.createTempDirectory("player-video-test").toFile()
            val reader = ProgressiveReader(remote, File(directory, "unused.part"), 1.0)
            return PreparedVideo(reader, 1.0, slide.id)
        }
    }

    private class FixtureVideoStage : VideoStage {
        val played = mutableListOf<String>()
        private val active = mutableListOf<Pair<String, VideoEvents>>()

        override fun play(video: PreparedVideo, events: VideoEvents): VideoPlayback {
            val id = requireNotNull(video.label)
            played += id
            active += id to events
            events.onStarted()
            return object : VideoPlayback {
                override fun cancel() = Unit
            }
        }

        fun failCurrent(reason: VideoFailure) {
            active.last().second.onFailed(reason)
        }

        fun finishCurrent() {
            active.last().second.onFinished()
        }

        fun finishDownload() {
            active.last().second.onDownloadComplete()
        }
    }

    private fun video(id: String): Slide = Slide(
        id = id,
        description = "",
        kind = SlideKind.VIDEO,
        video = VideoItem(id, 1.0, 128L),
    )

    private fun photo(id: String): Slide = Slide(id, "")

    private class QueuedCallbacks : CallbackDispatcher {
        private val posted = ArrayDeque<Runnable>()
        private val delayed = ArrayDeque<Runnable>()

        override fun post(callback: Runnable) {
            posted.addLast(callback)
        }

        override fun postDelayed(callback: Runnable, delayMillis: Long) {
            delayed.addLast(callback)
        }

        override fun removeCallbacks(callback: Runnable) {
            posted.remove(callback)
            delayed.remove(callback)
        }

        fun runPosted() {
            while (posted.isNotEmpty()) posted.removeFirst().run()
        }

        fun runNextDelayed() {
            delayed.removeFirst().run()
        }
    }

    private class DirectExecutorService : AbstractExecutorService() {
        private var stopped = false

        override fun execute(command: Runnable) {
            check(!stopped)
            command.run()
        }

        override fun shutdown() {
            stopped = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return mutableListOf()
        }

        override fun isShutdown(): Boolean = stopped

        override fun isTerminated(): Boolean = stopped

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = stopped
    }

    private object QuietPlayerLogger : PlayerLogger {
        override fun info(message: String) = Unit
        override fun warning(message: String) = Unit
    }
}
