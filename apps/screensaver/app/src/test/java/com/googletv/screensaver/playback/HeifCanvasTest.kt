package com.googletv.screensaver.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeifCanvasTest {
    private val line = "PASS source=4284x5712 grid=5x9 tiles=45 tileSize=896x640 output=1920x1080 content=810x1080+555+0 elapsedMs=6075"

    @Test fun readsThePictureRectangleFromTheResultLine() {
        assertEquals(HeifContent(810, 1080, 555, 0), HeifCanvas.parseContent(line, 1920, 1080))
    }

    @Test fun aLandscapePictureThatFillsTheCanvasIsTheWholeCanvas() {
        assertEquals(HeifContent(1440, 1080, 240, 0), HeifCanvas.parseContent("PASS content=1440x1080+240+0 elapsedMs=1", 1920, 1080))
    }

    @Test fun anOlderDecoderLineWithoutARectangleGivesNull() {
        assertNull(HeifCanvas.parseContent("PASS source=4284x5712 output=1920x1080 elapsedMs=6075", 1920, 1080))
    }

    @Test fun aRectangleOutsideTheCanvasOrEmptyGivesNull() {
        assertNull(HeifCanvas.parseContent("PASS content=810x1080+1200+0", 1920, 1080))
        assertNull(HeifCanvas.parseContent("PASS content=0x1080+0+0", 1920, 1080))
        assertNull(HeifCanvas.parseContent("PASS content=810x1081+0+0", 1920, 1080))
        assertNull(HeifCanvas.parseContent("PASS content=99999999999x1+0+0", 1920, 1080))
    }
}
