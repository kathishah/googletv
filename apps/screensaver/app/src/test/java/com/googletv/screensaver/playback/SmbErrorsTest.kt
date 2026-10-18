package com.googletv.screensaver.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SmbErrorsTest {
    @Test
    fun anUnreadableShareIsTemporaryEvenWhenWrapped() {
        assertEquals(SmbFailureKind.TRANSIENT, ShareUnreadableException(8).smbFailureKind())
        assertEquals(SmbFailureKind.TRANSIENT, RuntimeException("outer", ShareUnreadableException(8)).smbFailureKind())
    }

    @Test
    fun itIsNotAnAlbumIndexErrorSoThePlayerRetriesInsteadOfStopping() {
        assertFalse(ShareUnreadableException(8) is AlbumIndexException)
    }

    @Test
    fun itsMessageNamesNoPathsOrHosts() {
        assertEquals("None of 8 sampled photos could be opened", ShareUnreadableException(8).message)
    }

    @Test
    fun otherKindsAreUnchanged() {
        assertEquals(SmbFailureKind.CONFIGURATION, SmbConfigurationException("x").smbFailureKind())
        assertEquals(SmbFailureKind.PER_FILE, IOException("decoder").smbFailureKind())
        assertEquals(SmbFailureKind.TRANSIENT, java.net.SocketException("reset").smbFailureKind())
        assertTrue(ShareUnreadableException(1).safeCauseChain().endsWith("ShareUnreadableException"))
    }
}
