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
