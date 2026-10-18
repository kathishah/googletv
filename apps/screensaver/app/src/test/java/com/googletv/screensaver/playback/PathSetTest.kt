package com.googletv.screensaver.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathSetTest {
    private val root = "Photos Library.photoslibrary\\originals"
    private val exclusion = PathSet(setOf("0/1/Scan.HEIC", "A/b.png"))

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
    fun containsSmbPathIsTheSameLookupUnderAnotherName() {
        val portrait = PathSet(setOf("3/Tall.HEIC"))
        assertTrue(portrait.containsSmbPath(root, "$root\\3\\tall.heic"))
        assertFalse(portrait.containsSmbPath(root, "$root\\3\\wide.heic"))
    }

    @Test
    fun aPathOutsideTheRootOrTooShortNeverMatches() {
        assertFalse(exclusion.excludesSmbPath(root, root))
        assertFalse(exclusion.excludesSmbPath(root, "$root\\"))
        assertFalse(exclusion.excludesSmbPath(root, "x"))
    }

    @Test
    fun anEmptyListExcludesNothing() {
        assertTrue(PathSet.NONE.isEmpty)
        assertEquals(0, PathSet.NONE.size)
        assertFalse(PathSet.NONE.excludesSmbPath(root, "$root\\0\\a.jpg"))
        assertFalse("0/a.jpg" in PathSet.NONE)
    }
}
