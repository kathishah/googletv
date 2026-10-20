package com.googletv.screensaver.playback

import com.googletv.screensaver.PhotoLabelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class VideoLabelTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun label(
        date: String?, location: String?, mode: PhotoLabelMode = PhotoLabelMode.LOCATION_AND_DATE,
        lookup: ((Double, Double) -> String?)? = null,
    ) = VideoLabel.format(date, location, mode, lookup, Locale.US, utc)

    @Test
    fun placeAndDateAreJoinedLikePhotoLabels() {
        assertEquals("Yosemite Valley · Jun 12, 2022", label("20220612T180212.000Z", "+37.7459-119.5936/") { _, _ -> "Yosemite Valley" })
    }

    @Test
    fun withoutAPlaceNameTheCoordinatesAreShownLikePhotos() {
        assertEquals("37.7749°, -122.4194° · Jun 12, 2022", label("20220612T180212.000Z", "+37.7749-122.4194/"))
        assertEquals("37.7749°, -122.4194° · Jun 12, 2022", label("20220612T180212.000Z", "+37.7749-122.4194/") { _, _ -> null })
        assertEquals("37.7749°, -122.4194°", label(null, "+37.7749-122.4194/") { _, _ -> throw IllegalStateException("geocoder down") })
    }

    @Test
    fun missingPiecesAreLeftOut() {
        assertEquals("Jun 12, 2022", label("20220612T180212.000Z", null))
        assertEquals("Jun 12, 2022", label("20220612T180212Z", ""))
        assertNull(label(null, null))
        assertNull(label("garbage", "garbage"))
    }

    @Test
    fun theLabelModeIsHonored() {
        assertNull(label("20220612T180212.000Z", "+37.7749-122.4194/", PhotoLabelMode.NONE))
    }

    @Test
    fun locationParsingHandlesAltitudeSignsAndPlaceholders() {
        assertEquals(37.7749 to -122.4194, VideoLabel.parseLocation("+37.7749-122.4194/"))
        assertEquals(-33.8688 to 151.2093, VideoLabel.parseLocation("-33.8688+151.2093+012.500/"))
        assertEquals(10.0 to 20.0, VideoLabel.parseLocation(" +10+20 "))
        assertNull(VideoLabel.parseLocation("+0.0000+0.0000/")) // "no location" placeholder
        assertNull(VideoLabel.parseLocation("+95.0000+010.0000/")) // latitude out of range
        assertNull(VideoLabel.parseLocation("+10.0000+190.0000/")) // longitude out of range
        assertNull(VideoLabel.parseLocation("37.7749,-122.4194"))
        assertNull(VideoLabel.parseLocation(null))
    }

    @Test
    fun dateParsingAcceptsRetrieverFormatsAndRejectsPlaceholders() {
        assertNotNull(VideoLabel.parseDate("20220612T180212.000Z"))
        assertNotNull(VideoLabel.parseDate("20220612T180212"))
        assertNull(VideoLabel.parseDate("19040101T000000.000Z")) // zeroed camera clocks
        assertNull(VideoLabel.parseDate("20221345T180212.000Z")) // not a real date
        assertNull(VideoLabel.parseDate("2022-06-12"))
        assertNull(VideoLabel.parseDate(null))
    }

    @Test
    fun theDayFollowsTheDeviceTimeZone() {
        val sydney = TimeZone.getTimeZone("Australia/Sydney")
        assertEquals("Jun 13, 2022", VideoLabel.format("20220612T180212.000Z", null, PhotoLabelMode.LOCATION_AND_DATE, null, Locale.US, sydney))
    }
}
