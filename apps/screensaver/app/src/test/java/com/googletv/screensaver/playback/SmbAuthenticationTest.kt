package com.googletv.screensaver.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbAuthenticationTest {
    @Test
    fun guestIsTheDefaultAndUsesExplicitGuestMode() {
        val authentication = SmbAuthentication()

        assertEquals(SmbAuthMode.GUEST, authentication.mode)
        assertTrue(authentication.validated().mode == SmbAuthMode.GUEST)
        assertTrue(authentication.toAuthenticationContext().isGuest)
    }

    @Test
    fun registeredModeRequiresUsernameAndPassword() {
        assertInvalid(SmbAuthentication(SmbAuthMode.REGISTERED, "", "secret"))
        assertInvalid(SmbAuthentication(SmbAuthMode.REGISTERED, "user", ""))
    }

    @Test
    fun registeredCredentialsAreTrimmedAndPasswordIsRedacted() {
        val authentication = SmbAuthentication(SmbAuthMode.REGISTERED, " user ", "secret")

        assertEquals("user", authentication.validated().username)
        assertEquals("user", authentication.toAuthenticationContext().username)
        assertTrue(authentication.toString().contains("registered=true"))
        assertFalse(authentication.toString().contains("secret"))
    }

    private fun assertInvalid(authentication: SmbAuthentication) {
        try {
            authentication.validated()
            throw AssertionError("Expected invalid registered credentials to fail validation")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
