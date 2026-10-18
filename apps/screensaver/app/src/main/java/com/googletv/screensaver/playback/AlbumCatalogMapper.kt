package com.googletv.screensaver.playback

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Path rules shared by the catalog builder and reader. Paths are relative to `originals`. */
internal object CatalogPaths {
    val photoExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    val videoExtensions = setOf("mov", "mp4", "m4v", "3gp", "3g2", "avi", "mpg", "mpeg", "mts", "m2ts")

    fun safePhotoPath(directory: String?, filename: String?): String? = safePath(directory, filename, photoExtensions)

    fun safeVideoPath(directory: String?, filename: String?): String? = safePath(directory, filename, videoExtensions)

    fun isSafePhotoPath(path: String): Boolean = isSafe(path, photoExtensions)

    fun isSafeVideoPath(path: String): Boolean = isSafe(path, videoExtensions)

    private fun safePath(directory: String?, filename: String?, extensions: Set<String>): String? {
        if (directory == null || filename == null) return null
        val relative = "${directory.replace('\\', '/')}/${filename.replace('\\', '/')}"
        return relative.takeIf { isSafe(it, extensions) }
    }

    private fun isSafe(path: String, extensions: Set<String>): Boolean {
        val parts = path.replace('\\', '/').split('/')
        val extension = parts.lastOrNull()?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return path.isNotBlank() && !path.startsWith('/') && parts.none { it.isBlank() || it == "." || it == ".." } &&
            extension in extensions
    }
}

/** One row of the album-membership query. Every column can be null (outer joins). */
internal data class AlbumRow(
    val albumId: String?,
    val albumTitle: String?,
    val albumStart: Double?,
    val albumEnd: Double?,
    val assetKind: Int?,
    val directory: String?,
    val filename: String?,
)

/** One row of the library-wide video query (active, non-hidden videos only; the SQL filters those). */
internal data class VideoRow(
    val directory: String?,
    val filename: String?,
    val durationSeconds: Double?,
    val sizeBytes: Long?,
)

/**
 * Turns query rows into an [AlbumIndex]. Plain Kotlin so the rules can be tested on the JVM; the
 * SQL itself lives in [PhotosAlbumCatalog].
 *
 * Photos keep the catalog v1 rules (asset kind 0 or 1, supported photo extension). A video is an
 * asset of kind 1 with a video extension whose path appears in the library-wide video list, which
 * holds only active, non-hidden videos with a positive duration and size. An album's videos are
 * resolved against that list when the index is built, so the order of calls does not matter and the
 * album query never needs duration or size. Live Photo motion clips are not separate asset rows, so
 * they never appear here.
 */
internal class AlbumCatalogMapper(private val zone: ZoneId = ZoneId.systemDefault()) {
    private class MutableAlbum(val id: String, val title: String, val start: Double?, val end: Double?) {
        val paths = linkedSetOf<String>()
        val videoPaths = linkedSetOf<String>()
    }

    private val albums = linkedMapOf<String, MutableAlbum>()
    private val libraryVideos = linkedMapOf<String, VideoItem>()
    private val excludedPhotos = linkedSetOf<String>()

    fun addAlbumRow(row: AlbumRow) {
        val id = row.albumId ?: return
        val album = albums.getOrPut(id) {
            MutableAlbum(id, row.albumTitle?.takeIf { it.isNotBlank() } ?: "Untitled album", row.albumStart, row.albumEnd)
        }
        val kind = row.assetKind ?: return
        if (kind !in 0..1) return
        CatalogPaths.safePhotoPath(row.directory, row.filename)?.let {
            album.paths.add(it)
            return
        }
        if (kind != 1) return
        CatalogPaths.safeVideoPath(row.directory, row.filename)?.let { album.videoPaths.add(it) }
    }

    fun addLibraryVideo(row: VideoRow) {
        videoItem(row.directory, row.filename, row.durationSeconds, row.sizeBytes)?.let { libraryVideos.putIfAbsent(it.path, it) }
    }

    /** A photo the slideshow must never show (screenshot, document, receipt). The SQL decides which. */
    fun addExcludedPhoto(directory: String?, filename: String?) {
        CatalogPaths.safePhotoPath(directory, filename)?.let { excludedPhotos.add(it) }
    }

    /** Excluded photos are dropped from every album here, so album counts and covers never include them. */
    fun build(createdAtMillis: Long): AlbumIndex {
        val exclusion = PhotoExclusion(excludedPhotos)
        return AlbumIndex(
            albums = albums.values.map { album ->
                val date = listOfNotNull(album.start, album.end).maxOrNull()
                val videos = album.videoPaths.mapNotNull { libraryVideos[it] }
                AlbumEntry(album.id, album.title, date?.let(::yearMonth), album.paths.filter { it !in exclusion }, videos)
            },
            createdAtMillis = createdAtMillis,
            videos = libraryVideos.values.toList(),
            excludedPaths = excludedPhotos.toSet(),
        )
    }

    private fun videoItem(directory: String?, filename: String?, duration: Double?, size: Long?): VideoItem? {
        val path = CatalogPaths.safeVideoPath(directory, filename) ?: return null
        if (duration == null || !duration.isFinite() || duration <= 0.0 || size == null || size <= 0L) return null
        return VideoItem(path, Math.round(duration * 1000) / 1000.0, size)
    }

    private fun yearMonth(cocoaSeconds: Double): String? = runCatching {
        yearMonthFormatter.format(Instant.ofEpochMilli(((cocoaSeconds + COCOA_EPOCH_SECONDS) * 1_000).toLong()).atZone(zone))
    }.getOrNull()

    private companion object {
        const val COCOA_EPOCH_SECONDS = 978_307_200.0
        val yearMonthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    }
}
