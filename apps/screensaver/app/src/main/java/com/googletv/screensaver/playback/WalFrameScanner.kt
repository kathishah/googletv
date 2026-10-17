package com.googletv.screensaver.playback

/**
 * Walks a SQLite write-ahead log as it streams past and tracks where the last committed frame ends.
 *
 * Only frames that belong to the current WAL generation (their salts match the file header) count,
 * and only up to the last commit frame (non-zero "database size after commit" field). Bytes after
 * that point may be an unfinished or rolled-back transaction that a later write overwrites, so a
 * copy resumed from [committedBytes] stays correct. Checksums are not verified here; SQLite does that
 * when it opens the staged file.
 *
 * WAL layout: a 32-byte header (magic, version, page size, checkpoint sequence, salt-1, salt-2,
 * checksums), then frames of a 24-byte header plus one page. See https://sqlite.org/fileformat2.html
 */
internal class WalFrameScanner private constructor(
    header: ByteArray?,
    private var position: Long,
) {
    /** End offset of the last complete commit frame, or the header size if there is none yet. */
    var committedBytes: Long = position
        private set

    /** True once the header or a frame is not part of this WAL generation; later bytes are ignored. */
    var ended: Boolean = false
        private set

    /** True when the header is not a valid WAL header. Implies [ended]. */
    var headerInvalid: Boolean = false
        private set

    var header: ByteArray? = null
        private set

    private val headerBuffer = ByteArray(WAL_HEADER_BYTES)
    private val frameHeader = ByteArray(FRAME_HEADER_BYTES)
    private var frameSize = 0L
    private var frameCommits = false

    init {
        if (header != null) accept(header)
        if (!ended && header != null && (position < WAL_HEADER_BYTES || (position - WAL_HEADER_BYTES) % frameSize != 0L)) {
            ended = true
            headerInvalid = true
        }
    }

    fun feed(buffer: ByteArray, offset: Int, length: Int) {
        var index = offset
        val end = offset + length
        while (index < end && !ended) {
            if (header == null) {
                val filled = position.toInt()
                val count = minOf(end - index, WAL_HEADER_BYTES - filled)
                System.arraycopy(buffer, index, headerBuffer, filled, count)
                index += count
                position += count
                if (position == WAL_HEADER_BYTES.toLong()) {
                    accept(headerBuffer.copyOf())
                    if (!ended) committedBytes = position
                }
                continue
            }
            val inFrame = ((position - WAL_HEADER_BYTES) % frameSize).toInt()
            if (inFrame < FRAME_HEADER_BYTES) {
                val count = minOf(end - index, FRAME_HEADER_BYTES - inFrame)
                System.arraycopy(buffer, index, frameHeader, inFrame, count)
                index += count
                position += count
                if (inFrame + count == FRAME_HEADER_BYTES) beginFrame()
            } else {
                val count = minOf((end - index).toLong(), frameSize - inFrame).toInt()
                index += count
                position += count
                if (inFrame + count.toLong() == frameSize && frameCommits) committedBytes = position
            }
        }
    }

    private fun accept(candidate: ByteArray) {
        val magic = readInt(candidate, 0)
        val pageSizeField = readInt(candidate, 8)
        val pageSize = if (pageSizeField == 1) 65_536 else pageSizeField
        val valid = (magic == WAL_MAGIC_LITTLE || magic == WAL_MAGIC_BIG) &&
            pageSize in 512..65_536 && pageSize and (pageSize - 1) == 0
        header = candidate
        if (valid) {
            frameSize = (FRAME_HEADER_BYTES + pageSize).toLong()
        } else {
            ended = true
            headerInvalid = true
        }
    }

    private fun beginFrame() {
        val owner = requireNotNull(header)
        val pageNumber = readInt(frameHeader, 0)
        val sameGeneration = (0 until 8).all { frameHeader[8 + it] == owner[16 + it] }
        if (pageNumber == 0 || !sameGeneration) {
            ended = true
            return
        }
        frameCommits = readInt(frameHeader, 4) != 0
    }

    companion object {
        const val WAL_HEADER_BYTES = 32
        const val FRAME_HEADER_BYTES = 24
        private const val WAL_MAGIC_LITTLE = 0x377f0682
        private const val WAL_MAGIC_BIG = 0x377f0683

        fun start() = WalFrameScanner(null, 0L)

        /** Continues a scan at [committedBytes], the end of a commit frame found earlier. */
        fun resume(header: ByteArray, committedBytes: Long) = WalFrameScanner(header.copyOf(), committedBytes)

        fun frameBytes(header: ByteArray): Long {
            val pageSizeField = readInt(header, 8)
            val pageSize = if (pageSizeField == 1) 65_536 else pageSizeField
            return (FRAME_HEADER_BYTES + pageSize).toLong()
        }

        private fun readInt(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
    }
}
