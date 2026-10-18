package com.googletv.screensaver.playback

/**
 * Which photos may share a slide in Portrait pairs. The catalog stores the raw pixel size and EXIF
 * orientation of the original file (`ZADDITIONALASSETATTRIBUTES.ZORIGINAL*`), never the edited
 * version's size, because the slideshow shows originals.
 */
internal object PortraitRules {
    /** 9:16 up to a little under square, as width over height after orientation. */
    const val MIN_ASPECT = 0.56
    const val MAX_ASPECT = 0.9

    /** Width over height as displayed. EXIF orientations 5 to 8 turn the image a quarter, which swaps the sides. */
    fun displayedAspect(width: Int, height: Int, orientation: Int?): Double? {
        if (width <= 0 || height <= 0) return null
        return if (orientation in 5..8) height.toDouble() / width else width.toDouble() / height
    }

    fun isPairable(aspect: Double): Boolean = aspect in MIN_ASPECT..MAX_ASPECT

    fun isPairable(width: Int, height: Int, orientation: Int?): Boolean =
        displayedAspect(width, height, orientation)?.let(::isPairable) == true
}
