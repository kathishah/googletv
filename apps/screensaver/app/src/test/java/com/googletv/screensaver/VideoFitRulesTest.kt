package com.googletv.screensaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoFitRulesTest {
    @Test fun hdClipFillsTheScreenAsBefore() {
        assertEquals(VideoFit(1920, 1080), VideoFitRules.fit(1920, 1080, 1920, 1080))
    }

    @Test fun portraitClipIsFittedByHeight() {
        assertEquals(VideoFit(607, 1080), VideoFitRules.fit(1080, 1920, 1920, 1080))
    }

    @Test fun smallClipIsEnlargedAtMostTwice() {
        assertEquals(VideoFit(1280, 960), VideoFitRules.fit(640, 480, 1920, 1080))
        assertEquals(VideoFit(320, 180), VideoFitRules.fit(160, 90, 1920, 1080))
    }

    @Test fun clipJustUnderTheCapStillFits() {
        // 960x540 doubles to exactly the screen.
        assertEquals(VideoFit(1920, 1080), VideoFitRules.fit(960, 540, 1920, 1080))
    }

    @Test fun largeClipIsShrunkToFit() {
        assertEquals(VideoFit(1440, 1080), VideoFitRules.fit(3840, 2880, 1920, 1080))
    }

    @Test fun unknownSizesDrawNothing() {
        assertNull(VideoFitRules.fit(0, 480, 1920, 1080))
        assertNull(VideoFitRules.fit(640, 480, 0, 0))
    }
}
