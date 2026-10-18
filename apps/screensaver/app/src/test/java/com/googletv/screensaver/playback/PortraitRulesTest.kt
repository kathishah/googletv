package com.googletv.screensaver.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitRulesTest {
    @Test
    fun orientationsFiveToEightSwapTheSides() {
        assertEquals(0.75, PortraitRules.displayedAspect(3024, 4032, 1)!!, 1e-9)
        for (orientation in listOf(2, 3, 4)) {
            assertEquals(0.75, PortraitRules.displayedAspect(3024, 4032, orientation)!!, 1e-9)
        }
        // Stored 4032 x 3024 (4:3) but turned a quarter: shown 3:4.
        for (orientation in 5..8) {
            assertEquals(0.75, PortraitRules.displayedAspect(4032, 3024, orientation)!!, 1e-9)
        }
        assertEquals(4.0 / 3, PortraitRules.displayedAspect(4032, 3024, 1)!!, 1e-9)
    }

    @Test
    fun aMissingOrUnknownOrientationMeansAsStored() {
        assertEquals(0.75, PortraitRules.displayedAspect(3024, 4032, null)!!, 1e-9)
        assertEquals(0.75, PortraitRules.displayedAspect(3024, 4032, 0)!!, 1e-9)
        assertEquals(0.75, PortraitRules.displayedAspect(3024, 4032, 9)!!, 1e-9)
    }

    @Test
    fun sizesThatAreNotPositiveHaveNoAspect() {
        assertNull(PortraitRules.displayedAspect(0, 100, 1))
        assertNull(PortraitRules.displayedAspect(100, -1, 6))
        assertFalse(PortraitRules.isPairable(0, 100, 1))
    }

    @Test
    fun theBandIsInclusiveAtBothEnds() {
        assertTrue(PortraitRules.isPairable(0.56))
        assertTrue(PortraitRules.isPairable(0.9))
        assertTrue(PortraitRules.isPairable(9, 16, 1)) // 0.5625, 9:16
        assertTrue(PortraitRules.isPairable(9, 10, 1)) // 0.9
        assertFalse(PortraitRules.isPairable(0.5599))
        assertFalse(PortraitRules.isPairable(0.9001))
        assertFalse(PortraitRules.isPairable(1.0))
        assertFalse(PortraitRules.isPairable(4, 3, 1))
    }

    @Test
    fun commonCameraShapes() {
        assertTrue(PortraitRules.isPairable(3024, 4032, 1)) // 3:4
        assertTrue(PortraitRules.isPairable(2, 3, 1)) // 2:3
        assertTrue(PortraitRules.isPairable(4, 5, 1)) // 4:5
        assertFalse(PortraitRules.isPairable(1080, 2400, 1)) // 0.45, taller than 9:16
        assertFalse(PortraitRules.isPairable(4032, 3024, 1))
        assertTrue(PortraitRules.isPairable(4032, 3024, 6))
        assertFalse(PortraitRules.isPairable(3024, 4032, 6))
    }
}
