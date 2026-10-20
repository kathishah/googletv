package com.googletv.screensaver.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbSourceIdentityTest {
    @Test
    fun normalizedHostShareAndPathHaveAStablePrivateIdentity() {
        val first = SmbSourceIdentity.forConfig(SmbSourceConfig(" photos.local ", " Photos ", "/Photos Library/originals/"))
        val same = SmbSourceIdentity.forConfig(SmbSourceConfig("PHOTOS.LOCAL", "PHOTOS", "Photos Library\\originals"))

        assertEquals(first, same)
        assertTrue(first.matches(Regex("[a-f0-9]{64}")))
        assertNotEquals("photos.local", first)
    }

    @Test
    fun changingAnySourceLocationPartChangesTheIdentity() {
        val original = SmbSourceConfig("photos.local", "Photos", "Photos Library\\originals")
        val id = SmbSourceIdentity.forConfig(original)

        assertNotEquals(id, SmbSourceIdentity.forConfig(original.copy(host = "other.local")))
        assertNotEquals(id, SmbSourceIdentity.forConfig(original.copy(share = "Other")))
        assertNotEquals(id, SmbSourceIdentity.forConfig(original.copy(rootPath = "Other Library\\originals")))
    }
}
