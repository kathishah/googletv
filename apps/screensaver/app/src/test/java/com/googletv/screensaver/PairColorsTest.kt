package com.googletv.screensaver

import com.googletv.screensaver.playback.PairLook
import com.googletv.screensaver.playback.PairStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairColorsTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun value(color: Int) = PairColors.toHsv(color).third
    private fun hue(color: Int) = PairColors.toHsv(color).first

    @Test
    fun meanAveragesChannels() {
        assertEquals(rgb(50, 100, 150), PairColors.mean(intArrayOf(rgb(0, 0, 0), rgb(100, 200, 255), rgb(50, 100, 195))))
        assertEquals(rgb(128, 128, 128), PairColors.mean(IntArray(0)))
    }

    @Test
    fun mixIsTheMidpoint() {
        assertEquals(rgb(150, 100, 50), PairColors.mix(rgb(200, 0, 0), rgb(100, 200, 100)))
    }

    @Test
    fun hsvRoundTripsPrimaryAndMixedColors() {
        for (color in listOf(rgb(255, 0, 0), rgb(0, 255, 0), rgb(0, 0, 255), rgb(30, 60, 90), rgb(200, 150, 100), rgb(10, 10, 10), rgb(255, 255, 255))) {
            val (h, s, v) = PairColors.toHsv(color)
            val back = PairColors.fromHsv(h, s, v)
            for (shift in listOf(16, 8, 0)) assertEquals(color shr shift and 0xFF, (back shr shift and 0xFF), )
        }
    }

    @Test
    fun theTonesAreDarkAndQuietWhateverThePhotosLookLike() {
        val samples = listOf(rgb(255, 255, 255), rgb(0, 0, 0), rgb(255, 0, 0), rgb(120, 130, 125), rgb(30, 60, 200), rgb(220, 200, 40))
        for (a in samples) for (b in samples) {
            val tones = PairColors.tones(a, b)
            assertEquals("base value", 0.20f, value(tones.base), 0.01f)
            assertEquals("line value", 0.32f, value(tones.line), 0.01f)
            assertTrue(value(tones.line) > value(tones.base))
        }
    }

    @Test
    fun theTonesKeepTheHueOfTheMixedPhotos() {
        val tones = PairColors.tones(rgb(160, 40, 40), rgb(200, 60, 60)) // both red
        assertEquals(0f, hue(tones.base).let { if (it > 180f) it - 360f else it }, 4f)
        val blue = PairColors.tones(rgb(30, 60, 200), rgb(30, 60, 200))
        assertEquals(PairColors.toHsv(rgb(30, 60, 200)).first, hue(blue.base), 4f)
        assertEquals(hue(blue.base), hue(blue.line), 4f)
    }

    @Test
    fun aGreyPairStillGetsAFaintTint() {
        val tones = PairColors.tones(rgb(128, 128, 128), rgb(128, 128, 128))
        assertTrue(PairColors.toHsv(tones.base).second >= 0.11f)
    }

    @Test
    fun everyStyleHasExactlyTheVariantsThePlannerCanPick() {
        assertEquals(PairLook.VARIANTS, PairVariants.stripePitch.size)
        assertEquals(PairLook.VARIANTS, PairVariants.latticePitch.size)
        assertEquals(PairLook.VARIANTS, PairVariants.woods.size)
        assertEquals(PairLook.VARIANTS, PairVariants.mats.size)
        for (style in PairStyle.entries) for (variant in 0 until PairLook.VARIANTS) {
            assertTrue(PairVariants.describe(PairLook(style, variant)).startsWith(style.label))
        }
    }

    @Test
    fun theVariantsDifferFromEachOther() {
        assertEquals(PairLook.VARIANTS, PairVariants.stripePitch.toSet().size)
        assertEquals(PairLook.VARIANTS, PairVariants.latticePitch.toSet().size)
        assertEquals(PairLook.VARIANTS, PairVariants.woods.map { it.center }.toSet().size)
        assertEquals(PairLook.VARIANTS, PairVariants.mats.map { it.mat to it.outer }.toSet().size)
    }
}
