package com.googletv.screensaver.playback

import android.os.Handler
import android.os.Looper

/** Main-thread callbacks used by the player and its interval scheduler. */
internal interface CallbackDispatcher {
    fun post(callback: Runnable)
    fun postDelayed(callback: Runnable, delayMillis: Long)
    fun removeCallbacks(callback: Runnable)
}

/** Android implementation. Tests can provide a deterministic queue without constructing a Looper. */
internal class HandlerCallbackDispatcher(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : CallbackDispatcher {
    override fun post(callback: Runnable) {
        handler.post(callback)
    }

    override fun postDelayed(callback: Runnable, delayMillis: Long) {
        handler.postDelayed(callback, delayMillis)
    }

    override fun removeCallbacks(callback: Runnable) {
        handler.removeCallbacks(callback)
    }
}
