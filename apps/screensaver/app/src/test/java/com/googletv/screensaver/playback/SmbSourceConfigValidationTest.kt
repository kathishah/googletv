package com.googletv.screensaver.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SmbSourceConfigValidationTest {
    @Test
    fun trimsConnectionFieldsAndNormalizesPathSeparators() {
        val result = SmbSourceConfig("  photos.local  ", " Photos ", "/Photos Library/originals/").validated()

        assertEquals(SmbSourceConfig("photos.local", "Photos", "Photos Library\\originals"), result)
    }

    @Test
    fun rejectsBlankOrMalformedHost() {
        listOf("", "  ", "bad/host", "bad\\host", "user@host").forEach { host ->
            expectInvalid(SmbSourceConfig(host, "Photos", "originals"))
        }
    }

    @Test
    fun rejectsBlankOrMalformedShare() {
        listOf("", "  ", "Photos/other", "Photos\\other").forEach { share ->
            expectInvalid(SmbSourceConfig("photos.local", share, "originals"))
        }
    }

    @Test
    fun rejectsBlankOrEscapingFolderPaths() {
        listOf("", "  ", ".", "..", "Photos/../private", "Photos//originals").forEach { path ->
            expectInvalid(SmbSourceConfig("photos.local", "Photos", path))
        }
    }

    private fun expectInvalid(config: SmbSourceConfig) {
        try {
            config.validated()
            fail("Expected invalid SMB configuration to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
