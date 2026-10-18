package com.googletv.screensaver

import com.googletv.screensaver.playback.VideoItem
import java.security.MessageDigest

/** Plain-Kotlin rules for which cover an album card gets and how it is cached. */
object AlbumCoverRules {
    private const val POSTER_CANDIDATE_POOL = 8
    private const val POSTER_ATTEMPTS = 4

    /**
     * A poster frame from a video is used when Type is Videos, or for an album that has only videos
     * while videos are in scope. Every other album keeps its photo cover, which is unchanged.
     */
    fun usesVideoPoster(type: MediaType, photoCount: Int, videoCount: Int): Boolean =
        videoCount > 0 && type.includesVideos && (type == MediaType.VIDEOS || photoCount == 0)

    /** Up to four videos to try, lightest first among the album's first eight, so a poster is quick to fetch. */
    fun posterCandidates(videos: List<VideoItem>): List<VideoItem> =
        videos.take(POSTER_CANDIDATE_POOL).sortedBy { it.sizeBytes }.take(POSTER_ATTEMPTS)

    /** The key photo covers have always used; unchanged so covers cached before videos existed still hit. */
    fun photoCacheKey(albumId: String, photoPaths: List<String>): String =
        sha256("$albumId\u0000${photoPaths.take(4).joinToString("\u0000")}")

    /** A separate key for posters, so switching Type keeps both kinds of cover. */
    fun posterCacheKey(albumId: String, candidates: List<VideoItem>): String =
        sha256("poster\u0000$albumId\u0000${candidates.joinToString("\u0000") { it.path }}")

    /** The size a [frameWidth] by [frameHeight] frame is scaled to so it fits [targetWidth] by [targetHeight]; never enlarged. */
    fun posterSize(frameWidth: Int, frameHeight: Int, targetWidth: Int, targetHeight: Int): Pair<Int, Int> {
        if (frameWidth <= 0 || frameHeight <= 0) return 1 to 1
        val scale = minOf(1.0, targetWidth.toDouble() / frameWidth, targetHeight.toDouble() / frameHeight)
        return Math.round(frameWidth * scale).toInt().coerceAtLeast(1) to Math.round(frameHeight * scale).toInt().coerceAtLeast(1)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
