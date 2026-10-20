package com.googletv.screensaver.playback

import kotlin.random.Random

/** The four backgrounds a pair can have behind its photos (see docs/PRODUCT.md, Portrait pairs, "Three-slice background"). */
enum class PairStyle(val label: String) {
    PINSTRIPES("Pinstripes"),
    DIAMOND_LATTICE("Diamond lattice"),
    WALNUT_FRAME("Walnut frame"),
    BRASS_FILLET_MAT("Brass fillet mat"),
}

/**
 * The look of one pair: a [style] and a [variant] (0 until [VARIANTS]). Chosen when the pair is formed
 * and fixed for the life of the slide. What a variant means is the renderer's business: pinstripes pitch
 * 8, 10, or 14 px; lattice small, medium, or large; frame walnut, oak, or cherry; mat brass on charcoal,
 * silver on navy, or brass on navy.
 */
data class PairLook(val style: PairStyle, val variant: Int) {
    init {
        require(variant in 0 until VARIANTS)
    }

    companion object {
        const val VARIANTS = 3
    }
}

/**
 * Turns a shuffled slide list into the slide list the player runs, with some portrait photos paired.
 * Plain Kotlin with the random source passed in, so tests can fix it.
 *
 * One pass over the shuffled slides:
 * 1. A slide that is not a pairable photo (a video, a landscape, anything the catalog does not list as
 *    portrait) stays a single slide where it is.
 * 2. A pairable photo flips a coin: with probability [pairProbability] it is a pair *candidate*, otherwise
 *    it stays a single slide, alone on black.
 * 3. The first candidate becomes a single slide for now and waits; the next candidate is pulled forward
 *    into that slide, which becomes a pair (the waiting photo on the left). Then the slot is free again.
 * 4. A candidate that never finds a partner (an odd count at the end) stays single.
 *
 * A pair's [PairLook] is chosen when it is formed, never the same style as the previous pair's. The list
 * is read as a loop by the player, so the last pair is also kept from matching the first.
 */
object PairPlanner {
    /** How likely a pairable photo is to become a pair candidate. A constant, not a setting, in v1. */
    const val PAIR_PROBABILITY = 0.6

    /** The styles in play. Empty = pairs get a black background (the renderer's fallback); one entry = it repeats. */
    val BACKGROUND_STYLES: List<PairStyle> = PairStyle.entries

    fun plan(
        slides: List<Slide>,
        isPairable: (Slide) -> Boolean,
        random: Random = Random.Default,
        pairProbability: Double = PAIR_PROBABILITY,
        styles: List<PairStyle> = BACKGROUND_STYLES,
    ): List<Slide> {
        val planned = ArrayList<Slide>(slides.size)
        var waiting = -1 // index in [planned] of the candidate waiting for a partner
        val pairSlots = ArrayList<Int>()
        var previousStyle: PairStyle? = null
        for (slide in slides) {
            if (slide.kind != SlideKind.PHOTO || slide.partner != null || !isPairable(slide) ||
                random.nextDouble() >= pairProbability
            ) {
                planned += slide
                continue
            }
            if (waiting < 0) {
                waiting = planned.size
                planned += slide
            } else if (planned[waiting].id == slide.id) {
                // The same photo listed twice is never its own partner; it takes its own slot.
                planned += slide
            } else {
                val look = chooseLook(random, previousStyle, styles)
                previousStyle = look?.style
                planned[waiting] = planned[waiting].copy(partner = slide, look = look)
                pairSlots += waiting
                waiting = -1
            }
        }
        separateWrapAround(planned, pairSlots, random, styles)
        return planned
    }

    /** A random style other than [previous] (unless it is the only one) and a random variant; null when no style is allowed. */
    fun chooseLook(random: Random, previous: PairStyle?, styles: List<PairStyle> = BACKGROUND_STYLES): PairLook? {
        if (styles.isEmpty()) return null
        val choices = styles.filter { it != previous }.ifEmpty { styles }
        return PairLook(choices[random.nextInt(choices.size)], random.nextInt(PairLook.VARIANTS))
    }

    // The player loops the list, so the last pair's neighbor is the first one. Re-pick the first pair's
    // style so it differs from both the last pair's and its own successor's.
    private fun separateWrapAround(planned: MutableList<Slide>, pairSlots: List<Int>, random: Random, styles: List<PairStyle>) {
        if (pairSlots.size < 2) return
        val first = planned[pairSlots.first()]
        val firstLook = first.look ?: return
        val lastStyle = planned[pairSlots.last()].look?.style
        if (firstLook.style != lastStyle) return
        val secondStyle = planned[pairSlots[1]].look?.style
        val choices = styles.filter { it != lastStyle && it != secondStyle }.ifEmpty { styles.filter { it != lastStyle } }
        if (choices.isEmpty()) return
        planned[pairSlots.first()] = first.copy(look = PairLook(choices[random.nextInt(choices.size)], firstLook.variant))
    }
}
