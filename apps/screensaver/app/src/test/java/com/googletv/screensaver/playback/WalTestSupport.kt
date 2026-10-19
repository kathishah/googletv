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

    fun header(salt1: Int = 11, salt2: Int = 22, magic: Int = 0x377f0682): ByteArray = ByteArray(32).also {
        putInt(it, 0, magic)
        putInt(it, 4, 3007000)
        putInt(it, 8, PAGE_SIZE)
        putInt(it, 16, salt1)
        putInt(it, 20, salt2)
        Checksum(littleEndian = magic == 0x377f0682).also { checksum ->
            checksum.update(it, 0, 24)
            putInt(it, 24, checksum.s0.toInt())
            putInt(it, 28, checksum.s1.toInt())
        }
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

    fun wal(vararg parts: ByteArray): ByteArray {
        val bytes = parts.fold(ByteArray(0)) { all, part -> all + part }
        if (bytes.size < 32) return bytes
        val littleEndian = readInt(bytes, 0) == 0x377f0682
        var checksum = Checksum(littleEndian)
        checksum.update(bytes, 0, 24)
        writeInt(bytes, 24, checksum.s0.toInt())
        writeInt(bytes, 28, checksum.s1.toInt())
        val pageSize = readInt(bytes, 8).let { if (it == 1) 65_536 else it }
        val frameSize = 24 + pageSize
        var offset = 32
        while (offset + frameSize <= bytes.size) {
            checksum.update(bytes, offset, 8)
            checksum.update(bytes, offset + 24, pageSize)
            writeInt(bytes, offset + 16, checksum.s0.toInt())
            writeInt(bytes, offset + 20, checksum.s1.toInt())
            offset += frameSize
        }
        return bytes
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private class Checksum(private val littleEndian: Boolean) {
        var s0 = 0L
            private set
        var s1 = 0L
            private set

        fun update(bytes: ByteArray, offset: Int, length: Int) {
            var index = offset
            while (index < offset + length) {
                val x0 = word(bytes, index)
                val x1 = word(bytes, index + 4)
                s0 = (s0 + x0 + s1) and 0xffff_ffffL
                s1 = (s1 + x1 + s0) and 0xffff_ffffL
                index += 8
            }
        }

        private fun word(bytes: ByteArray, offset: Int): Long = if (littleEndian) {
            (bytes[offset].toLong() and 0xff) or
                ((bytes[offset + 1].toLong() and 0xff) shl 8) or
                ((bytes[offset + 2].toLong() and 0xff) shl 16) or
                ((bytes[offset + 3].toLong() and 0xff) shl 24)
        } else readInt(bytes, offset).toLong() and 0xffff_ffffL
    }
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
