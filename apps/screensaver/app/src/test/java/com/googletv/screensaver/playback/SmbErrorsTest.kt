package com.googletv.screensaver.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import com.hierynomus.mserref.NtStatus

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
    fun perFileRefusalsKeepTheConnectionButSessionFailuresDiscardAndReconnectWhenReused() {
        assertFalse(SmbFailureKind.PER_FILE.shouldDiscardConnection())
        assertTrue(SmbFailureKind.TRANSIENT.shouldDiscardConnection())
        assertTrue(SmbFailureKind.CONFIGURATION.shouldDiscardConnection())

        assertFalse(SmbFailureKind.TRANSIENT.shouldReconnectReusedConnection(reused = false))
        assertTrue(SmbFailureKind.TRANSIENT.shouldReconnectReusedConnection(reused = true))
        assertFalse(SmbFailureKind.PER_FILE.shouldReconnectReusedConnection(reused = true))
    }

    @Test
    fun onlyExplicitNotFoundStatusesMeanTheWalIsAbsent() {
        assertTrue(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.isMissingRemoteFile())
        assertTrue(NtStatus.STATUS_OBJECT_PATH_NOT_FOUND.isMissingRemoteFile())
        assertTrue(NtStatus.STATUS_NO_SUCH_FILE.isMissingRemoteFile())
        assertFalse(NtStatus.STATUS_ACCESS_DENIED.isMissingRemoteFile())
    }

    @Test
    fun otherKindsAreUnchanged() {
        assertEquals(SmbFailureKind.CONFIGURATION, SmbConfigurationException("x").smbFailureKind())
        assertEquals(SmbFailureKind.PER_FILE, IOException("decoder").smbFailureKind())
        assertEquals(SmbFailureKind.TRANSIENT, java.net.SocketException("reset").smbFailureKind())
        assertTrue(ShareUnreadableException(1).safeCauseChain().endsWith("ShareUnreadableException"))
    }
}
