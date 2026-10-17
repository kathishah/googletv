package com.googletv.screensaver.playback

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Builds and validates the TV-private metadata catalog from a staged Photos database. */
internal object PhotosAlbumCatalog {
    // Bump when the query or path mapping changes, so a saved catalog is never kept for a newer build.
    private const val VERSION = 1
    private const val PATH_ROOT = "Photos Library.photoslibrary/originals"
    private val supportedExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    private val yearMonthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    fun read(file: File): AlbumIndex? = runCatching {
        if (!file.isFile) return null
        val root = JSONObject(file.readText(Charsets.UTF_8))
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
                pathsJson.getString(pathIndex).also { require(isSafeRelativePath(it)) }
            }.distinct()
            require(item.optInt("photoCount", -1) == paths.size)
            AlbumEntry(id, title, item.optString("yearMonth").takeUnless { it.isBlank() || it == "null" }, paths)
        }
        AlbumIndex(albums, createdAt, root.optLong("sourceModifiedAt", -1L).takeIf { it > 0 })
    }.getOrNull()

    fun build(databaseFile: File, timings: PhaseTimings, progress: (String) -> Unit): AlbumIndex {
        progress("Reading regular albums and memberships")
        val database = timings.time("sqlite_open") {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }
        val groups = linkedMapOf<String, MutableAlbum>()
        try {
            timings.time("sqlite_open") { database.execSQL("PRAGMA query_only=ON") }
            // The first row pays for building SQLite's WAL index and planning the joins.
            var rowCount = 0L
            // Android prepares the statement here; that is the first read, so it also pays for
            // SQLite building its WAL index.
            timings.time("sqlite_prepare") { database.rawQuery(
                """SELECT a.ZUUID, a.ZTITLE, a.ZSTARTDATE, a.ZENDDATE,
                          x.Z_3ASSETS, s.ZDIRECTORY, s.ZFILENAME, s.ZKIND
                   FROM ZGENERICALBUM a
                   LEFT JOIN Z_34ASSETS x ON x.Z_34ALBUMS=a.Z_PK
                   LEFT JOIN ZASSET s ON s.Z_PK=x.Z_3ASSETS
                   WHERE a.Z_ENT=34 AND a.ZKIND=2 AND a.ZTRASHEDSTATE=0""".trimIndent(),
                null,
            ) }.use { cursor ->
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
                    if (cursor.isNull(idColumn)) return
                    val id = cursor.getString(idColumn)
                    val start = if (cursor.isNull(startColumn)) null else cursor.getDouble(startColumn)
                    val end = if (cursor.isNull(endColumn)) null else cursor.getDouble(endColumn)
                    val album = groups.getOrPut(id) {
                        MutableAlbum(id, cursor.getString(titleColumn)?.takeIf { it.isNotBlank() } ?: "Untitled album", start, end)
                    }
                    if (cursor.isNull(assetColumn) || cursor.isNull(directoryColumn) || cursor.isNull(filenameColumn)) return
                    val kind = if (cursor.isNull(kindColumn)) -1 else cursor.getInt(kindColumn)
                    if (kind !in setOf(0, 1)) return
                    val relative = safeRelativePath(cursor.getString(directoryColumn), cursor.getString(filenameColumn)) ?: return
                    album.paths.add(relative)
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
        } catch (error: Exception) {
            throw AlbumIndexException("The Photos database schema could not be read. Check that the Mac Photos library is available and try Refresh.")
        } finally {
            timings.time("sqlite_close") { database.close() }
        }

        progress("Validating album references under originals")
        val now = System.currentTimeMillis()
        val albums = timings.time("album_mapping") { groups.values.map { album ->
            val date = listOfNotNull(album.start, album.end).maxOrNull()
            val yearMonth = date?.let { seconds ->
                runCatching {
                    yearMonthFormatter.format(Instant.ofEpochMilli(((seconds + COCOA_EPOCH_SECONDS) * 1_000).toLong()).atZone(ZoneId.systemDefault()))
                }.getOrNull()
            }
            AlbumEntry(album.id, album.title, yearMonth, album.paths.toList())
        } }
        timings.note("albums", albums.size.toLong())
        timings.note("paths", albums.sumOf { it.paths.size }.toLong())
        return AlbumIndex(albums, now)
    }

    fun write(file: File, index: AlbumIndex) {
        file.parentFile?.mkdirs()
        val root = JSONObject()
            .put("version", VERSION)
            .put("pathRoot", PATH_ROOT)
            .put("createdAt", index.createdAtMillis)
        index.sourceModifiedMillis?.let { root.put("sourceModifiedAt", it) }
        val albums = JSONArray()
        index.albums.forEach { album ->
            albums.put(JSONObject()
                .put("id", album.id)
                .put("title", album.title)
                .put("yearMonth", album.yearMonth ?: JSONObject.NULL)
                .put("photoCount", album.paths.size)
                .put("paths", JSONArray().apply { album.paths.forEach(::put) }))
        }
        root.put("albums", albums)
        val temporary = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(root.toString().toByteArray(Charsets.UTF_8))
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

    private fun safeRelativePath(directory: String, filename: String): String? {
        val relative = "${directory.replace('\\', '/')}/${filename.replace('\\', '/')}"
        return relative.takeIf { isSafeRelativePath(it) }
    }

    private fun isSafeRelativePath(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/')
        val extension = parts.lastOrNull()?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return path.isNotBlank() && !path.startsWith('/') && parts.none { it.isBlank() || it == "." || it == ".." } &&
            extension in supportedExtensions
    }

    private data class MutableAlbum(val id: String, val title: String, val start: Double?, val end: Double?) {
        val paths = linkedSetOf<String>()
    }

    private const val COCOA_EPOCH_SECONDS = 978_307_200.0
}
