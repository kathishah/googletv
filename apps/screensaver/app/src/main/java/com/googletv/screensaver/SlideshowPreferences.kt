package com.googletv.screensaver

import android.content.Context

data class IntervalPreset(val seconds: Int, val label: String) {
    val millis: Long get() = seconds * 1_000L

    companion object {
        val all = listOf(
            IntervalPreset(10, "10 sec"),
            IntervalPreset(30, "30 sec"),
            IntervalPreset(60, "1 min"),
            IntervalPreset(300, "5 min"),
            IntervalPreset(900, "15 min"),
        )
        val default = all[1]
    }
}

class SlideshowPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("slideshow", Context.MODE_PRIVATE)

    fun intervalSeconds(): Int = preferences.getInt(KEY_INTERVAL_SECONDS, IntervalPreset.default.seconds)

    fun intervalMillis(): Long = intervalSeconds() * 1_000L

    fun setInterval(preset: IntervalPreset) {
        preferences.edit().putInt(KEY_INTERVAL_SECONDS, preset.seconds).apply()
    }

    companion object {
        private const val KEY_INTERVAL_SECONDS = "interval_seconds"
    }
}
