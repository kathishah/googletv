package com.googletv.screensaver.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairFitTest {
    private val screenW = 1920
    private val screenH = 1080

    private fun fit(w1: Int, h1: Int, w2: Int, h2: Int) = PairFit.fit(w1, h1, w2, h2, screenW, screenH)!!

    /** Everything a layout must satisfy, whatever the shapes. */
    private fun assertValid(layout: PairLayout) {
        listOf(layout.first, layout.second).forEach {
            assertTrue(it.left >= 0 && it.right <= screenW && it.top >= 0 && it.bottom <= screenH)
        }
        assertEquals(layout.first.height, layout.second.height)
        assertEquals(layout.first.top, layout.second.top)
        assertEquals(screenW, layout.leftSlice + layout.first.width + layout.middleSlice + layout.second.width + layout.rightSlice)
        assertEquals(layout.leftSlice, layout.first.left)
        assertEquals(layout.first.right + layout.middleSlice, layout.second.left)
        assertEquals(screenW, layout.second.right + layout.rightSlice)
        // Equal slices, within the odd pixels the right one takes.
        assertEquals(layout.leftSlice, layout.middleSlice)
        assertTrue(layout.rightSlice - layout.leftSlice in 0..2)
        assertTrue(layout.leftSlice >= PairFit.MIN_SLICE - 1)
        // The group is centered vertically.
        assertTrue(Math.abs(layout.first.top - (screenH - layout.first.bottom)) <= 1)
    }

    @Test
    fun twoThreeByFourPhotosFillEightyFourPercentWithHundredPixelSlices() {
        val layout = fit(3024, 4032, 3024, 4032)
        assertValid(layout)
        assertEquals(PairRect(100, 0, 810, 1080), layout.first)
        assertEquals(PairRect(1010, 0, 810, 1080), layout.second)
        assertEquals(listOf(100, 100, 100), listOf(layout.leftSlice, layout.middleSlice, layout.rightSlice))
        assertEquals(0.84, layout.photoFill(screenW, screenH), 0.005)
    }

    @Test
    fun threeByFourPlusTwoByThreeLeavesHundredThirtyPixelSlices() {
        val layout = fit(3, 4, 2, 3)
        assertValid(layout)
        assertEquals(810, layout.first.width)
        assertEquals(720, layout.second.width)
        assertEquals(130, layout.leftSlice)
        assertEquals(0.80, layout.photoFill(screenW, screenH), 0.005)
    }

    @Test
    fun twoTwoByThreePhotosFillSeventyFivePercent() {
        val layout = fit(2, 3, 2, 3)
        assertValid(layout)
        assertEquals(720, layout.first.width)
        assertEquals(160, layout.leftSlice) // free space 480, three slices of 160
        assertEquals(0.75, layout.photoFill(screenW, screenH), 0.005)
    }

    @Test
    fun twoNineBySixteenPhotosFillSixtyThreePercentWithAboutTwoHundredThirtyFiveSlices() {
        val layout = fit(9, 16, 9, 16)
        assertValid(layout)
        assertEquals(0.63, layout.photoFill(screenW, screenH), 0.005)
        assertTrue(layout.leftSlice in 233..236)
    }

    @Test
    fun theHeightIsAlwaysTheScreenHeightUpToTheShapesThatDoNotFit() {
        // Every shape pair in the table, and all aspect pairs up to a sum of 1.7 (two 0.85 photos).
        for (a in 56..85) for (b in a..85) {
            val layout = fit(a, 100, b, 100)
            assertValid(layout)
            assertEquals("aspects $a/$b", screenH, layout.first.height)
        }
    }

    @Test
    fun pairsNearTheLimitShrinkJustEnoughToKeepTheSlices() {
        val layout = fit(9, 10, 9, 10) // two photos at the 0.9 limit: 1944 px of photo at full height
        assertValid(layout)
        assertTrue(layout.first.height < screenH)
        assertTrue("height ${layout.first.height}", layout.first.height >= 1036)
        assertEquals(PairFit.MIN_SLICE, layout.leftSlice)
        // 0.88 + 0.88 fits at full height (1900 px of photo, 20 px of free space, a 6 px slice is less than 16: shrinks a hair).
        val almost = fit(88, 100, 88, 100)
        assertValid(almost)
        assertTrue(almost.first.height in 1050..1080)
    }

    @Test
    fun aMismatchedPairOfLandscapesJustShrinksAndStaysInsideTheScreen() {
        val layout = fit(4032, 3024, 4032, 3024)
        assertValid(layout)
        assertTrue(layout.first.height < 800)
        assertTrue(layout.first.top > 100) // centered vertically with black above and below
    }

    @Test
    fun differentSizedBitmapsKeepTheirOwnAspect() {
        // Decoded bitmaps are what counts: 810x1080 beside 360x480 are both 3:4.
        val layout = fit(810, 1080, 360, 480)
        assertValid(layout)
        assertEquals(layout.first.width, layout.second.width)
    }

    @Test
    fun aDifferentScreenSizeScalesTheSame() {
        val layout = PairFit.fit(3, 4, 3, 4, 1280, 720)!!
        assertEquals(PairRect(66, 0, 540, 720), layout.first) // free 200: slices 66, 66, 68
        assertEquals(66, layout.leftSlice)
        assertEquals(68, layout.rightSlice)
    }

    @Test
    fun invalidSizesGiveNoLayout() {
        assertNull(PairFit.fit(0, 4, 3, 4, screenW, screenH))
        assertNull(PairFit.fit(3, 4, 3, -1, screenW, screenH))
        assertNull(PairFit.fit(3, 4, 3, 4, 0, screenH))
        assertNull(PairFit.fit(3, 4, 3, 4, screenW, 0))
    }
}
