package com.googletv.screensaver

import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutModeTest {
    @Test fun singleIsTheDefault() {
        assertEquals(LayoutMode.SINGLE, LayoutMode.default)
    }

    @Test fun aMissingOrUnknownValueIsSingle() {
        assertEquals(LayoutMode.SINGLE, LayoutMode.parse(null))
        assertEquals(LayoutMode.SINGLE, LayoutMode.parse(""))
        assertEquals(LayoutMode.SINGLE, LayoutMode.parse("TILES"))
        assertEquals(LayoutMode.SINGLE, LayoutMode.parse("portrait_pairs")) // names are exact
    }

    @Test fun storedNamesRoundTrip() {
        for (mode in LayoutMode.all) assertEquals(mode, LayoutMode.parse(mode.name))
        assertEquals("PORTRAIT_PAIRS", LayoutMode.PORTRAIT_PAIRS.name)
    }

    @Test fun thereAreExactlyTwoChoicesInOrder() {
        assertEquals(listOf("Single", "Portrait pairs"), LayoutMode.all.map { it.label })
    }
}
