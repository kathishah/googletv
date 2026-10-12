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

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            onTick()
            handler.postDelayed(this, intervalMillis())
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.postDelayed(ticker, intervalMillis())
    }

    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
    }
}
