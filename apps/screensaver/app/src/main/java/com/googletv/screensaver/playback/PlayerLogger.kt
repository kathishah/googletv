package com.googletv.screensaver.playback

import android.util.Log

/** Small logging seam so player transition tests don't call Android's unmocked Log stubs. */
internal interface PlayerLogger {
    fun info(message: String)
    fun warning(message: String)
}

internal object AndroidPlayerLogger : PlayerLogger {
    override fun info(message: String) {
        Log.i("PhotoSource", message)
    }

    override fun warning(message: String) {
        Log.w("PhotoSource", message)
    }
}
