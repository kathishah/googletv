package com.googletv.screensaver.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException

class PairFailureRulesTest {
    private fun photo(id: String) = Slide(id, "Photo $id")
    private fun pair(a: String, b: String) = photo(a).copy(partner = photo(b), look = PairLook(PairStyle.WALNUT_FRAME, 1))
    private val unreadable = IOException("cannot decode") // per-file: a photo that can never load
    private val network = SocketException("reset") // transient: says nothing about the photo

    @Test fun bothLoadedIsAPair() {
        assertTrue(PairFailureRules.classify(null, null) === PairFailureRules.Outcome.BothLoaded)
    }

    @Test fun theSurvivorIsWhicheverPhotoLoaded() {
        val firstFailed = PairFailureRules.classify(unreadable, null) as PairFailureRules.Outcome.OneLoaded
        assertEquals(false, firstFailed.survivorIsFirst)
        assertSame(unreadable, firstFailed.failure)
        val partnerFailed = PairFailureRules.classify(null, network) as PairFailureRules.Outcome.OneLoaded
        assertEquals(true, partnerFailed.survivorIsFirst)
        assertSame(network, partnerFailed.failure)
    }

    @Test fun whenNeitherLoadsANetworkErrorWinsSoThePairIsRetried() {
        assertSame(network, (PairFailureRules.classify(unreadable, network) as PairFailureRules.Outcome.NoneLoaded).error)
        assertSame(network, (PairFailureRules.classify(network, unreadable) as PairFailureRules.Outcome.NoneLoaded).error)
        assertSame(unreadable, (PairFailureRules.classify(unreadable, IOException("also bad")) as PairFailureRules.Outcome.NoneLoaded).error)
    }

    @Test fun anUnreadablePartnerIsDroppedAndTheFirstPhotoStaysAsASingle() {
        val slides = mutableListOf(photo("x"), pair("a", "b"), photo("y"))
        val shown = PairFailureRules.repair(slides, 1, survivorIsFirst = true, failureKind = SmbFailureKind.PER_FILE)
        assertEquals("a", shown.id)
        assertNull(shown.partner)
        assertNull(shown.look)
        assertEquals(listOf("x", "a", "y"), slides.map { it.id })
        assertTrue(slides.all { it.partner == null })
    }

    @Test fun anUnreadableFirstPhotoIsDroppedAndThePartnerTakesItsPlace() {
        val slides = mutableListOf(pair("a", "b"), photo("y"))
        val shown = PairFailureRules.repair(slides, 0, survivorIsFirst = false, failureKind = SmbFailureKind.PER_FILE)
        assertEquals("b", shown.id)
        assertEquals(listOf("b", "y"), slides.map { it.id })
    }

    @Test fun aNetworkFailureSendsThePhotoToTheBackAsASingle() {
        val slides = mutableListOf(pair("a", "b"), photo("y"), photo("z"))
        val shown = PairFailureRules.repair(slides, 0, survivorIsFirst = true, failureKind = SmbFailureKind.TRANSIENT)
        assertEquals("a", shown.id)
        assertEquals(listOf("a", "y", "z", "b"), slides.map { it.id })
        assertNull(slides.last().partner)
        assertNull(slides.last().look)
    }

    @Test fun aConfigurationErrorIsKeptLikeANetworkError() {
        val slides = mutableListOf(pair("a", "b"))
        PairFailureRules.repair(slides, 0, survivorIsFirst = false, failureKind = SmbFailureKind.CONFIGURATION)
        assertEquals(listOf("b", "a"), slides.map { it.id })
    }

    @Test fun repairingASingleSlideIsAnError() {
        val slides = mutableListOf(photo("a"))
        try {
            PairFailureRules.repair(slides, 0, true, SmbFailureKind.PER_FILE)
            org.junit.Assert.fail("expected an error")
        } catch (_: IllegalStateException) {
            // expected
        }
    }
}
