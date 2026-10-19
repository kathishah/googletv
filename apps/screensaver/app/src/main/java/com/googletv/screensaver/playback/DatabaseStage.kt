package com.googletv.screensaver.playback

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

internal enum class DatabaseFile(val localName: String) {
    DATABASE("Photos.sqlite"),
    WAL("Photos.sqlite-wal"),
}

internal data class RemoteFileInfo(val size: Long, val modifiedMillis: Long)

/** Read-only view of the Photos database files on the share. */
internal interface RemoteDatabaseFiles {
    /** Null only when the file is confirmed absent; throw when its presence or metadata is unclear. */
    fun stat(file: DatabaseFile): RemoteFileInfo?

    /** Opens [file] for sequential reading starting at [offset]. */
    fun openStream(file: DatabaseFile, offset: Long = 0L): InputStream
}

internal data class SyncResult(
    val databaseFile: File,
    val databaseBytesFetched: Long,
    val walBytesFetched: Long,
    val reusedDatabase: Boolean,
    /** Offset the WAL copy resumed from; 0 when it was fetched from the start. */
    val walResumedFrom: Long,
    val localWalBytes: Long,
    val databaseModifiedMillis: Long,
    val walModifiedMillis: Long?,
    val snapshotIdentity: String,
    private val sameCommittedSnapshot: Boolean,
) {
    /** The database and committed WAL prefix match the snapshot used for the saved catalog. */
    val unchanged: Boolean get() = sameCommittedSnapshot
}

/**
 * Keeps a persistent, app-private copy of `Photos.sqlite` and its `-wal` file and brings it up to
 * date with as little transfer as is safe.
 *
 * The main database file changes only when Photos checkpoints, and the WAL is append-only within one
 * generation (identified by the salts in its header). So when the database size and modification
 * time are unchanged and the WAL header is identical, only the WAL bytes after the last committed
 * frame are fetched. Anything else (a checkpoint, a WAL restart, a changed database) recopies the
 * affected file(s) in full. State is recorded only after a copy is verified; if it is missing or does
 * not match the files on disk, the next sync starts from scratch.
 */
internal class DatabaseStage(private val directory: File) {
    private val databaseFile = File(directory, DatabaseFile.DATABASE.localName)
    private val walFile = File(directory, DatabaseFile.WAL.localName)
    private val stateFile = File(directory, STATE_FILE)

    fun <T> withExclusiveAccess(block: () -> T): T = synchronized(LOCK, block)

    /**
     * SQLite's wal-index (`-shm`) is derived data. A retained one describes the WAL as it was at the
     * last open and would hide frames appended since, so remove it before every query. The rebuild
     * scans the WAL once; [PhaseTimings] shows what that costs.
     */
    fun prepareForQuery() {
        File(directory, "${DatabaseFile.DATABASE.localName}-shm").delete()
        File(directory, "${DatabaseFile.DATABASE.localName}-journal").delete()
    }

    fun clear() {
        stateFile.delete()
        directory.deleteRecursively()
    }

    fun sync(
        remote: RemoteDatabaseFiles,
        timings: PhaseTimings,
        progress: (done: Long, total: Long) -> Unit,
    ): SyncResult {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw AlbumIndexException("The TV could not prepare storage for the Photos database.")
        }
        val database = timings.time("stat") { remote.stat(DatabaseFile.DATABASE) }
            ?: throw AlbumIndexException("The Photos database is missing or unreadable on the share. Check the library path and read-only access.")
        val wal = timings.time("stat") { remote.stat(DatabaseFile.WAL) }
        if (wal != null && wal.size < WalFrameScanner.WAL_HEADER_BYTES) throw walUnreadable()
        val walHeader = wal?.let { timings.time("stat") { readRange(remote, DatabaseFile.WAL, 0L, WalFrameScanner.WAL_HEADER_BYTES) } }
        if (wal != null && walHeader == null) throw walUnreadable()

        val prior = loadState()
        val reuseDatabase = prior != null && prior.databaseSize == database.size &&
            prior.databaseModified == database.modifiedMillis && databaseFile.length() == database.size
        val resumeFrom = if (
            reuseDatabase && wal != null && walHeader != null &&
            prior.walHeaderHex == hex(walHeader) && prior.walCommitted >= WalFrameScanner.WAL_HEADER_BYTES &&
            prior.walCommitted <= wal.size && walFile.length() == prior.walCommitted &&
            (wal.size != prior.walCommitted || wal.modifiedMillis == prior.walModified) &&
            timings.time("wal_verify") { tailMatches(remote, walHeader, prior.walCommitted) }
        ) prior.walCommitted else 0L

        val total = (if (reuseDatabase) 0L else database.size) + (if (wal != null) wal.size - resumeFrom else 0L)
        var done = 0L
        progress(0L, total)
        val report = { bytes: Long -> done += bytes; progress(done, total) }

        var databaseFetched = 0L
        if (!reuseDatabase) {
            // The recorded state describes the old database, so drop it before replacing the file.
            stateFile.delete()
            databaseFetched = timings.time("db_copy") { copyDatabase(remote, database, report) }
            timings.addBytes("db_copy", databaseFetched)
        }

        var walFetched = 0L
        var committed = 0L
        val rollback = if (resumeFrom > 0L && prior != null && walHeader != null) {
            WalAppendRollback(prior, walHeader.copyOf(), resumeFrom)
        } else null
        try {
            if (wal != null && walHeader != null) {
                val scanner = if (resumeFrom > 0L) WalFrameScanner.resume(walHeader, resumeFrom) else WalFrameScanner.start()
                walFetched = timings.time("wal_copy") { copyWal(remote, wal, resumeFrom, scanner, report) }
                timings.addBytes("wal_copy", walFetched)
                if (scanner.headerInvalid) throw walUnreadable()
                committed = scanner.committedBytes
                RandomAccessFile(walFile, "rw").use { it.setLength(committed) }
            } else {
                walFile.delete()
            }

            timings.time("verify") {
                val databaseAfter = remote.stat(DatabaseFile.DATABASE)
                val walAfter = remote.stat(DatabaseFile.WAL)
                val headerAfter = if (walAfter != null) readRange(remote, DatabaseFile.WAL, 0L, WalFrameScanner.WAL_HEADER_BYTES) else null
                if (databaseAfter != database || walAfter != wal ||
                    (walHeader != null && (headerAfter == null || !headerAfter.contentEquals(walHeader)))
                ) {
                    throw AlbumIndexException("The Photos library changed on the Mac while it was being copied. Try Refresh again once the Mac has finished.")
                }
            }
            progress(total, total)
            val snapshotIdentity = snapshotIdentity(committed)
            saveState(SyncState(
                database.size,
                database.modifiedMillis,
                walHeader?.let(::hex).orEmpty(),
                committed,
                wal?.modifiedMillis ?: 0L,
                snapshotIdentity,
            ))
            return SyncResult(
                databaseFile = databaseFile,
                databaseBytesFetched = databaseFetched,
                walBytesFetched = walFetched,
                reusedDatabase = reuseDatabase,
                walResumedFrom = resumeFrom,
                localWalBytes = committed,
                databaseModifiedMillis = database.modifiedMillis,
                walModifiedMillis = wal?.modifiedMillis,
                snapshotIdentity = snapshotIdentity,
                sameCommittedSnapshot = prior?.snapshotIdentity == snapshotIdentity,
            )
        } catch (error: Throwable) {
            rollback?.let { rollbackAppendIfSafe(remote, it) }
            throw error
        }
    }

    /** The saved prefix remains usable only if the database identity, WAL generation, and prefix still match. */
    private fun rollbackAppendIfSafe(remote: RemoteDatabaseFiles, rollback: WalAppendRollback) {
        val safe = runCatching {
            val state = rollback.state
            loadState() == state &&
                databaseFile.length() == state.databaseSize &&
                remote.stat(DatabaseFile.DATABASE) == RemoteFileInfo(state.databaseSize, state.databaseModified) &&
                remote.stat(DatabaseFile.WAL)?.size?.let { it >= rollback.committedBytes } == true &&
                readRange(remote, DatabaseFile.WAL, 0L, WalFrameScanner.WAL_HEADER_BYTES)
                    ?.contentEquals(rollback.header) == true &&
                walFile.length() >= rollback.committedBytes &&
                RandomAccessFile(walFile, "r").use { local ->
                    val localHeader = ByteArray(WalFrameScanner.WAL_HEADER_BYTES)
                    local.readFully(localHeader)
                    localHeader.contentEquals(rollback.header)
                } &&
                tailMatches(remote, rollback.header, rollback.committedBytes)
        }.getOrDefault(false)
        if (safe) runCatching {
            RandomAccessFile(walFile, "rw").use { it.setLength(rollback.committedBytes) }
        }
    }

    private data class WalAppendRollback(
        val state: SyncState,
        val header: ByteArray,
        val committedBytes: Long,
    )

    private fun copyDatabase(remote: RemoteDatabaseFiles, info: RemoteFileInfo, report: (Long) -> Unit): Long {
        val partial = File(directory, "${databaseFile.name}.part")
        var copied = 0L
        try {
            remote.openStream(DatabaseFile.DATABASE).use { source ->
                FileOutputStream(partial).use { target ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (copied < info.size) {
                        val read = source.read(buffer, 0, minOf(buffer.size.toLong(), info.size - copied).toInt())
                        if (read < 0) break
                        target.write(buffer, 0, read)
                        copied += read
                        report(read.toLong())
                    }
                }
            }
            if (copied != info.size) throw AlbumIndexException("The Photos database ended early while copying. Try Refresh again.")
            replace(partial, databaseFile)
        } finally {
            partial.delete()
        }
        // A new database invalidates the old WAL and any index built from it.
        walFile.delete()
        prepareForQuery()
        return copied
    }

    /** Streams the WAL from [resumeFrom], keeping only frames the [scanner] accepts. */
    private fun copyWal(
        remote: RemoteDatabaseFiles,
        info: RemoteFileInfo,
        resumeFrom: Long,
        scanner: WalFrameScanner,
        report: (Long) -> Unit,
    ): Long {
        var offset = resumeFrom
        RandomAccessFile(walFile, "rw").use { target ->
            target.setLength(resumeFrom)
            target.seek(resumeFrom)
            remote.openStream(DatabaseFile.WAL, resumeFrom).use { source ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (offset < info.size && !scanner.ended) {
                    val read = source.read(buffer, 0, minOf(buffer.size.toLong(), info.size - offset).toInt())
                    if (read < 0) break
                    scanner.feed(buffer, 0, read)
                    target.write(buffer, 0, read)
                    offset += read
                    report(read.toLong())
                }
            }
        }
        if (offset < info.size && !scanner.ended) throw walUnreadable()
        return offset - resumeFrom
    }

    /** The last commit frame's header already on this TV must equal the one on the share. */
    private fun tailMatches(remote: RemoteDatabaseFiles, header: ByteArray, committed: Long): Boolean {
        if (committed == WalFrameScanner.WAL_HEADER_BYTES.toLong()) return true
        val frameBytes = WalFrameScanner.frameBytes(header)
        val start = committed - frameBytes
        if (start < WalFrameScanner.WAL_HEADER_BYTES) return false
        val remoteBytes = readRange(remote, DatabaseFile.WAL, start, WalFrameScanner.FRAME_HEADER_BYTES) ?: return false
        val localBytes = ByteArray(WalFrameScanner.FRAME_HEADER_BYTES)
        return runCatching {
            RandomAccessFile(walFile, "r").use { it.seek(start); it.readFully(localBytes) }
        }.isSuccess && localBytes.contentEquals(remoteBytes)
    }

    private fun readRange(remote: RemoteDatabaseFiles, file: DatabaseFile, offset: Long, length: Int): ByteArray? {
        val bytes = ByteArray(length)
        remote.openStream(file, offset).use { input ->
            var filled = 0
            while (filled < length) {
                val read = input.read(bytes, filled, length - filled)
                if (read < 0) return null
                filled += read
            }
        }
        return bytes
    }

    private fun walUnreadable() = AlbumIndexException(
        "The Photos database log on the share could not be read. Try Refresh again once the Mac has finished copying.",
    )

    private data class SyncState(
        val databaseSize: Long,
        val databaseModified: Long,
        val walHeaderHex: String,
        val walCommitted: Long,
        val walModified: Long,
        val snapshotIdentity: String,
    )

    private fun loadState(): SyncState? = runCatching {
        if (!stateFile.isFile) return null
        val properties = Properties().apply { stateFile.inputStream().use(::load) }
        require(properties.getProperty("version") == STATE_VERSION)
        SyncState(
            properties.getProperty("databaseSize").toLong(),
            properties.getProperty("databaseModified").toLong(),
            properties.getProperty("walHeader").orEmpty(),
            properties.getProperty("walCommitted").toLong(),
            properties.getProperty("walModified").toLong(),
            properties.getProperty("snapshotIdentity").also { require(!it.isNullOrBlank()) },
        )
    }.getOrNull()

    private fun saveState(state: SyncState) {
        val properties = Properties().apply {
            setProperty("version", STATE_VERSION)
            setProperty("databaseSize", state.databaseSize.toString())
            setProperty("databaseModified", state.databaseModified.toString())
            setProperty("walHeader", state.walHeaderHex)
            setProperty("walCommitted", state.walCommitted.toString())
            setProperty("walModified", state.walModified.toString())
            setProperty("snapshotIdentity", state.snapshotIdentity)
        }
        val temporary = File(directory, "$STATE_FILE.tmp")
        try {
            FileOutputStream(temporary).use { properties.store(it, null) }
            replace(temporary, stateFile)
        } finally {
            temporary.delete()
        }
    }

    private fun replace(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Hash the exact local SQLite snapshot that the catalog query will read. */
    private fun snapshotIdentity(committedWalBytes: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun updateFile(file: File, length: Long) {
            digest.update(byteArrayOf(0))
            digest.update(length.toString().toByteArray(Charsets.US_ASCII))
            digest.update(byteArrayOf(0))
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                var remaining = length
                while (remaining > 0L) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count <= 0) throw IOException("Staged snapshot ended early")
                    digest.update(buffer, 0, count)
                    remaining -= count
                }
            }
        }
        updateFile(databaseFile, databaseFile.length())
        digest.update(byteArrayOf(1))
        if (committedWalBytes > 0L) updateFile(walFile, committedWalBytes)
        else digest.update(byteArrayOf(0))
        return hex(digest.digest())
    }

    private companion object {
        const val STATE_FILE = "sync-state.properties"
        const val STATE_VERSION = "2"
        const val BUFFER_BYTES = 1024 * 1024
        val LOCK = Any()
    }
}
