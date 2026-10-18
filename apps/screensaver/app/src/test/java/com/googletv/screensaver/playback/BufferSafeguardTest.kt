package com.googletv.screensaver.playback

import com.googletv.screensaver.playback.BufferSafeguard.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class BufferSafeguardTest {
    private val guard = BufferSafeguard(lowSeconds = 1.0, resumeSeconds = 4.0, maxNoProgressMillis = 10_000L)

    private fun decide(
        paused: Boolean = false,
        ahead: Double = 10.0,
        complete: Boolean = false,
        inTime: Boolean = false,
        stalledMillis: Long = 0L,
    ) = guard.decide(paused, ahead, complete, inTime, stalledMillis)

    @Test
    fun aHealthyBufferKeepsPlaying() {
        assertEquals(Decision.CONTINUE, decide(ahead = 10.0))
        assertEquals(Decision.CONTINUE, decide(ahead = 1.0)) // exactly the low mark is still fine
    }

    @Test
    fun runningLowPauses() {
        assertEquals(Decision.PAUSE, decide(ahead = 0.9))
        assertEquals(Decision.PAUSE, decide(ahead = 0.0, inTime = true)) // even if the finish looks fine, nothing is buffered now
    }

    @Test
    fun aPausedPlayerResumesOnceEnoughIsBuffered() {
        assertEquals(Decision.CONTINUE, decide(paused = true, ahead = 3.9))
        assertEquals(Decision.RESUME, decide(paused = true, ahead = 4.0))
        assertEquals(Decision.RESUME, decide(paused = true, ahead = 12.0))
    }

    @Test
    fun aPausedPlayerMayResumeEarlyWhenTheDownloadWillFinishInTime() {
        assertEquals(Decision.CONTINUE, decide(paused = true, ahead = 1.5, inTime = true)) // not even two low marks yet
        assertEquals(Decision.RESUME, decide(paused = true, ahead = 2.0, inTime = true))
        assertEquals(Decision.CONTINUE, decide(paused = true, ahead = 2.0, inTime = false))
    }

    @Test
    fun noProgressForTooLongAbandonsAPausedClip() {
        assertEquals(Decision.CONTINUE, decide(paused = true, ahead = 0.0, stalledMillis = 10_000L))
        assertEquals(Decision.ABANDON, decide(paused = true, ahead = 0.0, stalledMillis = 10_001L))
        assertEquals(Decision.ABANDON, decide(paused = true, ahead = 3.0, stalledMillis = 20_000L)) // buffer too small to resume, and nothing coming
    }

    @Test
    fun aPlayingClipIsNeverAbandonedByTheSafeguard() {
        // The player's own read timeout handles a hard stall; here a low buffer just pauses.
        assertEquals(Decision.PAUSE, decide(ahead = 0.2, stalledMillis = 60_000L))
    }

    @Test
    fun whenEverythingIsLocalNothingPausesAndAnythingPausedResumes() {
        assertEquals(Decision.CONTINUE, decide(ahead = 0.0, complete = true))
        assertEquals(Decision.RESUME, decide(paused = true, ahead = 0.0, complete = true, stalledMillis = 60_000L))
    }
}
