package com.googletv.screensaver.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

class DatabaseStageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val stageDirectory: File by lazy { File(temporaryFolder.root, "stage") }
    private val stage by lazy { DatabaseStage(stageDirectory) }
    private val header = WalBuilder.header()

    private fun database(seed: Int, size: Int = 3000) = ByteArray(size) { (it + seed).toByte() }

    private fun sync(remote: RemoteDatabaseFiles) = stage.sync(remote, PhaseTimings()) { _, _ -> }

    private fun localWal() = File(stageDirectory, "Photos.sqlite-wal").readBytes()

    private val frameBytes = WalBuilder.FRAME_BYTES.toLong()

    @Test
    fun firstSyncCopiesDatabaseAndCommittedWal() {
        val wal = WalBuilder.wal(header, WalBuilder.frame(1, 0), WalBuilder.frame(2, 2), WalBuilder.frame(3, 0))
        val remote = FakeRemote(database(1), 100L, wal, 200L)
        val result = sync(remote)

        assertFalse(result.reusedDatabase)
        assertEquals(3000L, result.databaseBytesFetched)
        assertArrayEquals(remote.database, result.databaseFile.readBytes())
        // The trailing uncommitted frame is not kept.
        assertEquals(32 + 2 * frameBytes, result.localWalBytes)
        assertArrayEquals(wal.copyOf((32 + 2 * frameBytes).toInt()), localWal())
        assertEquals(200L, result.walModifiedMillis)
    }

    @Test
    fun unchangedLibraryTransfersNothing() {
        val wal = WalBuilder.wal(header, WalBuilder.frame(1, 1), WalBuilder.frame(2, 2))
        val remote = FakeRemote(database(1), 100L, wal, 200L)
        sync(remote)
        val second = sync(remote)

        assertTrue(second.reusedDatabase)
        assertEquals(0L, second.databaseBytesFetched)
        assertEquals(0L, second.walBytesFetched)
        assertEquals(32 + 2 * frameBytes, second.walResumedFrom)
        assertTrue(second.unchanged)
    }

    @Test
    fun repeatedUncommittedWalTailDoesNotChangeCommittedSnapshotIdentity() {
        val committed = WalBuilder.wal(header, WalBuilder.frame(1, 1))
        val remote = FakeRemote(database(1), 100L, committed, 200L)
        val originalSnapshot = sync(remote)

        val uncommittedTail = WalBuilder.wal(committed, WalBuilder.frame(2, 0))
        remote.wal = uncommittedTail
        remote.walModified = 300L
        val firstTailRefresh = sync(remote)
        assertTrue(firstTailRefresh.unchanged)
        assertEquals(originalSnapshot.snapshotIdentity, firstTailRefresh.snapshotIdentity)
        assertEquals(WalBuilder.FRAME_BYTES.toLong(), firstTailRefresh.walBytesFetched)

        remote.served.clear()
        val repeatedTailRefresh = sync(remote)
        assertTrue(repeatedTailRefresh.unchanged)
        assertEquals(originalSnapshot.snapshotIdentity, repeatedTailRefresh.snapshotIdentity)
        assertEquals(WalBuilder.FRAME_BYTES.toLong(), repeatedTailRefresh.walBytesFetched)
        assertEquals(committed.size.toLong(), repeatedTailRefresh.localWalBytes)
    }

    @Test
    fun sameLengthWalReplacementWithNewModificationTimeChangesSnapshotIdentity() {
        val original = WalBuilder.wal(header, WalBuilder.frame(1, 1, fill = 11))
        val remote = FakeRemote(database(1), 100L, original, 200L)
        sync(remote)

        val replacement = WalBuilder.wal(header, WalBuilder.frame(1, 1, fill = 99))
        assertEquals(original.size, replacement.size)
        remote.wal = replacement
        remote.walModified = 201L
        remote.served.clear()
        val result = sync(remote)

        assertFalse(result.unchanged)
        assertEquals(0L, result.walResumedFrom)
        assertArrayEquals(replacement, localWal())
    }

    @Test
    fun appendedFramesAreTheOnlyWalBytesFetched() {
        val first = WalBuilder.wal(header, WalBuilder.frame(1, 1), WalBuilder.frame(2, 2))
        val remote = FakeRemote(database(1), 100L, first, 200L)
        sync(remote)

        val grown = WalBuilder.wal(first, WalBuilder.frame(3, 3), WalBuilder.frame(4, 4))
        remote.wal = grown
        remote.walModified = 300L
        remote.served.clear()
        val second = sync(remote)

        assertTrue(second.reusedDatabase)
        assertEquals(2 * frameBytes, second.walBytesFetched)
        assertEquals(32 + 2 * frameBytes, second.walResumedFrom)
        assertArrayEquals(grown, localWal())
        assertEquals(300L, second.walModifiedMillis)
        assertEquals(null, remote.served[DatabaseFile.DATABASE])
        assertFalse(second.unchanged)
    }

    @Test
    fun failedWalAppendRollsBackToCommittedPrefixAndRetryResumesThere() {
        val first = WalBuilder.wal(header, WalBuilder.frame(1, 1))
        val remote = FakeRemote(database(1), 100L, first, 200L)
        sync(remote)
        val prefix = 32L + frameBytes
        val grown = WalBuilder.wal(first, WalBuilder.frame(2, 2), WalBuilder.frame(3, 3))
        remote.wal = grown
        remote.walModified = 300L
        val failing = object : RemoteDatabaseFiles {
            override fun stat(file: DatabaseFile): RemoteFileInfo? = remote.stat(file)

            override fun openStream(file: DatabaseFile, offset: Long): InputStream {
                val source = remote.openStream(file, offset)
                if (file != DatabaseFile.WAL || offset != prefix) return source
                return object : FilterInputStream(source) {
                    private var delivered = 0
                    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                        if (delivered >= 200) throw IOException("interrupted append")
                        val count = super.read(buffer, off, minOf(len, 200 - delivered))
                        if (count > 0) delivered += count
                        return count
                    }
                }
            }
        }

        try {
            sync(failing)
            fail("expected the append stream to fail")
        } catch (expected: IOException) {
            assertEquals("interrupted append", expected.message)
        }
        assertEquals(prefix, localWal().size.toLong())

        val retry = sync(remote)
        assertEquals(prefix, retry.walResumedFrom)
        assertEquals(2 * frameBytes, retry.walBytesFetched)
        assertArrayEquals(grown, localWal())
    }

    @Test
    fun earlyWalEofRollsBackAndRetryResumesAtCommittedPrefix() {
        val first = WalBuilder.wal(header, WalBuilder.frame(1, 1))
        val remote = FakeRemote(database(1), 100L, first, 200L)
        sync(remote)
        val prefix = 32L + frameBytes
        val grown = WalBuilder.wal(first, WalBuilder.frame(2, 2), WalBuilder.frame(3, 3))
        remote.wal = grown
        remote.walModified = 300L
        val short = object : RemoteDatabaseFiles {
            override fun stat(file: DatabaseFile): RemoteFileInfo? = remote.stat(file)

            override fun openStream(file: DatabaseFile, offset: Long): InputStream {
                val source = remote.openStream(file, offset)
                if (file != DatabaseFile.WAL || offset != prefix) return source
                return object : FilterInputStream(source) {
                    private var delivered = 0
                    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                        if (delivered >= 200) return -1
                        val count = super.read(buffer, off, minOf(len, 200 - delivered))
                        if (count > 0) delivered += count
                        return count
                    }
                }
            }
        }

        try {
            sync(short)
            fail("expected the short WAL stream to be rejected")
        } catch (expected: AlbumIndexException) {
            assertTrue(expected.message!!.contains("log"))
        }
        assertEquals(prefix, localWal().size.toLong())

        val retry = sync(remote)
        assertEquals(prefix, retry.walResumedFrom)
        assertEquals(2 * frameBytes, retry.walBytesFetched)
        assertArrayEquals(grown, localWal())
    }

    @Test
    fun walChangedBetweenCopyAndVerificationIsRejectedAndRetrySucceeds() {
        val remote = FakeRemote(database(1), 100L, WalBuilder.wal(header, WalBuilder.frame(1, 1)), 200L)
        var walOpens = 0
        val changing = object : RemoteDatabaseFiles {
            override fun stat(file: DatabaseFile): RemoteFileInfo? = remote.stat(file)

            override fun openStream(file: DatabaseFile, offset: Long): InputStream {
                val source = remote.openStream(file, offset)
                if (file == DatabaseFile.WAL && offset == 0L && ++walOpens == 2) remote.walModified += 1L
                return source
            }
        }

        try {
            sync(changing)
            fail("expected a changed WAL snapshot to be rejected")
        } catch (expected: AlbumIndexException) {
            assertTrue(expected.message!!.contains("changed"))
        }
        assertFalse(File(stageDirectory, "sync-state.properties").exists())

        val retry = sync(remote)
        assertFalse(retry.reusedDatabase)
        assertArrayEquals(remote.wal, localWal())
    }

    @Test
    fun unclearWalListingPreservesPreviouslyCommittedSnapshot() {
        val remote = FakeRemote(database(1), 100L, WalBuilder.wal(header, WalBuilder.frame(1, 1)), 200L)
        sync(remote)
        val committedWal = localWal()
        val unclear = object : RemoteDatabaseFiles {
            override fun stat(file: DatabaseFile): RemoteFileInfo? {
                if (file == DatabaseFile.WAL) throw IOException("listing unavailable")
                return remote.stat(file)
            }

            override fun openStream(file: DatabaseFile, offset: Long): InputStream = remote.openStream(file, offset)
        }

        try {
            sync(unclear)
            fail("expected an unclear WAL listing to be rejected")
        } catch (expected: IOException) {
            assertEquals("listing unavailable", expected.message)
        }
        assertArrayEquals(committedWal, localWal())
        assertTrue(sync(remote).unchanged)
    }

    @Test
    fun existingWalShorterThanHeaderIsNotTreatedAsMissing() {
        val remote = FakeRemote(database(1), 100L, ByteArray(16), 200L)
        try {
            sync(remote)
            fail("expected the short WAL to be rejected")
        } catch (expected: AlbumIndexException) {
            assertTrue(expected.message!!.contains("log"))
        }
        assertFalse(File(stageDirectory, "sync-state.properties").exists())

        remote.wal = WalBuilder.wal(header, WalBuilder.frame(1, 1))
        val retry = sync(remote)
        assertArrayEquals(remote.wal, localWal())
        assertFalse(retry.reusedDatabase)
    }

    @Test
    fun invalidWalFrameChecksumIsRejectedAndRetryPublishesValidSnapshot() {
        val validWal = WalBuilder.wal(header, WalBuilder.frame(1, 1), WalBuilder.frame(2, 2))
        val corruptedWal = validWal.copyOf().also { it[32 + 24 + 20] = (it[32 + 24 + 20].toInt() xor 0x20).toByte() }
        val remote = FakeRemote(database(1), 100L, corruptedWal, 200L)

        try {
            sync(remote)
            fail("expected a WAL checksum error")
        } catch (expected: AlbumIndexException) {
            assertTrue(expected.message!!.contains("log"))
        }
        assertFalse(File(stageDirectory, "sync-state.properties").exists())

        remote.wal = validWal
        remote.walModified = 201L
        sync(remote)
        assertArrayEquals(validWal, localWal())
    }

    @Test
    fun uncommittedTailIsFetchedAgainAfterItIsOverwritten() {
        val committed = WalBuilder.wal(header, WalBuilder.frame(1, 1))
        val remote = FakeRemote(database(1), 100L, WalBuilder.wal(committed, WalBuilder.frame(2, 0)), 200L)
        val first = sync(remote)
        assertEquals(32 + frameBytes, first.localWalBytes)

        // The writer rolled back and wrote different frames in the same place, then committed.
        val rewritten = WalBuilder.wal(committed, WalBuilder.frame(9, 0, fill = 77), WalBuilder.frame(10, 10))
        remote.wal = rewritten
        remote.walModified = 300L
        val second = sync(remote)

        assertEquals(32 + frameBytes, second.walResumedFrom)
        assertEquals(2 * frameBytes, second.walBytesFetched)
        assertArrayEquals(rewritten, localWal())
    }

    @Test
    fun changedDatabaseRecopiesEverything() {
        val wal = WalBuilder.wal(header, WalBuilder.frame(1, 1))
        val remote = FakeRemote(database(1), 100L, wal, 200L)
        sync(remote)

        remote.database = database(2)
        remote.databaseModified = 150L
        remote.wal = WalBuilder.wal(WalBuilder.header(salt1 = 12), WalBuilder.frame(5, 5, salt1 = 12))
        val second = sync(remote)

        assertFalse(second.reusedDatabase)
        assertEquals(3000L, second.databaseBytesFetched)
        assertEquals(0L, second.walResumedFrom)
        assertArrayEquals(remote.database, second.databaseFile.readBytes())
        assertArrayEquals(remote.wal, localWal())
    }

    @Test
    fun walRestartKeepsTheDatabaseButRefetchesTheWal() {
        val remote = FakeRemote(database(1), 100L, WalBuilder.wal(header, WalBuilder.frame(1, 1)), 200L)
        sync(remote)

        val restarted = WalBuilder.wal(WalBuilder.header(salt1 = 12), WalBuilder.frame(7, 7, salt1 = 12), WalBuilder.frame(8, 8, salt1 = 12))
        remote.wal = restarted
        remote.walModified = 300L
        val second = sync(remote)

        assertTrue(second.reusedDatabase)
        assertEquals(0L, second.walResumedFrom)
        assertArrayEquals(restarted, localWal())
    }

    @Test
    fun framesLeftFromBeforeAWalRestartAreNotKept() {
        val stale = WalBuilder.frame(2, 2, salt1 = 5)
        val remote = FakeRemote(
            database(1), 100L,
            WalBuilder.wal(header, WalBuilder.frame(1, 1), stale, stale), 200L,
        )
        val result = sync(remote)
        assertEquals(32 + frameBytes, result.localWalBytes)
        assertEquals(32 + frameBytes, localWal().size.toLong())
    }

    @Test
    fun damagedLocalWalIsRefetchedWithoutRecopyingTheDatabase() {
        val remote = FakeRemote(database(1), 100L, WalBuilder.wal(header, WalBuilder.frame(1, 1), WalBuilder.frame(2, 2)), 200L)
        sync(remote)
        File(stageDirectory, "Photos.sqlite-wal").writeBytes(ByteArray(10))

        val second = sync(remote)
        assertTrue(second.reusedDatabase)
        assertEquals(0L, second.walResumedFrom)
        assertArrayEquals(remote.wal, localWal())
    }

    @Test
    fun changedTailFrameOnTheShareForcesAFullWalRefetch() {
        val remote = FakeRemote(database(1), 100L, WalBuilder.wal(header, WalBuilder.frame(1, 1), WalBuilder.frame(2, 2)), 200L)
        sync(remote)

        // Same generation header, but the last committed frame is not the one we hold.
        remote.wal = WalBuilder.wal(header, WalBuilder.frame(1, 1), WalBuilder.frame(8, 8), WalBuilder.frame(9, 9))
        val second = sync(remote)
        assertEquals(0L, second.walResumedFrom)
        assertArrayEquals(remote.wal, localWal())
    }

    @Test
    fun databaseThatChangesWhileCopyingIsRejectedAndNotRemembered() {
        val remote = FakeRemote(database(1), 100L, WalBuilder.wal(header, WalBuilder.frame(1, 1)), 200L)
        remote.onOpen = { file -> if (file == DatabaseFile.DATABASE) remote.databaseModified += 1 }
        try {
            sync(remote)
            fail("expected the changed database to be rejected")
        } catch (expected: AlbumIndexException) {
            assertTrue(expected.message!!.contains("changed"))
        }

        remote.onOpen = null
        val retry = sync(remote)
        assertFalse(retry.reusedDatabase)
    }

    @Test
    fun missingDatabaseIsReported() {
        try {
            sync(FakeRemote(null, 0L, null, 0L))
            fail("expected a missing database error")
        } catch (expected: AlbumIndexException) {
            assertTrue(expected.message!!.contains("missing or unreadable"))
        }
    }

    @Test
    fun missingWalIsAllowedAndRemovesAnyOldOne() {
        val remote = FakeRemote(database(1), 100L, WalBuilder.wal(header, WalBuilder.frame(1, 1)), 200L)
        sync(remote)
        remote.wal = null
        val second = sync(remote)

        assertTrue(second.reusedDatabase)
        assertEquals(0L, second.localWalBytes)
        assertFalse(File(stageDirectory, "Photos.sqlite-wal").exists())
    }

    @Test
    fun prepareForQueryRemovesDerivedIndexFiles() {
        sync(FakeRemote(database(1), 100L, null, 0L))
        val shm = File(stageDirectory, "Photos.sqlite-shm").apply { writeText("x") }
        stage.prepareForQuery()
        assertFalse(shm.exists())
        assertTrue(File(stageDirectory, "Photos.sqlite").exists())
    }

    @Test
    fun progressReportsActualBytesToTransfer() {
        val remote = FakeRemote(database(1), 100L, WalBuilder.wal(header, WalBuilder.frame(1, 1)), 200L)
        val updates = mutableListOf<Pair<Long, Long>>()
        stage.sync(remote, PhaseTimings()) { done, total -> updates += done to total }
        val expectedTotal = 3000L + 32 + frameBytes
        assertTrue(updates.all { it.second == expectedTotal })
        assertEquals(expectedTotal, updates.last().first)
    }
}
