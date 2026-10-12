package com.googletv.screensaver.playback

import com.googletv.screensaver.R

data class Slide(
    val imageResource: Int,
    val description: String,
)

/** Supplies displayable images without coupling a source to slideshow timing or rendering. */
fun interface PhotoSource {
    fun loadSlides(): List<Slide>
}

class BundledSamplePhotoSource : PhotoSource {
    override fun loadSlides() = listOf(
        Slide(R.drawable.sample_mountain_lake, "Mountain lake at sunrise"),
        Slide(R.drawable.sample_redwood_forest, "Redwood forest trail"),
    )
}
