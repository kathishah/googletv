package com.googletv.screensaver.playback

import kotlin.math.ceil
import kotlin.random.Random

/** Decides how many photos and videos make up a slideshow pool. Plain Kotlin. */
internal object SlideMixer {
    /**
     * Videos appear in proportion to their share of the library. Returns how many of [poolSize] slots
     * go to videos, at least one when any video exists and at most what exists, and never the whole
     * pool unless there are no photos.
     */
    fun videoSlots(poolSize: Int, libraryVideos: Int, libraryPhotos: Int): Int {
        if (poolSize <= 0 || libraryVideos <= 0) return 0
        if (libraryPhotos <= 0) return minOf(poolSize, libraryVideos)
        val share = libraryVideos.toDouble() / (libraryVideos + libraryPhotos)
        val wanted = ceil(poolSize * share).toInt().coerceAtLeast(1)
        return minOf(wanted, libraryVideos, (poolSize - 1).coerceAtLeast(1))
    }

    /** A random selection of up to [count] videos, without repeats. */
    fun pickVideos(videos: List<VideoItem>, count: Int, random: Random = Random.Default): List<VideoItem> =
        if (count >= videos.size) videos.shuffled(random) else videos.shuffled(random).take(count)

    /** Photos and videos shuffled together. */
    fun <T> shuffleTogether(photos: List<T>, videos: List<T>, random: Random = Random.Default): List<T> =
        (photos + videos).shuffled(random)
}
