package com.googletv.screensaver

import com.googletv.screensaver.playback.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumCoverRulesTest {
    private fun video(name: String, size: Long) = VideoItem("0/$name.mov", 10.0, size)

    @Test
    fun postersAreUsedForTypeVideosAndForVideoOnlyAlbums() {
        assertTrue(AlbumCoverRules.usesVideoPoster(MediaType.VIDEOS, 300, 5)) // Type Videos: always a poster when there are videos
        assertTrue(AlbumCoverRules.usesVideoPoster(MediaType.VIDEOS, 0, 5))
        assertTrue(AlbumCoverRules.usesVideoPoster(MediaType.PHOTOS_AND_VIDEOS, 0, 5)) // no photos to show
        assertFalse(AlbumCoverRules.usesVideoPoster(MediaType.PHOTOS_AND_VIDEOS, 300, 5)) // photo albums keep their photo cover
    }

    @Test
    fun theCoverStaysAPhotoWheneverVideosAreOutOfScopeOrAbsent() {
        assertFalse(AlbumCoverRules.usesVideoPoster(MediaType.PHOTOS, 0, 5)) // Type Photos is unchanged
        assertFalse(AlbumCoverRules.usesVideoPoster(MediaType.PHOTOS, 300, 5))
        assertFalse(AlbumCoverRules.usesVideoPoster(MediaType.VIDEOS, 300, 0)) // nothing to take a frame from
        assertFalse(AlbumCoverRules.usesVideoPoster(MediaType.PHOTOS_AND_VIDEOS, 0, 0))
    }

    @Test
    fun candidatesAreTheLightestOfTheFirstEightUpToFour() {
        val videos = (1..12).map { video("v$it", (13 - it) * 1_000L) } // later ones are lighter
        val candidates = AlbumCoverRules.posterCandidates(videos)
        // Only the first eight (sizes 12000 down to 5000) are considered; the four lightest of those win.
        assertEquals(listOf(5_000L, 6_000L, 7_000L, 8_000L), candidates.map { it.sizeBytes })
        assertEquals(2, AlbumCoverRules.posterCandidates(videos.take(2)).size)
        assertTrue(AlbumCoverRules.posterCandidates(emptyList()).isEmpty())
    }

    @Test
    fun thePhotoCacheKeyIsExactlyTheOneUsedBeforeVideosExisted() {
        // SHA-256 of "id" + NUL + the first four paths joined by NUL, as MainActivity computed it before this change.
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("A1\u0000a.jpg\u0000b.jpg\u0000c.jpg\u0000d.jpg".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(expected, AlbumCoverRules.photoCacheKey("A1", listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg")))
        assertEquals(64, expected.length)
    }

    @Test
    fun posterKeysAreSeparateAndFollowTheCandidates() {
        val a = listOf(video("a", 1), video("b", 2))
        val photoKey = AlbumCoverRules.photoCacheKey("A1", listOf("0/a.mov", "0/b.mov"))
        val posterKey = AlbumCoverRules.posterCacheKey("A1", a)
        assertNotEquals(photoKey, posterKey)
        assertEquals(posterKey, AlbumCoverRules.posterCacheKey("A1", a))
        assertNotEquals(posterKey, AlbumCoverRules.posterCacheKey("A2", a))
        assertNotEquals(posterKey, AlbumCoverRules.posterCacheKey("A1", a.reversed())) // a different first choice is a different poster
    }

    @Test
    fun posterSizeFitsTheTargetKeepsAspectAndNeverEnlarges() {
        assertEquals(480 to 270, AlbumCoverRules.posterSize(1920, 1080, 480, 300))
        assertEquals(169 to 300, AlbumCoverRules.posterSize(1080, 1920, 480, 300)) // portrait: height limits
        assertEquals(240 to 135, AlbumCoverRules.posterSize(240, 135, 480, 300)) // already small
        assertEquals(480 to 300, AlbumCoverRules.posterSize(480, 300, 480, 300))
        assertEquals(1 to 1, AlbumCoverRules.posterSize(0, 100, 480, 300))
        assertEquals(1 to 1, AlbumCoverRules.posterSize(-5, -5, 480, 300))
    }
}
