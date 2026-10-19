package com.googletv.screensaver.playback

/** Calls [onTick] at the current user-selected interval. It does not know about photos or views. */
internal class SlideshowScheduler(
    private val intervalMillis: () -> Long,
    private val onTick: () -> Unit,
    private val dispatcher: CallbackDispatcher = HandlerCallbackDispatcher(),
) {
    private var running = false
    private var scheduleGeneration = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val runGeneration = scheduleGeneration
            onTick()
            if (running && runGeneration == scheduleGeneration) {
                dispatcher.postDelayed(this, intervalMillis())
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        dispatcher.postDelayed(ticker, intervalMillis())
    }

    fun stop() {
        running = false
        scheduleGeneration++
        dispatcher.removeCallbacks(ticker)
    }

    /** Restart the dwell interval from the moment a newly decoded slide is displayed. */
    fun resetInterval() {
        if (!running) return
        scheduleGeneration++
        dispatcher.removeCallbacks(ticker)
        dispatcher.postDelayed(ticker, intervalMillis())
    }
}
