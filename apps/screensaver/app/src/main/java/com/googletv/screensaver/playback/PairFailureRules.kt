package com.googletv.screensaver.playback

/**
 * What happens when a pair's photos do not both load (docs/MULTI_IMAGE.md, "States and errors"). Plain
 * Kotlin so the rules run in JVM tests; the player owns the threads and the bitmaps.
 */
internal object PairFailureRules {
    sealed class Outcome {
        /** Both photos decoded: show the pair. */
        object BothLoaded : Outcome()

        /** One photo decoded: show it alone and deal with [failure]. */
        class OneLoaded(val survivorIsFirst: Boolean, val failure: Throwable) : Outcome()

        /** Neither decoded: the whole slide fails with [error]. */
        class NoneLoaded(val error: Throwable) : Outcome()
    }

    /** A network error wins over a per-file one when both fail, so a pair is retried rather than dropped while the share is down. */
    fun classify(firstError: Throwable?, partnerError: Throwable?): Outcome = when {
        firstError == null && partnerError == null -> Outcome.BothLoaded
        firstError == null -> Outcome.OneLoaded(survivorIsFirst = true, failure = partnerError!!)
        partnerError == null -> Outcome.OneLoaded(survivorIsFirst = false, failure = firstError)
        firstError.smbFailureKind() == SmbFailureKind.PER_FILE && partnerError.smbFailureKind() != SmbFailureKind.PER_FILE ->
            Outcome.NoneLoaded(partnerError)
        else -> Outcome.NoneLoaded(firstError)
    }

    /**
     * Un-pairs the slide at [index] in [slides] after one photo failed with [failureKind]. The photo that
     * loaded becomes a single slide in the pair's place and is returned. The failed photo is dropped when
     * it can never load (per-file error) and otherwise goes to the back of the list as a single slide, so a
     * network error costs it nothing.
     */
    fun repair(slides: MutableList<Slide>, index: Int, survivorIsFirst: Boolean, failureKind: SmbFailureKind): Slide {
        val pair = slides[index]
        val partner = checkNotNull(pair.partner) { "Not a pair." }
        val first = pair.copy(partner = null, look = null)
        val second = partner.copy(partner = null, look = null)
        val survivor = if (survivorIsFirst) first else second
        val failed = if (survivorIsFirst) second else first
        slides[index] = survivor
        if (failureKind != SmbFailureKind.PER_FILE) slides.add(failed)
        return survivor
    }
}
