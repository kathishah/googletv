package com.googletv.screensaver.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ReadableSampleTest {
    private val sample = listOf("a", "b", "c")

    @Test
    fun anEmptySampleIsNotAFailure() {
        assertNull(ReadableSample.check(emptyList()) { error("not called") })
    }

    @Test
    fun oneOpeningPhotoIsEnoughAndStopsTheCheck() {
        val tried = mutableListOf<String>()
        val result = ReadableSample.check(sample) { path ->
            tried += path
            if (path == "b") null else "STATUS_ACCESS_DENIED"
        }
        assertNull(result)
        assertEquals(listOf("a", "b"), tried)
    }

    @Test
    fun whenNothingOpensTheReasonsAreCountedByStatus() {
        val reasons = mapOf("a" to "STATUS_ACCESS_DENIED", "b" to "STATUS_OBJECT_NAME_NOT_FOUND", "c" to "STATUS_ACCESS_DENIED")
        val result = ReadableSample.check(sample) { reasons.getValue(it) }
        assertEquals(mapOf("STATUS_ACCESS_DENIED" to 2, "STATUS_OBJECT_NAME_NOT_FOUND" to 1), result)
    }

    @Test
    fun aDroppedConnectionPassesThroughInsteadOfCountingAsUnreadable() {
        try {
            ReadableSample.check(sample) { throw java.net.ConnectException("down") }
            fail("expected the connection error to propagate")
        } catch (_: java.net.ConnectException) {
            // expected: the player's retry path handles it
        }
    }
}
