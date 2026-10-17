package com.googletv.screensaver.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseTimingsTest {
    @Test
    fun summaryShowsMillisecondsBytesRateAndNotes() {
        var nanos = 0L
        val timings = PhaseTimings { nanos }
        timings.time("copy") { nanos += 2_000_000_000L }
        timings.addBytes("copy", 20_000_000L)
        timings.time("query") { nanos += 500_000_000L }
        timings.time("query") { nanos += 250_000_000L }
        timings.note("rows", 42L)
        nanos += 1_000_000L
        assertEquals(
            "total=2751ms | copy=2000ms bytes=20000000 rate=10.0MB/s | query=750ms | rows=42",
            timings.summary(),
        )
    }

    @Test
    fun phaseIsRecordedWhenTheBlockThrows() {
        var nanos = 0L
        val timings = PhaseTimings { nanos }
        runCatching { timings.time("copy") { nanos += 3_000_000L; error("boom") } }
        assertTrue(timings.summary().contains("copy=3ms"))
    }
}
