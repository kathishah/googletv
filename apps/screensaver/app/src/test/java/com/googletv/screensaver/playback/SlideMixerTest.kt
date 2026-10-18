package com.googletv.screensaver.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SlideMixerTest {
    @Test
    fun videosAppearInProportionToTheirShareOfTheLibrary() {
        // About 8.6% of this library's media are videos; 250 slots round up to 22.
        assertEquals(22, SlideMixer.videoSlots(250, 3_258, 34_526))
        assertEquals(87, SlideMixer.videoSlots(1_000, 3_258, 34_526))
        assertEquals(500, SlideMixer.videoSlots(1_000, 1_000, 1_000))
    }

    @Test
    fun aLibraryWithAnyVideosGetsAtLeastOneSlot() {
        assertEquals(1, SlideMixer.videoSlots(100, 1, 1_000_000))
        assertEquals(0, SlideMixer.videoSlots(100, 0, 500))
        assertEquals(0, SlideMixer.videoSlots(0, 50, 500))
    }

    @Test
    fun theCountNeverExceedsWhatExistsAndLeavesRoomForPhotos() {
        assertEquals(3, SlideMixer.videoSlots(100, 3, 2))
        assertEquals(99, SlideMixer.videoSlots(100, 5_000, 1)) // nearly all videos: one slot stays for a photo
    }

    @Test
    fun withoutPhotosTheWholePoolCanBeVideos() {
        assertEquals(100, SlideMixer.videoSlots(100, 5_000, 0))
        assertEquals(40, SlideMixer.videoSlots(100, 40, 0))
    }

    @Test
    fun pickVideosIsARandomSubsetWithoutRepeats() {
        val videos = (1..50).map { VideoItem("0/$it.mov", 10.0, 1_000L) }
        val picked = SlideMixer.pickVideos(videos, 10, Random(1))
        assertEquals(10, picked.size)
        assertEquals(10, picked.map { it.path }.toSet().size)
        assertTrue(videos.containsAll(picked))
        assertEquals(50, SlideMixer.pickVideos(videos, 500, Random(1)).size)
        assertEquals(videos.toSet(), SlideMixer.pickVideos(videos, 50, Random(2)).toSet())
        assertTrue(SlideMixer.pickVideos(emptyList(), 5).isEmpty())
    }

    @Test
    fun shuffleTogetherKeepsEverythingOnce() {
        val mixed = SlideMixer.shuffleTogether(listOf("p1", "p2", "p3"), listOf("v1", "v2"), Random(7))
        assertEquals(setOf("p1", "p2", "p3", "v1", "v2"), mixed.toSet())
        assertEquals(5, mixed.size)
    }
}
