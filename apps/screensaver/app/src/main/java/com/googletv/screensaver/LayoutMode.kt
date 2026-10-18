package com.googletv.screensaver

/**
 * How photos share the screen. Stored as `layout_mode`, independent of Media, Type, Pool, Interval, and Label.
 * [SINGLE] is today's behavior: one photo at a time. [PORTRAIT_PAIRS] puts two portrait photos side by side.
 */
enum class LayoutMode(val label: String) {
    SINGLE("Single"),
    PORTRAIT_PAIRS("Portrait pairs");

    companion object {
        val all = entries
        val default = SINGLE

        /** A missing or unknown value is [default], so a bad preference can never change how photos look. */
        fun parse(value: String?): LayoutMode = entries.firstOrNull { it.name == value } ?: default
    }
}
