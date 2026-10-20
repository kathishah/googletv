package com.googletv.screensaver.playback

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Builds and validates the TV-private metadata catalog from a staged Photos database. */
internal object PhotosAlbumCatalog {
    // Bump when the query, path mapping, or JSON layout changes, so a saved catalog is never kept
    // for a newer build. A catalog with any other version is rejected and rebuilt on the next refresh.
    // Version 2 added per-album videos, `videoCount`, and a library-wide `videos` list.
    // Version 3 leaves shared-album copies (ZSAVEDASSETTYPE 4) out of the video list: their files are not
    // in `originals`, so about 18% of version 2's videos could never play.
    // Version 4 adds `excluded`, the photos the slideshow never shows (screenshots, documents,
    // receipts), and drops them from every album's `paths`.
    // Version 5 adds `portrait`, the photos whose displayed shape allows pairing two on one slide.
    // Version 6 retains exclusions for trashed assets because their originals can remain on disk.
    // Version 7 binds a saved catalog to the exact committed database snapshot it was built from.
    // Version 8 binds the catalog to one private SMB source identity.
    internal const val VERSION = 8
    private const val PATH_ROOT = "Photos Library.photoslibrary/originals"

    /** `ZASSET.ZSAVEDASSETTYPE` of assets that come from iCloud Shared Albums rather than the user's own originals. */
    private const val SHARED_ASSET_TYPE = 4

    // Exclusion rules, from Photos' own classification (see journal/2026-10-18-1219-excluding-
    // screenshots-documents-receipts.md). `ZSCENECLASSIFICATION` rows of type 0 carry a scene id and a
    // confidence per asset. Ids 492 (document) and 1086 (receipt) are the same in Apple's scene taxonomy
    // versions 84 and 99. Sampled on this library, document confidence of 0.4 and up was about 90% to
    // 100% real documents; 0.2 to 0.4 was about half family photos, so it is not excluded.
    private const val SCENE_DOCUMENT = 492
    private const val SCENE_RECEIPT = 1086
    private const val MIN_DOCUMENT_CONFIDENCE = 0.4
    private const val MIN_RECEIPT_CONFIDENCE = 0.19

    fun read(file: File, expectedSourceId: String? = null): AlbumIndex? {
        val parsed = try {
            if (!file.isFile) return null
            parse(file.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            if (error is InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            }
            null
        }
        return parsed?.takeIf { expectedSourceId == null || it.sourceId == expectedSourceId }
    }

    /** Parses and validates catalog JSON; throws if anything is wrong. */
    internal fun parse(json: String): AlbumIndex {
        val root = JSONObject(json)
        require(root.optInt("version", -1) == VERSION)
        require(root.optString("pathRoot") == PATH_ROOT)
        val createdAt = root.optLong("createdAt", -1L)
        require(createdAt > 0)
        val array = root.getJSONArray("albums")
        val ids = mutableSetOf<String>()
        val albums = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            val title = item.getString("title")
            require(id.isNotBlank() && title.isNotBlank() && ids.add(id))
            val pathsJson = item.getJSONArray("paths")
            val paths = (0 until pathsJson.length()).map { pathIndex ->
                pathsJson.getString(pathIndex).also { require(CatalogPaths.isSafePhotoPath(it)) }
            }.distinct()
            require(item.optInt("photoCount", -1) == paths.size)
            val videos = readVideos(item.getJSONArray("videos"))
            require(item.optInt("videoCount", -1) == videos.size)
            AlbumEntry(id, title, item.optString("yearMonth").takeUnless { it.isBlank() || it == "null" }, paths, videos)
        }
        val libraryVideos = readVideos(root.getJSONArray("videos"))
        val excludedArray = root.getJSONArray("excluded")
        val excluded = linkedSetOf<String>()
        for (index in 0 until excludedArray.length()) {
            val path = excludedArray.getString(index)
            require(CatalogPaths.isSafePhotoPath(path) && excluded.add(path))
        }
        val portraitArray = root.getJSONArray("portrait")
        val portrait = linkedSetOf<String>()
        for (index in 0 until portraitArray.length()) {
            val path = portraitArray.getString(index)
            require(CatalogPaths.isSafePhotoPath(path) && portrait.add(path))
        }
        return AlbumIndex(
            albums = albums,
            createdAtMillis = createdAt,
            sourceModifiedMillis = root.optLong("sourceModifiedAt", -1L).takeIf { it > 0 },
            sourceSnapshotIdentity = root.getString("sourceSnapshotIdentity").also { require(it.isNotBlank()) },
            videos = libraryVideos,
            excludedPaths = excluded,
            portraitPaths = portrait,
            sourceId = root.getString("sourceId").also { require(it.matches(Regex("[a-f0-9]{64}"))) },
        )
    }

    private fun readVideos(array: JSONArray): List<VideoItem> {
        val seen = mutableSetOf<String>()
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val path = item.getString("p")
            val duration = item.getDouble("d")
            val size = item.getLong("s")
            require(CatalogPaths.isSafeVideoPath(path) && duration.isFinite() && duration > 0.0 && size > 0L && seen.add(path))
            VideoItem(path, duration, size)
        }
    }

    fun build(databaseFile: File, timings: PhaseTimings, progress: (String) -> Unit): AlbumIndex {
        progress("Reading regular albums and memberships")
        val database = timings.time("sqlite_open") {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }
        val mapper = AlbumCatalogMapper()
        try {
            timings.time("sqlite_open") { database.execSQL("PRAGMA query_only=ON") }
            // Android prepares the statement here; that is the first read, so it also pays for
            // SQLite building its WAL index.
            var rowCount = 0L
            timings.time("sqlite_prepare") {
                database.rawQuery(
                    """SELECT a.ZUUID, a.ZTITLE, a.ZSTARTDATE, a.ZENDDATE,
                              x.Z_3ASSETS, s.ZDIRECTORY, s.ZFILENAME, s.ZKIND
                       FROM ZGENERICALBUM a
                       LEFT JOIN Z_34ASSETS x ON x.Z_34ALBUMS=a.Z_PK
                       LEFT JOIN ZASSET s ON s.Z_PK=x.Z_3ASSETS
                       WHERE a.Z_ENT=34 AND a.ZKIND=2 AND a.ZTRASHEDSTATE=0""".trimIndent(),
                    null,
                )
            }.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow("ZUUID")
                val titleColumn = cursor.getColumnIndexOrThrow("ZTITLE")
                val startColumn = cursor.getColumnIndexOrThrow("ZSTARTDATE")
                val endColumn = cursor.getColumnIndexOrThrow("ZENDDATE")
                val assetColumn = cursor.getColumnIndexOrThrow("Z_3ASSETS")
                val directoryColumn = cursor.getColumnIndexOrThrow("ZDIRECTORY")
                val filenameColumn = cursor.getColumnIndexOrThrow("ZFILENAME")
                val kindColumn = cursor.getColumnIndexOrThrow("ZKIND")
                fun consumeRow() {
                    rowCount += 1
                    val hasAsset = !cursor.isNull(assetColumn)
                    mapper.addAlbumRow(
                        AlbumRow(
                            albumId = cursor.stringOrNull(idColumn),
                            albumTitle = cursor.stringOrNull(titleColumn),
                            albumStart = cursor.doubleOrNull(startColumn),
                            albumEnd = cursor.doubleOrNull(endColumn),
                            assetKind = if (hasAsset) cursor.intOrNull(kindColumn) else null,
                            directory = if (hasAsset) cursor.stringOrNull(directoryColumn) else null,
                            filename = if (hasAsset) cursor.stringOrNull(filenameColumn) else null,
                        ),
                    )
                }
                var hasRow = timings.time("sqlite_first_row") { cursor.moveToNext() }
                timings.time("sqlite_rows") {
                    while (hasRow) {
                        consumeRow()
                        hasRow = cursor.moveToNext()
                    }
                }
                timings.note("rows", rowCount)
            }
            var videoRowCount = 0L
            timings.time("sqlite_videos") {
                database.rawQuery(
                    """SELECT a.ZDIRECTORY, a.ZFILENAME, a.ZDURATION, y.ZORIGINALFILESIZE
                       FROM ZASSET a LEFT JOIN ZADDITIONALASSETATTRIBUTES y ON y.ZASSET=a.Z_PK
                       WHERE a.ZKIND=1 AND a.ZTRASHEDSTATE=0 AND a.ZHIDDEN=0 AND a.ZSAVEDASSETTYPE<>$SHARED_ASSET_TYPE""".trimIndent(),
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        videoRowCount += 1
                        mapper.addLibraryVideo(
                            VideoRow(cursor.stringOrNull(0), cursor.stringOrNull(1), cursor.doubleOrNull(2), cursor.longOrNull(3)),
                        )
                    }
                }
            }
            timings.note("video_rows", videoRowCount)
            var excludedRowCount = 0L
            timings.time("sqlite_excluded") {
                // One pass over the classification table: the subquery is not correlated, so SQLite
                // evaluates it once instead of once per asset.
                database.rawQuery(
                    """SELECT a.ZDIRECTORY, a.ZFILENAME
                       FROM ZASSET a
                       WHERE a.ZKIND=0 AND (a.ZISDETECTEDSCREENSHOT=1 OR a.Z_PK IN (
                           SELECT y.ZASSET
                           FROM ZSCENECLASSIFICATION c JOIN ZADDITIONALASSETATTRIBUTES y ON y.Z_PK=c.ZASSETATTRIBUTES
                           WHERE c.ZCLASSIFICATIONTYPE=0 AND (
                               (c.ZSCENEIDENTIFIER=$SCENE_DOCUMENT AND c.ZCONFIDENCE>=$MIN_DOCUMENT_CONFIDENCE) OR
                               (c.ZSCENEIDENTIFIER=$SCENE_RECEIPT AND c.ZCONFIDENCE>=$MIN_RECEIPT_CONFIDENCE))))""".trimIndent(),
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        excludedRowCount += 1
                        mapper.addExcludedPhoto(cursor.stringOrNull(0), cursor.stringOrNull(1))
                    }
                }
            }
            timings.note("excluded_rows", excludedRowCount)
            var portraitRowCount = 0L
            timings.time("sqlite_portrait") {
                // The band test in SQL keeps the result to the portrait rows (about a third of the library);
                // PortraitRules repeats it in Kotlin so the rule is unit-tested. EXIF orientations 5 to 8 swap
                // the sides. ZORIGINAL* describe the original file, not the edited version Photos shows.
                database.rawQuery(
                    """SELECT a.ZDIRECTORY, a.ZFILENAME, y.ZORIGINALWIDTH, y.ZORIGINALHEIGHT, y.ZORIGINALORIENTATION
                       FROM ZASSET a JOIN ZADDITIONALASSETATTRIBUTES y ON y.ZASSET=a.Z_PK
                       WHERE a.ZKIND=0 AND a.ZTRASHEDSTATE=0 AND a.ZSAVEDASSETTYPE<>$SHARED_ASSET_TYPE
                         AND y.ZORIGINALWIDTH>0 AND y.ZORIGINALHEIGHT>0
                         AND (CASE WHEN y.ZORIGINALORIENTATION BETWEEN 5 AND 8
                                   THEN CAST(y.ZORIGINALHEIGHT AS REAL) / y.ZORIGINALWIDTH
                                   ELSE CAST(y.ZORIGINALWIDTH AS REAL) / y.ZORIGINALHEIGHT END)
                             BETWEEN ${PortraitRules.MIN_ASPECT} AND ${PortraitRules.MAX_ASPECT}""".trimIndent(),
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        portraitRowCount += 1
                        mapper.addPortraitPhoto(
                            PortraitRow(
                                cursor.stringOrNull(0), cursor.stringOrNull(1),
                                cursor.intOrNull(2), cursor.intOrNull(3), cursor.intOrNull(4),
                            ),
                        )
                    }
                }
            }
            timings.note("portrait_rows", portraitRowCount)
        } catch (error: Exception) {
            throw AlbumIndexException("The Photos database schema could not be read. Check that the Mac Photos library is available and try Refresh.")
        } finally {
            timings.time("sqlite_close") { database.close() }
        }

        progress("Validating album references under originals")
        val index = timings.time("album_mapping") { mapper.build(System.currentTimeMillis()) }
        timings.note("albums", index.albums.size.toLong())
        timings.note("paths", index.albums.sumOf { it.paths.size }.toLong())
        timings.note("library_videos", index.videos.size.toLong())
        timings.note("album_videos", index.albums.sumOf { it.videos.size }.toLong())
        timings.note("portrait_paths", index.portraitPaths.size.toLong())
        return index
    }

    fun write(file: File, index: AlbumIndex) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(encode(index).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    internal fun encode(index: AlbumIndex): String {
        val root = JSONObject()
            .put("version", VERSION)
            .put("pathRoot", PATH_ROOT)
            .put("createdAt", index.createdAtMillis)
            .put("sourceId", requireNotNull(index.sourceId) { "Catalog must be bound to an SMB source." })
        index.sourceModifiedMillis?.let { root.put("sourceModifiedAt", it) }
        index.sourceSnapshotIdentity?.let { root.put("sourceSnapshotIdentity", it) }
        val albums = JSONArray()
        index.albums.forEach { album ->
            albums.put(
                JSONObject()
                    .put("id", album.id)
                    .put("title", album.title)
                    .put("yearMonth", album.yearMonth ?: JSONObject.NULL)
                    .put("photoCount", album.paths.size)
                    .put("videoCount", album.videos.size)
                    .put("paths", JSONArray().apply { album.paths.forEach(::put) })
                    .put("videos", encodeVideos(album.videos)),
            )
        }
        root.put("albums", albums)
        root.put("videos", encodeVideos(index.videos))
        root.put("excluded", JSONArray().apply { index.excludedPaths.forEach(::put) })
        root.put("portrait", JSONArray().apply { index.portraitPaths.forEach(::put) })
        return root.toString()
    }

    private fun encodeVideos(videos: List<VideoItem>): JSONArray = JSONArray().apply {
        videos.forEach { put(JSONObject().put("p", it.path).put("d", it.durationSeconds).put("s", it.sizeBytes)) }
    }

    private fun Cursor.stringOrNull(column: Int): String? = if (isNull(column)) null else getString(column)
    private fun Cursor.doubleOrNull(column: Int): Double? = if (isNull(column)) null else getDouble(column)
    private fun Cursor.longOrNull(column: Int): Long? = if (isNull(column)) null else getLong(column)
    private fun Cursor.intOrNull(column: Int): Int? = if (isNull(column)) null else getInt(column)
}
