package com.googletv.screensaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTypeRulesTest {
    @Test
    fun parseFallsBackToPhotosForMissingOrUnknownValues() {
        assertEquals(MediaType.PHOTOS, MediaType.parse(null))
        assertEquals(MediaType.PHOTOS, MediaType.parse(""))
        assertEquals(MediaType.PHOTOS, MediaType.parse("garbage"))
        assertEquals(MediaType.PHOTOS, MediaType.parse("photos")) // names are case-sensitive
        MediaType.all.forEach { assertEquals(it, MediaType.parse(it.name)) }
    }

    @Test
    fun theThreeChoicesKeepTheirOrderAndLabels() {
        assertEquals(listOf("Photos", "Photos + Videos", "Videos"), MediaType.all.map { it.label })
        assertEquals(MediaType.PHOTOS, MediaType.default)
    }

    @Test
    fun includesFlagsMatchTheType() {
        assertTrue(MediaType.PHOTOS.includesPhotos); assertFalse(MediaType.PHOTOS.includesVideos)
        assertTrue(MediaType.PHOTOS_AND_VIDEOS.includesPhotos); assertTrue(MediaType.PHOTOS_AND_VIDEOS.includesVideos)
        assertFalse(MediaType.VIDEOS.includesPhotos); assertTrue(MediaType.VIDEOS.includesVideos)
    }

    @Test
    fun theScreensaverFallsBackToRandomPhotosWheneverVideosAreIncluded() {
        assertEquals(DreamPlan.SAVED_SELECTION, MediaTypeRules.dreamPlan(MediaType.PHOTOS))
        assertEquals(DreamPlan.RANDOM_PHOTOS, MediaTypeRules.dreamPlan(MediaType.PHOTOS_AND_VIDEOS))
        assertEquals(DreamPlan.RANDOM_PHOTOS, MediaTypeRules.dreamPlan(MediaType.VIDEOS))
        assertEquals(500, MediaTypeRules.DREAM_FALLBACK_POOL)
    }

    @Test
    fun photosTypeKeepsTodaysCardAndTotalTextExactly() {
        assertEquals("318 pictures", MediaTypeRules.cardCountText(MediaType.PHOTOS, 318, 12))
        assertEquals("0 pictures", MediaTypeRules.cardCountText(MediaType.PHOTOS, 0, 21))
        assertEquals("3 albums  ·  970 pictures", MediaTypeRules.totalText(MediaType.PHOTOS, 3, 970, 50))
        assertEquals("2 albums · 842 photos", MediaTypeRules.summaryText(MediaType.PHOTOS, 2, 842, 9))
        assertFalse(MediaTypeRules.isEmptyForType(MediaType.PHOTOS, 0, 0))
        assertFalse(MediaTypeRules.showsVideoBadge(MediaType.PHOTOS, 40))
        assertNull(MediaTypeRules.typeNote(MediaType.PHOTOS))
        assertEquals(
            "2 selected album(s) have no readable supported pictures. Keep the selection or choose other albums.",
            MediaTypeRules.emptySelectionWarning(MediaType.PHOTOS, 2),
        )
    }

    @Test
    fun cardAndTotalTextFollowTheType() {
        assertEquals("318 pictures · 12 videos", MediaTypeRules.cardCountText(MediaType.PHOTOS_AND_VIDEOS, 318, 12))
        assertEquals("5 pictures · 1 video", MediaTypeRules.cardCountText(MediaType.PHOTOS_AND_VIDEOS, 5, 1))
        assertEquals("12 videos", MediaTypeRules.cardCountText(MediaType.VIDEOS, 318, 12))
        assertEquals("1 video", MediaTypeRules.cardCountText(MediaType.VIDEOS, 3, 1))
        assertEquals("No videos", MediaTypeRules.cardCountText(MediaType.VIDEOS, 3, 0))
        assertEquals("No pictures or videos", MediaTypeRules.cardCountText(MediaType.PHOTOS_AND_VIDEOS, 0, 0))
        assertEquals("0 pictures · 4 videos", MediaTypeRules.cardCountText(MediaType.PHOTOS_AND_VIDEOS, 0, 4))
        assertEquals("7 pictures · 0 videos", MediaTypeRules.cardCountText(MediaType.PHOTOS_AND_VIDEOS, 7, 0))
        assertEquals("3 albums  ·  1,020".replace(",", "") + " items", MediaTypeRules.totalText(MediaType.PHOTOS_AND_VIDEOS, 3, 970, 50))
        assertEquals("3 albums  ·  50 videos", MediaTypeRules.totalText(MediaType.VIDEOS, 3, 970, 50))
        assertEquals("3 albums · 970 photos · 50 videos", MediaTypeRules.summaryText(MediaType.PHOTOS_AND_VIDEOS, 3, 970, 50))
        assertEquals("3 albums · 50 videos", MediaTypeRules.summaryText(MediaType.VIDEOS, 3, 970, 50))
    }

    @Test
    fun anUnknownVideoTotalIsLeftOutOfTheSummaryNotShownAsZero() {
        assertEquals("11 albums · 953 photos", MediaTypeRules.summaryText(MediaType.PHOTOS, 11, 953, -1))
        assertEquals("11 albums · 953 photos", MediaTypeRules.summaryText(MediaType.PHOTOS_AND_VIDEOS, 11, 953, -1))
        assertEquals("11 albums", MediaTypeRules.summaryText(MediaType.VIDEOS, 11, 953, -1))
        assertEquals("11 albums · 953 photos · 0 videos", MediaTypeRules.summaryText(MediaType.PHOTOS_AND_VIDEOS, 11, 953, 0))
    }

    @Test
    fun eligibleCountAndEmptyRules() {
        assertEquals(318, MediaTypeRules.eligibleCount(MediaType.PHOTOS, 318, 12))
        assertEquals(330, MediaTypeRules.eligibleCount(MediaType.PHOTOS_AND_VIDEOS, 318, 12))
        assertEquals(12, MediaTypeRules.eligibleCount(MediaType.VIDEOS, 318, 12))
        assertTrue(MediaTypeRules.isEmptyForType(MediaType.VIDEOS, 318, 0))
        assertFalse(MediaTypeRules.isEmptyForType(MediaType.VIDEOS, 0, 4))
        assertTrue(MediaTypeRules.isEmptyForType(MediaType.PHOTOS_AND_VIDEOS, 0, 0))
        assertFalse(MediaTypeRules.isEmptyForType(MediaType.PHOTOS_AND_VIDEOS, 0, 1))
    }

    @Test
    fun badgeOnlyWhenVideosAreInScopeAndPresent() {
        assertTrue(MediaTypeRules.showsVideoBadge(MediaType.PHOTOS_AND_VIDEOS, 1))
        assertTrue(MediaTypeRules.showsVideoBadge(MediaType.VIDEOS, 1))
        assertFalse(MediaTypeRules.showsVideoBadge(MediaType.VIDEOS, 0))
    }

    @Test
    fun warningsAndNotes() {
        assertNull(MediaTypeRules.emptySelectionWarning(MediaType.VIDEOS, 0))
        assertEquals("1 selected album(s) have no videos. Keep the selection or choose other albums.", MediaTypeRules.emptySelectionWarning(MediaType.VIDEOS, 1))
        assertEquals("1 selected album(s) have no pictures or videos. Keep the selection or choose other albums.", MediaTypeRules.emptySelectionWarning(MediaType.PHOTOS_AND_VIDEOS, 1))
        assertEquals(
            "Videos play in the app slideshow only. The screensaver shows 500 random photos.",
            MediaTypeRules.typeNote(MediaType.VIDEOS),
        )
    }
}
