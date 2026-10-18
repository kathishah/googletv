package com.googletv.screensaver.playback

/**
 * Decides when playback should pause so the player never starves mid-clip, and when it may resume.
 * Plain Kotlin; the video stage feeds it the buffer state a few times a second.
 *
 * Pausing on purpose holds the last frame cleanly instead of letting the player stutter or freeze at
 * random while it waits for data. The same wait happens either way; this only makes it tidy.
 */
internal class BufferSafeguard(
    private val lowSeconds: Double = 1.0,
    private val resumeSeconds: Double = 4.0,
    private val maxNoProgressMillis: Long = 10_000L,
) {
    enum class Decision { CONTINUE, PAUSE, RESUME, ABANDON }

    /**
     * @param aheadSeconds seconds of video already downloaded beyond the playback position
     * @param downloadComplete the whole file is on this TV, so nothing can run dry
     * @param finishesInTime the rest of the download is expected to finish before the clip does
     * @param noProgressMillis how long the download has gone without a new block
     */
    fun decide(
        paused: Boolean,
        aheadSeconds: Double,
        downloadComplete: Boolean,
        finishesInTime: Boolean,
        noProgressMillis: Long,
    ): Decision {
        if (downloadComplete) return if (paused) Decision.RESUME else Decision.CONTINUE
        if (paused) {
            return when {
                noProgressMillis > maxNoProgressMillis -> Decision.ABANDON
                aheadSeconds >= resumeSeconds -> Decision.RESUME
                finishesInTime && aheadSeconds >= 2 * lowSeconds -> Decision.RESUME
                else -> Decision.CONTINUE
            }
        }
        return if (aheadSeconds < lowSeconds) Decision.PAUSE else Decision.CONTINUE
    }
}
