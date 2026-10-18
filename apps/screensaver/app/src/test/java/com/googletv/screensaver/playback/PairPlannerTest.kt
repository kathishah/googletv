package com.googletv.screensaver.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PairPlannerTest {
    private fun photo(id: String) = Slide(id, "")
    private fun video(id: String) = Slide(id, "", SlideKind.VIDEO, VideoItem("0/$id.mov", 10.0, 1_000_000L))

    // Photos whose id starts with "p" are portrait; everything else is not pairable.
    private val portraitIfP: (Slide) -> Boolean = { it.id.startsWith("p") }

    private fun plan(slides: List<Slide>, probability: Double = 1.0, seed: Int = 1, styles: List<PairStyle> = PairPlanner.BACKGROUND_STYLES) =
        PairPlanner.plan(slides, portraitIfP, Random(seed), probability, styles)

    private fun portraits(count: Int) = (1..count).map { photo("p$it") }

    /** Every photo of the input appears exactly once in the plan, as a slide or as a partner. */
    private fun assertEveryPhotoOnce(input: List<Slide>, planned: List<Slide>) {
        val ids = planned.flatMap { listOfNotNull(it.id, it.partner?.id) }
        assertEquals(input.map { it.id }.sorted(), ids.sorted())
    }

    @Test
    fun probabilityOneNeverLeavesTwoCandidatesApartAndPairsAllButAnOddOne() {
        val input = portraits(7)
        val planned = plan(input)
        assertEveryPhotoOnce(input, planned)
        assertEquals(4, planned.size) // 3 pairs and one odd single
        assertEquals(3, planned.count { it.partner != null })
        assertNull(planned.last().partner)
        assertNull(planned.last().look)
    }

    @Test
    fun probabilityZeroGivesNoPairs() {
        val input = portraits(20)
        val planned = plan(input, probability = 0.0)
        assertEquals(input, planned)
        assertTrue(planned.all { it.partner == null && it.look == null })
    }

    @Test
    fun theFirstPhotoOfAPairIsOnTheLeftInShuffleOrder() {
        val planned = plan(portraits(4))
        assertEquals(listOf("p1", "p3"), planned.map { it.id })
        assertEquals(listOf("p2", "p4"), planned.map { it.partner!!.id })
    }

    @Test
    fun aPairAppearsWhereItsFirstPhotoLanded() {
        val input = listOf(photo("l1"), photo("p1"), photo("l2"), photo("l3"), photo("p2"), photo("l4"))
        val planned = plan(input)
        assertEquals(listOf("l1", "p1", "l2", "l3", "l4"), planned.map { it.id })
        assertEquals("p2", planned[1].partner!!.id)
        assertEveryPhotoOnce(input, planned)
    }

    @Test
    fun nonPairablePhotosAndVideosStaySingleInPlace() {
        val input = listOf(photo("l1"), video("v1"), photo("p1"), video("v2"), photo("p2"), photo("l2"))
        val planned = plan(input)
        assertEquals(listOf("l1", "v1", "p1", "v2", "l2"), planned.map { it.id })
        assertEquals(SlideKind.VIDEO, planned[1].kind)
        assertNull(planned[1].partner)
        assertNull(planned[3].partner)
        assertEquals("p2", planned[2].partner!!.id)
    }

    @Test
    fun aVideoIsNeverPairedEvenIfTheCatalogWouldCallItPairable() {
        val input = listOf(video("p-clip1"), video("p-clip2"), photo("p1"))
        val planned = plan(input)
        assertEquals(input, planned)
    }

    @Test
    fun aPoolWithoutPairablePhotosIsUnchanged() {
        val input = listOf(photo("l1"), photo("l2"), video("v1"), photo("l3"))
        assertEquals(input, plan(input))
        assertEquals(emptyList<Slide>(), plan(emptyList()))
    }

    @Test
    fun aSinglePortraitStaysSingle() {
        assertEquals(listOf(photo("p1")), plan(listOf(photo("p1"))))
    }

    @Test
    fun oddCountsEndWithOneSingle() {
        for (count in listOf(1, 3, 5, 9)) {
            val planned = plan(portraits(count))
            assertEquals(count / 2, planned.count { it.partner != null })
            assertEquals(count / 2 + 1, planned.size)
        }
        for (count in listOf(2, 4, 10)) {
            assertEquals(count / 2, plan(portraits(count)).size)
        }
    }

    @Test
    fun theSameSeedGivesTheSamePlanAndAnotherSeedUsuallyDoesNot() {
        val input = portraits(60) + (1..40).map { photo("l$it") }
        assertEquals(plan(input, 0.6, seed = 42), plan(input, 0.6, seed = 42))
        assertNotEquals(plan(input, 0.6, seed = 42), plan(input, 0.6, seed = 43))
    }

    @Test
    fun noSlideIsLostOrDuplicatedAtThePlanningProbability() {
        for (seed in 1..20) {
            val input = (portraits(80) + (1..50).map { photo("l$it") } + (1..10).map { video("v$it") }).shuffled(Random(seed))
            assertEveryPhotoOnce(input, PairPlanner.plan(input, portraitIfP, Random(seed)))
        }
    }

    @Test
    fun aboutSixtyPercentOfPortraitsEndUpPairedOverTenThousandPhotos() {
        val random = Random(2026)
        val input = (1..10_000).map { if (random.nextInt(3) == 0) photo("p$it") else photo("l$it") }
        val planned = PairPlanner.plan(input, portraitIfP, Random(7))
        val portraitCount = input.count { it.id.startsWith("p") }
        val paired = planned.count { it.partner != null } * 2
        val share = paired.toDouble() / portraitCount
        assertTrue("paired share $share", share in 0.58..0.62)
        // The slides: portraits alone + pairs + landscapes.
        assertEquals(input.size - planned.count { it.partner != null }, planned.size)
    }

    @Test
    fun aPairHasALookAndASingleDoesNot() {
        val planned = plan(portraits(6) + listOf(photo("l1")))
        planned.forEach { slide ->
            if (slide.partner != null) assertTrue(slide.look != null) else assertNull(slide.look)
        }
    }

    @Test
    fun consecutivePairsNeverShareAStyle() {
        for (seed in 1..200) {
            val planned = plan(portraits(40), seed = seed)
            val styles = planned.mapNotNull { it.look?.style }
            styles.zipWithNext().forEach { (a, b) -> assertNotEquals("seed $seed", a, b) }
            // The player loops the list, so the last pair also differs from the first.
            if (styles.size > 1) assertNotEquals("wrap, seed $seed", styles.last(), styles.first())
        }
    }

    @Test
    fun everyStyleAndVariantEventuallyAppearsAndVariantsStayInRange() {
        val looks = (1..100).flatMap { seed -> plan(portraits(40), seed = seed).mapNotNull { it.look } }
        assertEquals(PairStyle.entries.toSet(), looks.map { it.style }.toSet())
        assertEquals((0 until PairLook.VARIANTS).toSet(), looks.map { it.variant }.toSet())
    }

    @Test
    fun aSingleAllowedStyleRepeatsAndNoStylesMeansBlack() {
        val one = plan(portraits(10), styles = listOf(PairStyle.WALNUT_FRAME))
        assertTrue(one.mapNotNull { it.look?.style }.all { it == PairStyle.WALNUT_FRAME })
        val none = plan(portraits(10), styles = emptyList())
        assertEquals(5, none.size)
        assertTrue(none.all { it.partner != null && it.look == null })
    }

    @Test
    fun twoAllowedStylesAlternate() {
        val styles = listOf(PairStyle.PINSTRIPES, PairStyle.BRASS_FILLET_MAT)
        val planned = plan(portraits(12), styles = styles) // 6 pairs: an even count, so the wrap matches too
        val seen = planned.mapNotNull { it.look?.style }
        assertEquals(6, seen.size)
        seen.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
        assertNotEquals(seen.first(), seen.last())
    }

    @Test
    fun aPhotoListedTwiceIsNeverItsOwnPartner() {
        val input = listOf(photo("p1"), photo("p1"), photo("p2"))
        val planned = plan(input)
        assertTrue(planned.none { it.partner != null && it.partner!!.id == it.id })
        assertEveryPhotoOnce(input, planned)
    }

    @Test
    fun theCoinIsFlippedOnlyForPairablePhotos() {
        // A non-pairable photo must not consume the random stream, or inserting landscapes would reshuffle the pairs.
        val onlyPortraits = portraits(30)
        val withLandscapes = onlyPortraits.flatMap { listOf(it, photo("l${it.id}")) }
        val a = plan(onlyPortraits, probability = 0.6, seed = 9).filter { it.partner != null }.map { it.id to it.partner!!.id }
        val b = plan(withLandscapes, probability = 0.6, seed = 9).filter { it.partner != null }.map { it.id to it.partner!!.id }
        assertEquals(a, b)
    }

    @Test
    fun slidesWithAPartnerAlreadySetAreLeftAlone() {
        val done = photo("p1").copy(partner = photo("p2"), look = PairLook(PairStyle.PINSTRIPES, 0))
        val planned = plan(listOf(done, photo("p3"), photo("p4")))
        assertEquals(done, planned.first())
        assertFalse(planned.drop(1).any { it.id == "p1" })
    }
}
