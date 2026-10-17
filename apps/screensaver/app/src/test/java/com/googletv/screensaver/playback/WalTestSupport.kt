package com.googletv.screensaver.playback

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream

/** Builds small synthetic SQLite WAL files (512-byte pages) for tests. */
internal object WalBuilder {
    const val PAGE_SIZE = 512
    const val FRAME_BYTES = 24 + PAGE_SIZE

    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    fun header(salt1: Int = 11, salt2: Int = 22): ByteArray = ByteArray(32).also {
        putInt(it, 0, 0x377f0682)
        putInt(it, 4, 3007000)
        putInt(it, 8, PAGE_SIZE)
        putInt(it, 16, salt1)
        putInt(it, 20, salt2)
    }

    /** [commitSize] non-zero marks a commit frame. [fill] makes frames distinguishable. */
    fun frame(page: Int, commitSize: Int, salt1: Int = 11, salt2: Int = 22, fill: Int = page): ByteArray =
        ByteArray(FRAME_BYTES).also {
            putInt(it, 0, page)
            putInt(it, 4, commitSize)
            putInt(it, 8, salt1)
            putInt(it, 12, salt2)
            it.fill(fill.toByte(), 24, FRAME_BYTES)
        }

    fun wal(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { all, part -> all + part }
}

/** In-memory stand-in for the share. Counts bytes it serves per file. */
internal class FakeRemote(
    var database: ByteArray?,
    var databaseModified: Long,
    var wal: ByteArray?,
    var walModified: Long,
) : RemoteDatabaseFiles {
    val served = mutableMapOf<DatabaseFile, Long>()
    var onOpen: ((DatabaseFile) -> Unit)? = null

    override fun stat(file: DatabaseFile): RemoteFileInfo? = when (file) {
        DatabaseFile.DATABASE -> database?.let { RemoteFileInfo(it.size.toLong(), databaseModified) }
        DatabaseFile.WAL -> wal?.let { RemoteFileInfo(it.size.toLong(), walModified) }
    }

    override fun openStream(file: DatabaseFile, offset: Long): InputStream {
        onOpen?.invoke(file)
        val bytes = (if (file == DatabaseFile.DATABASE) database else wal) ?: throw java.io.FileNotFoundException()
        val inner = ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
        return object : FilterInputStream(inner) {
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                val count = super.read(buffer, off, len)
                if (count > 0) served[file] = (served[file] ?: 0L) + count
                return count
            }
        }
    }
}
