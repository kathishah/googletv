package com.googletv.screensaver

/** What the in-app slideshow plays. Stored as `media_type`, independent of the Media (All/Albums) choice. */
enum class MediaType(val label: String) {
    PHOTOS("Photos"),
    PHOTOS_AND_VIDEOS("Photos + Videos"),
    VIDEOS("Videos");

    val includesPhotos: Boolean get() = this != VIDEOS
    val includesVideos: Boolean get() = this != PHOTOS

    companion object {
        val all = entries
        val default = PHOTOS

        /** A missing or unknown value is [default], so a bad preference can never hide photos. */
        fun parse(value: String?): MediaType = entries.firstOrNull { it.name == value } ?: default
    }
}

/** What the screensaver (DreamService) shows for a given [MediaType]. It never plays videos. */
enum class DreamPlan {
    /** Honor the saved Media choice, albums, and pool, as before. */
    SAVED_SELECTION,

    /** Ignore albums and the saved pool: a random pool of [MediaTypeRules.DREAM_FALLBACK_POOL] photos from the library. */
    RANDOM_PHOTOS,
}

/** Plain-Kotlin rules for how [MediaType] changes what the settings and the album picker show. */
object MediaTypeRules {
    const val DREAM_FALLBACK_POOL = 500

    fun dreamPlan(type: MediaType): DreamPlan = if (type.includesVideos) DreamPlan.RANDOM_PHOTOS else DreamPlan.SAVED_SELECTION

    /** How many items an album with [photos] and [videos] contributes under [type]. */
    fun eligibleCount(type: MediaType, photos: Int, videos: Int): Int =
        (if (type.includesPhotos) photos else 0) + (if (type.includesVideos) videos else 0)

    /**
     * The count line on an album card. Under [MediaType.PHOTOS] this is exactly the text before videos
     * existed. When nothing in the album is eligible for the other types it says so plainly.
     */
    fun cardCountText(type: MediaType, photos: Int, videos: Int): String = when {
        type == MediaType.PHOTOS -> "$photos pictures"
        isEmptyForType(type, photos, videos) -> if (type == MediaType.VIDEOS) "No videos" else "No pictures or videos"
        type == MediaType.PHOTOS_AND_VIDEOS -> "$photos pictures · ${videoText(videos)}"
        else -> videoText(videos)
    }

    /** True when a card should be dimmed and flagged because nothing in it is eligible. Never for [MediaType.PHOTOS]. */
    fun isEmptyForType(type: MediaType, photos: Int, videos: Int): Boolean =
        type != MediaType.PHOTOS && eligibleCount(type, photos, videos) == 0

    fun showsVideoBadge(type: MediaType, videos: Int): Boolean = type.includesVideos && videos > 0

    /** The unit in "N albums · X ___" at the bottom of the picker. */
    fun totalText(type: MediaType, albums: Int, photos: Int, videos: Int): String = when (type) {
        MediaType.PHOTOS -> "$albums albums  ·  $photos pictures"
        MediaType.PHOTOS_AND_VIDEOS -> "$albums albums  ·  ${photos + videos} items"
        MediaType.VIDEOS -> "$albums albums  ·  ${videoText(videos)}"
    }

    /**
     * The summary shown next to the Media row when Choose Albums is active. A negative [videos] means
     * the video total is not known yet (the selection was saved before video counts existed), so it is
     * left out until the picker has been opened once.
     */
    fun summaryText(type: MediaType, albums: Int, photos: Int, videos: Int): String = when {
        type == MediaType.PHOTOS -> "$albums albums · $photos photos"
        videos < 0 && type == MediaType.PHOTOS_AND_VIDEOS -> "$albums albums · $photos photos"
        videos < 0 -> "$albums albums"
        type == MediaType.PHOTOS_AND_VIDEOS -> "$albums albums · $photos photos · ${videoText(videos)}"
        else -> "$albums albums · ${videoText(videos)}"
    }

    /** Warning for selected albums with nothing eligible, or null when there is nothing to say. */
    fun emptySelectionWarning(type: MediaType, emptyCount: Int): String? = when {
        emptyCount <= 0 -> null
        type == MediaType.PHOTOS -> "$emptyCount selected album(s) have no readable supported pictures. Keep the selection or choose other albums."
        type == MediaType.VIDEOS -> "$emptyCount selected album(s) have no videos. Keep the selection or choose other albums."
        else -> "$emptyCount selected album(s) have no pictures or videos. Keep the selection or choose other albums."
    }

    /** Note under the Type row, or null for [MediaType.PHOTOS]. */
    fun typeNote(type: MediaType): String? =
        if (type.includesVideos) "Videos play in the app slideshow only. The screensaver shows $DREAM_FALLBACK_POOL random photos." else null

    private fun videoText(count: Int): String = if (count == 1) "1 video" else "$count videos"
}
