package com.googletv.screensaver.playback

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private class QueuedCallbacks : CallbackDispatcher {
        private val posted = ArrayDeque<Runnable>()

        override fun post(callback: Runnable) {
            posted.addLast(callback)
        }

        override fun postDelayed(callback: Runnable, delayMillis: Long) {
            posted.addLast(callback)
        }

        override fun removeCallbacks(callback: Runnable) {
            posted.remove(callback)
        }

        fun runPosted() {
            while (posted.isNotEmpty()) posted.removeFirst().run()
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
}
