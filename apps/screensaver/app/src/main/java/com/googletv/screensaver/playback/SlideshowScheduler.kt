package com.googletv.screensaver.playback

import android.os.Handler
import android.os.Looper

/** Calls [onTick] at the current user-selected interval. It does not know about photos or views. */
class SlideshowScheduler(
    private val intervalMillis: () -> Long,
    private val onTick: () -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private var running = false
    private var scheduleGeneration = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val runGeneration = scheduleGeneration
            onTick()
            if (running && runGeneration == scheduleGeneration) {
                handler.postDelayed(this, intervalMillis())
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.postDelayed(ticker, intervalMillis())
    }

    fun stop() {
        running = false
        scheduleGeneration++
        handler.removeCallbacks(ticker)
    }

    /** Restart the dwell interval from the moment a newly decoded slide is displayed. */
    fun resetInterval() {
        if (!running) return
        scheduleGeneration++
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, intervalMillis())
    }
}
