package com.googletv.screensaver.playback

/**
 * The start-up check that the share can be read at all. Opening every selected photo first took about
 * 21 s for 950 photos, so a small sample stands in: one photo that opens is enough, and a single
 * unreadable photo is skipped later when its turn comes.
 */
internal object ReadableSample {
    const val SIZE = 8

    /**
     * Tries [sample] in order with [openFailure], which returns null when the photo opened and a short
     * reason (an SMB status name, never a path) when it could not. Stops at the first photo that opens.
     * Returns null when the share is readable or [sample] is empty, otherwise how many photos failed
     * for each reason. Errors that [openFailure] throws (a dropped connection) pass through.
     */
    fun check(sample: List<String>, openFailure: (String) -> String?): Map<String, Int>? {
        if (sample.isEmpty()) return null
        val failures = sortedMapOf<String, Int>()
        for (path in sample) {
            val reason = openFailure(path) ?: return null
            failures[reason] = (failures[reason] ?: 0) + 1
        }
        return failures
    }
}
