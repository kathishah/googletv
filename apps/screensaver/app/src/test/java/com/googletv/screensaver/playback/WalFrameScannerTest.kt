package com.googletv.screensaver.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalFrameScannerTest {
    private val header = WalBuilder.header()

    private fun scan(scanner: WalFrameScanner, bytes: ByteArray, chunk: Int) {
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(chunk, bytes.size - offset)
            scanner.feed(bytes, offset, count)
            offset += count
        }
    }

    @Test
    fun committedBytesStopAtLastCommitFrame() {
        val wal = WalBuilder.wal(
            header,
            WalBuilder.frame(1, 0), WalBuilder.frame(2, 5),
            WalBuilder.frame(3, 0), WalBuilder.frame(4, 0),
        )
        // Odd chunk sizes force header, frame header and page boundaries to split across feeds.
        for (chunk in listOf(1, 7, 24, 100, 4096)) {
            val scanner = WalFrameScanner.start()
            scan(scanner, wal, chunk)
            assertEquals("chunk=$chunk", (32 + 2 * WalBuilder.FRAME_BYTES).toLong(), scanner.committedBytes)
            assertFalse(scanner.ended)
        }
    }

    @Test
    fun headerOnlyWalIsCommittedAtHeaderSize() {
        val scanner = WalFrameScanner.start()
        scan(scanner, header, 5)
        assertEquals(32L, scanner.committedBytes)
    }

    @Test
    fun framesFromAnotherGenerationEndTheScan() {
        val wal = WalBuilder.wal(
            header,
            WalBuilder.frame(1, 3),
            WalBuilder.frame(2, 3, salt1 = 10), // left over from before the WAL restarted
            WalBuilder.frame(3, 3),
        )
        val scanner = WalFrameScanner.start()
        scan(scanner, wal, 4096)
        assertEquals((32 + WalBuilder.FRAME_BYTES).toLong(), scanner.committedBytes)
        assertTrue(scanner.ended)
    }

    @Test
    fun zeroPageNumberEndsTheScan() {
        val scanner = WalFrameScanner.start()
        scan(scanner, WalBuilder.wal(header, WalBuilder.frame(0, 1)), 4096)
        assertEquals(32L, scanner.committedBytes)
        assertTrue(scanner.ended)
    }

    @Test
    fun invalidHeaderIsRejected() {
        val bad = header.copyOf().also { it[0] = 0 }
        val scanner = WalFrameScanner.start()
        scan(scanner, WalBuilder.wal(bad, WalBuilder.frame(1, 1)), 4096)
        assertTrue(scanner.headerInvalid)
        assertTrue(scanner.ended)
    }

    @Test
    fun resumingContinuesFromTheCommittedOffset() {
        val completeWal = WalBuilder.wal(
            header,
            WalBuilder.frame(1, 0), WalBuilder.frame(2, 2), WalBuilder.frame(3, 0),
            WalBuilder.frame(4, 0), WalBuilder.frame(5, 5),
        )
        val firstEnd = 32 + 3 * WalBuilder.FRAME_BYTES
        val earlier = WalFrameScanner.start().also { scan(it, completeWal.copyOf(firstEnd), 33) }
        assertEquals((32 + 2 * WalBuilder.FRAME_BYTES).toLong(), earlier.committedBytes)

        // The uncommitted frame 3 is fetched again, followed by the new frames.
        val resumed = WalFrameScanner.resume(header, earlier.committedBytes, earlier.committedChecksum)
        scan(resumed, completeWal.copyOfRange(earlier.committedBytes.toInt(), completeWal.size), 50)
        assertEquals((32 + 5 * WalBuilder.FRAME_BYTES).toLong(), resumed.committedBytes)
        assertFalse(resumed.ended)
    }

    @Test
    fun misalignedResumeOffsetIsRejected() {
        val scanner = WalFrameScanner.resume(header, 100L, 0L to 0L)
        assertTrue(scanner.ended)
    }

    @Test
    fun acceptsSqliteGeneratedWalAndRejectsCorruptFrameContent() {
        val wal = javaClass.getResourceAsStream("/sqlite-generated.wal")!!.use { it.readBytes() }
        val valid = WalFrameScanner.start()
        scan(valid, wal, 37)
        assertFalse(valid.headerInvalid)
        assertFalse(valid.checksumInvalid)
        assertTrue(valid.committedBytes > WalFrameScanner.WAL_HEADER_BYTES)

        val corrupted = wal.copyOf().also { it[it.lastIndex - 100] = (it[it.lastIndex - 100].toInt() xor 0x40).toByte() }
        val invalid = WalFrameScanner.start()
        scan(invalid, corrupted, 61)
        assertTrue(invalid.checksumInvalid)
        assertTrue(invalid.committedBytes < wal.size.toLong())
    }

    @Test
    fun supportsBigEndianChecksumWords() {
        val bigEndianWal = WalBuilder.wal(
            WalBuilder.header(magic = 0x377f0683),
            WalBuilder.frame(1, 1),
        )
        val scanner = WalFrameScanner.start()
        scan(scanner, bigEndianWal, 29)
        assertFalse(scanner.headerInvalid)
        assertFalse(scanner.checksumInvalid)
        assertEquals(bigEndianWal.size.toLong(), scanner.committedBytes)
    }

    @Test
    fun rejectsBadHeaderChecksumAndIgnoresTruncatedUncommittedTail() {
        val wal = javaClass.getResourceAsStream("/sqlite-generated.wal")!!.use { it.readBytes() }
        val badHeader = wal.copyOf().also { it[24] = (it[24].toInt() xor 1).toByte() }
        val invalidHeader = WalFrameScanner.start()
        scan(invalidHeader, badHeader, 19)
        assertTrue(invalidHeader.headerInvalid)

        val truncated = wal.copyOf(wal.size - 12)
        val scanner = WalFrameScanner.start()
        scan(scanner, truncated, 113)
        assertFalse(scanner.checksumInvalid)
        assertTrue(scanner.committedBytes <= truncated.size.toLong())
    }

    @Test
    fun pageSizeFieldOfOneMeans65536() {
        val big = header.copyOf().also { it[8] = 0; it[9] = 0; it[10] = 0; it[11] = 1 }
        assertEquals(24L + 65_536L, WalFrameScanner.frameBytes(big))
    }
}
