package com.googletv.screensaver.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoExclusionTest {
    private val root = "Photos Library.photoslibrary\\originals"
    private val exclusion = PhotoExclusion(setOf("0/1/Scan.HEIC", "A/b.png"))

    @Test
    fun matchesRelativePathsWithEitherSlashAndAnyCase() {
        assertTrue("0/1/scan.heic" in exclusion)
        assertTrue("0\\1\\SCAN.heic" in exclusion)
        assertTrue("a/B.PNG" in exclusion)
        assertFalse("0/1/other.heic" in exclusion)
        assertFalse("0/scan.heic" in exclusion)
    }

    @Test
    fun matchesSmbPathsUnderTheRoot() {
        assertTrue(exclusion.excludesSmbPath(root, "$root\\0\\1\\Scan.HEIC"))
        assertFalse(exclusion.excludesSmbPath(root, "$root\\0\\1\\keep.heic"))
    }

    @Test
    fun aPathOutsideTheRootOrTooShortNeverMatches() {
        assertFalse(exclusion.excludesSmbPath(root, root))
        assertFalse(exclusion.excludesSmbPath(root, "$root\\"))
        assertFalse(exclusion.excludesSmbPath(root, "x"))
    }

    @Test
    fun anEmptyListExcludesNothing() {
        assertTrue(PhotoExclusion.NONE.isEmpty)
        assertEquals(0, PhotoExclusion.NONE.size)
        assertFalse(PhotoExclusion.NONE.excludesSmbPath(root, "$root\\0\\a.jpg"))
        assertFalse("0/a.jpg" in PhotoExclusion.NONE)
    }
}
