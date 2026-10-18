package com.googletv.screensaver.playback

/** A photo's rectangle on screen, in pixels from the top-left corner. */
data class PairRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

/**
 * Where the two photos of a pair go: [first] on the left, [second] on the right, and the three equal
 * slices of free space (left of the first photo, between the photos, right of the second) the
 * background shows through. The slices add up to the screen width minus the two photos.
 */
data class PairLayout(
    val first: PairRect,
    val second: PairRect,
    val leftSlice: Int,
    val middleSlice: Int,
    val rightSlice: Int,
) {
    /** The share of a [screenWidth] x [screenHeight] screen the two photos cover. */
    fun photoFill(screenWidth: Int, screenHeight: Int): Double =
        (first.width.toLong() * first.height + second.width.toLong() * second.height).toDouble() / (screenWidth.toLong() * screenHeight)
}

object PairFit {
    /** The least free space left at each of the three slices, so the photos never touch each other or the screen edge. */
    const val MIN_SLICE = 16

    /**
     * Both photos are drawn at one height H, never cropped, side by side. H is the screen height unless
     * the two photos are so wide together that they would leave less than [minSlice] per slice; then H
     * shrinks to fit. The sizes are the decoded bitmaps' real sizes, so a photo whose catalog flag was
     * wrong simply shrinks the pair. The free space is split in three equal slices (the odd pixels go to
     * the right one), and the group is centered vertically. Returns null for a size that is not positive.
     */
    fun fit(
        firstWidth: Int, firstHeight: Int,
        secondWidth: Int, secondHeight: Int,
        screenWidth: Int, screenHeight: Int,
        minSlice: Int = MIN_SLICE,
    ): PairLayout? {
        if (firstWidth <= 0 || firstHeight <= 0 || secondWidth <= 0 || secondHeight <= 0 ||
            screenWidth <= 0 || screenHeight <= 0
        ) return null
        val firstAspect = firstWidth.toDouble() / firstHeight
        val secondAspect = secondWidth.toDouble() / secondHeight
        val fitHeight = (screenWidth - 3 * minSlice) / (firstAspect + secondAspect)
        val height = minOf(screenHeight.toDouble(), fitHeight).toInt().coerceAtLeast(1)
        val width1 = Math.round(firstAspect * height).toInt().coerceAtLeast(1)
        val width2 = Math.round(secondAspect * height).toInt().coerceAtLeast(1)
        val free = (screenWidth - width1 - width2).coerceAtLeast(0)
        val slice = free / 3
        val top = (screenHeight - height) / 2
        return PairLayout(
            first = PairRect(slice, top, width1, height),
            second = PairRect(2 * slice + width1, top, width2, height),
            leftSlice = slice,
            middleSlice = slice,
            rightSlice = free - 2 * slice,
        )
    }
}
