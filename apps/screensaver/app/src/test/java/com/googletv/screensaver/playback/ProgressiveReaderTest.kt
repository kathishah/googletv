package com.googletv.screensaver.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Virtual time: each fake read advances it by bytes / link rate, so nothing sleeps. */
private class VirtualClock {
    private val nanos = AtomicLong(0)
    fun now(): Long = nanos.get()
    fun advanceSeconds(seconds: Double) { nanos.addAndGet((seconds * 1e9).toLong()) }
}

private class FakeRemoteVideo(
    val data: ByteArray,
    private val clock: VirtualClock,
    private val bytesPerSecond: Double = 1e9,
    private val failInRange: LongRange? = null,
    initialPermits: Int = Int.MAX_VALUE / 2,
) : RemoteVideoFile {
    override val sizeBytes: Long = data.size.toLong()
    val offsets = CopyOnWriteArrayList<Long>()
    val permits = Semaphore(initialPermits)
    private val aborted = AtomicBoolean(false)

    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        while (!aborted.get()) {
            if (permits.tryAcquire(20, TimeUnit.MILLISECONDS)) break
        }
        if (aborted.get()) throw IOException("aborted")
        if (failInRange != null && (offset until offset + length).any { it in failInRange }) throw IOException("boom")
        if (offset >= sizeBytes) return -1
        val count = minOf(length.toLong(), sizeBytes - offset).toInt()
        System.arraycopy(data, offset.toInt(), buffer, bufferOffset, count)
        offsets.add(offset)
        clock.advanceSeconds(count / bytesPerSecond)
        return count
    }

    override fun abort() { aborted.set(true) }
}

class ProgressiveReaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val block = 1000
    private val clock = VirtualClock()

    private fun bytes(size: Int) = ByteArray(size) { ((it * 31) xor (it ushr 8)).toByte() }

    private fun config(maxStartWaitMillis: Long = 60_000L) = ProgressiveConfig(
        blockBytes = block,
        assumedBytesPerSecond = 1000.0,
        margin = 0.85,
        maxStartWaitMillis = maxStartWaitMillis,
        minPrebufferBytes = 100_000L,
        minPrebufferSeconds = 2.0,
        readTimeoutMillis = 2_000L,
        minFreeSpaceBytes = 0L,
    )

    private fun readerFor(
        remote: FakeRemoteVideo,
        duration: Double = 10.0,
        config: ProgressiveConfig = config(),
        freeSpace: () -> Long = { Long.MAX_VALUE },
        memory: LinkRateMemory = LinkRateMemory(), // never the shared one, so tests cannot affect each other
    ) = ProgressiveReader(remote, File(temporaryFolder.root, "video-test.part"), duration, config, clock::now, freeSpace, memory)

    private fun awaitAllFetched(reader: ProgressiveReader, size: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (reader.stats().bytesFetched < size) {
            if (System.nanoTime() > deadline) fail("download did not finish: ${reader.stats()}")
            Thread.sleep(5)
        }
    }

    @Test
    fun fetchesTheTailThenTheHeadThenForward() {
        val remote = FakeRemoteVideo(bytes(10_500), clock)
        val reader = readerFor(remote).also { it.start() }
        awaitAllFetched(reader, 10_500)
        assertEquals(listOf(10_000L, 0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 6_000L, 7_000L, 8_000L, 9_000L), remote.offsets.toList())
        val stats = reader.stats()
        assertEquals(11, stats.blocksFetched)
        assertEquals(0, stats.refetches)
        reader.close()
    }

    @Test
    fun readAtReturnsTheSameBytesAsTheSource() {
        val data = bytes(10_500)
        val reader = readerFor(FakeRemoteVideo(data, clock)).also { it.start() }
        awaitAllFetched(reader, 10_500)
        for ((position, size) in listOf(0L to 10, 995L to 20, 1_000L to 1_000, 5_500L to 3_000, 10_000L to 500, 10_400L to 500, 0L to 10_500)) {
            val buffer = ByteArray(size + 3)
            val read = reader.readAt(position, buffer, 2, size)
            val expected = minOf(size.toLong(), 10_500 - position).toInt()
            assertEquals("read at $position", expected, read)
            assertArrayEquals("bytes at $position", data.copyOfRange(position.toInt(), position.toInt() + expected), buffer.copyOfRange(2, 2 + expected))
        }
        assertEquals(-1, reader.readAt(10_500L, ByteArray(10), 0, 10))
        assertEquals(-1, reader.readAt(-1L, ByteArray(10), 0, 10))
        assertEquals(-1, reader.readAt(5L, ByteArray(10), 0, 0))
        reader.close()
    }

    @Test
    fun aBlockTheReaderIsAskedForOutOfOrderJumpsTheQueue() {
        val data = bytes(10_500)
        val remote = FakeRemoteVideo(data, clock, initialPermits = 2)
        val reader = readerFor(remote).also { it.start() }
        val waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (remote.offsets.size < 2) { if (System.nanoTime() > waitUntil) fail("tail and head not fetched"); Thread.sleep(5) }
        val result = arrayOfNulls<Int>(1)
        val worker = Thread { result[0] = reader.readAt(7_100L, ByteArray(50), 0, 50) }.apply { start() }
        Thread.sleep(150) // the request is queued while the reader waits on block 1
        remote.permits.release(1000)
        worker.join(5_000)
        assertEquals(50, result[0])
        awaitAllFetched(reader, 10_500)
        val order = remote.offsets.toList()
        assertTrue("block 7 should come before block 2: $order", order.indexOf(7_000L) < order.indexOf(2_000L))
        reader.close()
    }

    @Test
    fun theGateOpensAtOnceOnAFastLink() {
        val reader = readerFor(FakeRemoteVideo(bytes(5_000), clock, bytesPerSecond = 1e9)).also { it.start() }
        val result = reader.awaitReadyToPlay()
        assertTrue("$result", result is GateResult.Ready)
        assertTrue((result as GateResult.Ready).waitedMillis < 1_000)
        reader.close()
    }

    @Test
    fun theGateWaitsForEnoughBufferWhenTheClipIsFasterThanTheLink() {
        // 20 kB over 10 s is 2000 B/s on a 1000 B/s link: it must buffer 20000 - 0.85 * 1000 * 10 = 11500 bytes,
        // which is 12 blocks of 1000. The fake link hands out one block at a time so the gate can be watched.
        val remote = FakeRemoteVideo(bytes(20_000), clock, bytesPerSecond = 1000.0, initialPermits = 2)
        val reader = readerFor(remote, duration = 10.0).also { it.start() }
        val outcome = arrayOfNulls<GateResult>(1)
        val worker = Thread { outcome[0] = reader.awaitReadyToPlay() }.apply { start() }
        fun awaitBlocks(count: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (reader.stats().blocksFetched < count) { if (System.nanoTime() > deadline) fail("stuck at ${reader.stats()}"); Thread.sleep(5) }
        }
        awaitBlocks(2)
        for (target in 3..11) {
            remote.permits.release(1)
            awaitBlocks(target)
            Thread.sleep(120) // give the gate time to (wrongly) open
            assertTrue("gate opened early with $target blocks", worker.isAlive)
        }
        remote.permits.release(1)
        worker.join(5_000)
        assertFalse(worker.isAlive)
        val result = outcome[0]
        assertTrue("$result", result is GateResult.Ready)
        assertEquals(1000.0, (result as GateResult.Ready).bytesPerSecond, 50.0)
        assertEquals(12, reader.stats().blocksFetched)
        reader.close()
    }

    @Test
    fun aSlowEarlierClipCapsTheNextClipsPlanningRate() {
        // This link looks instant, but the last clip only managed 500 B/s, so the plan needs
        // 20000 - 0.85 * 500 * 10 = 15750 bytes: 16 blocks, not the 2 an instant link would suggest.
        val remote = FakeRemoteVideo(bytes(20_000), clock, bytesPerSecond = 1e9, initialPermits = 2)
        val memory = LinkRateMemory().apply { record(500.0) }
        val reader = readerFor(remote, duration = 10.0, memory = memory).also { it.start() }
        val outcome = arrayOfNulls<GateResult>(1)
        val worker = Thread { outcome[0] = reader.awaitReadyToPlay() }.apply { start() }
        fun awaitBlocks(count: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (reader.stats().blocksFetched < count) { if (System.nanoTime() > deadline) fail("stuck at ${reader.stats()}"); Thread.sleep(5) }
        }
        awaitBlocks(2)
        for (target in 3..15) {
            remote.permits.release(1)
            awaitBlocks(target)
            Thread.sleep(80)
            assertTrue("gate opened early with $target blocks", worker.isAlive)
        }
        remote.permits.release(1)
        worker.join(5_000)
        assertTrue("${outcome[0]}", outcome[0] is GateResult.Ready)
        assertEquals(500.0, (outcome[0] as GateResult.Ready).bytesPerSecond, 1.0)
        reader.close()
    }

    @Test
    fun closingRecordsTheClipsPaceForTheNextOne() {
        val memory = LinkRateMemory()
        val reader = readerFor(
            FakeRemoteVideo(bytes(10_500), clock, bytesPerSecond = 5_000.0), config = config().copy(minBytesToRemember = 1_000L), memory = memory,
        ).also { it.start() }
        awaitAllFetched(reader, 10_500)
        reader.close()
        assertEquals(5_000.0, memory.estimate()!!, 50.0)
    }

    @Test
    fun aClipThatMovedLittleDataIsNotRemembered() {
        val memory = LinkRateMemory()
        val reader = readerFor(FakeRemoteVideo(bytes(3_000), clock, bytesPerSecond = 100.0), memory = memory).also { it.start() }
        awaitAllFetched(reader, 3_000)
        reader.close() // 3 kB is under the default 16 MiB threshold
        assertNull(memory.estimate())
    }

    @Test
    fun rateMemoryKeepsTheLastFewClipsAndReportsTheSlowest() {
        val memory = LinkRateMemory(keep = 3)
        assertNull(memory.estimate())
        listOf(9.0, 8.0, 5.0, 7.0).forEach(memory::record)
        assertEquals(5.0, memory.estimate()!!, 0.0) // 9.0 has been forgotten; 5.0 is the slowest of 8, 5, 7
        listOf(6.0, 7.0).forEach(memory::record)
        assertEquals(6.0, memory.estimate()!!, 0.0) // the slow 5.0 has aged out
        memory.record(Double.NaN); memory.record(0.0); memory.record(-3.0)
        assertEquals(6.0, memory.estimate()!!, 0.0)
    }

    @Test
    fun theGateSkipsAClipThatWouldNeedAnImpossibleWait() {
        val reader = readerFor(FakeRemoteVideo(bytes(200_000), clock, bytesPerSecond = 1000.0), duration = 10.0).also { it.start() }
        val result = reader.awaitReadyToPlay()
        assertTrue("$result", result is GateResult.TooSlow)
        assertTrue((result as GateResult.TooSlow).estimatedWaitMillis > 60_000)
        reader.close()
    }

    @Test
    fun aLongerWaitLimitLetsThatClipThrough() {
        val reader = readerFor(
            FakeRemoteVideo(bytes(40_000), clock, bytesPerSecond = 1000.0), duration = 10.0, config = config(maxStartWaitMillis = 600_000L),
        ).also { it.start() }
        assertTrue(reader.awaitReadyToPlay() is GateResult.Ready)
        reader.close()
    }

    @Test
    fun closeStopsTheReaderUnblocksItAndDeletesTheTempFile() {
        val remote = FakeRemoteVideo(bytes(10_500), clock, initialPermits = 2)
        val file = File(temporaryFolder.root, "video-test.part")
        val reader = readerFor(remote).also { it.start() }
        Thread.sleep(100) // the reader is now blocked inside a read
        assertTrue(file.exists())
        val began = System.nanoTime()
        reader.close()
        assertTrue("close took ${(System.nanoTime() - began) / 1_000_000} ms", System.nanoTime() - began < TimeUnit.SECONDS.toNanos(2))
        assertFalse(file.exists())
        try {
            reader.readAt(0, ByteArray(10), 0, 10)
            fail("expected a closed reader to refuse reads")
        } catch (_: IOException) {
        }
        reader.close() // idempotent
    }

    @Test
    fun aFailureInTheTailBlockFailsTheGate() {
        val reader = readerFor(FakeRemoteVideo(bytes(10_500), clock, failInRange = 10_000L..10_499L)).also { it.start() }
        val result = reader.awaitReadyToPlay()
        assertTrue("$result", result is GateResult.Failed)
        reader.close()
    }

    @Test
    fun aFailureInAnotherBlockSurfacesToTheReader() {
        val reader = readerFor(FakeRemoteVideo(bytes(10_500), clock, failInRange = 3_000L..3_999L)).also { it.start() }
        try {
            reader.readAt(3_100L, ByteArray(10), 0, 10)
            fail("expected an IOException")
        } catch (error: IOException) {
            assertNotNull(error)
        }
        reader.close()
    }

    @Test
    fun aReadThatNeverGetsItsDataTimesOut() {
        val remote = FakeRemoteVideo(bytes(10_500), clock, initialPermits = 2)
        val reader = readerFor(remote).also { it.start() }
        Thread.sleep(100)
        val began = System.nanoTime()
        try {
            reader.readAt(7_100L, ByteArray(10), 0, 10)
            fail("expected a timeout")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("Timed out"))
        }
        assertTrue(System.nanoTime() - began >= TimeUnit.MILLISECONDS.toNanos(1_500))
        reader.close()
    }

    @Test
    fun purgeStaleRemovesOnlyLeftoverVideoCacheFiles() {
        listOf("video-a.part", "video-b.part", "other.txt", "video-c.mov", "catalog.part").forEach { File(temporaryFolder.root, it).writeText("x") }
        assertEquals(2, ProgressiveReader.purgeStale(temporaryFolder.root))
        assertEquals(setOf("other.txt", "video-c.mov", "catalog.part"), temporaryFolder.root.list()!!.toSet())
        assertTrue(ProgressiveReader.newCacheFile(temporaryFolder.root).name.matches(Regex("video-.+\\.part")))
    }

    @Test
    fun refusesToStartWithoutEnoughFreeSpace() {
        val file = File(temporaryFolder.root, "video-test.part")
        val reader = ProgressiveReader(
            FakeRemoteVideo(bytes(5_000), clock), file, 10.0, config().copy(minFreeSpaceBytes = 1_000L), clock::now,
            freeSpaceBytes = { 5_999L }, rateMemory = LinkRateMemory(),
        )
        try {
            reader.start()
            fail("expected InsufficientStorageException")
        } catch (_: InsufficientStorageException) {
        }
        assertFalse(file.exists())
    }

    @Test
    fun contiguousBytesFromReportsTheUnbrokenRunOnThisTv() {
        val remote = FakeRemoteVideo(bytes(10_500), clock, initialPermits = 2) // only the tail and the head arrive
        val reader = readerFor(remote).also { it.start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (reader.stats().blocksFetched < 2) { if (System.nanoTime() > deadline) fail("stuck"); Thread.sleep(5) }
        assertEquals(1_000L, reader.contiguousBytesFrom(0))
        assertEquals(500L, reader.contiguousBytesFrom(500))
        assertEquals(0L, reader.contiguousBytesFrom(1_000)) // block 1 is not there yet
        assertEquals(400L, reader.contiguousBytesFrom(10_100)) // the tail runs to the end of the file
        assertEquals(0L, reader.contiguousBytesFrom(10_500))
        assertEquals(0L, reader.contiguousBytesFrom(-5))
        assertFalse(reader.isComplete)
        assertNotNull(reader.estimatedRemainingSeconds())
        remote.permits.release(1000)
        awaitAllFetched(reader, 10_500)
        assertEquals(10_500L, reader.contiguousBytesFrom(0))
        assertEquals(10_000L, reader.contiguousBytesFrom(500))
        assertTrue(reader.isComplete)
        assertNull(reader.estimatedRemainingSeconds())
        reader.close()
    }

    @Test
    fun theDefaultFreeSpaceCheckWorksWhenTheCacheFolderDoesNotExistYet() {
        val data = bytes(3_000)
        val file = File(temporaryFolder.root, "video-cache/nested/video-test.part")
        assertFalse(file.parentFile!!.exists())
        val reader = ProgressiveReader(FakeRemoteVideo(data, clock), file, 10.0, config(), clock::now) // default free-space check
        reader.start()
        assertTrue(reader.awaitReadyToPlay() is GateResult.Ready)
        assertTrue(file.exists())
        reader.close()
        assertFalse(file.exists())
    }

    @Test
    fun tinyAndSingleBlockFilesWork() {
        for (size in listOf(300, 1_000, 2_000, 2_001)) {
            val data = bytes(size)
            val reader = readerFor(FakeRemoteVideo(data, clock)).also { it.start() }
            assertTrue("size $size", reader.awaitReadyToPlay() is GateResult.Ready)
            val buffer = ByteArray(size)
            assertEquals(size, reader.readAt(0, buffer, 0, size))
            assertArrayEquals(data, buffer)
            reader.close()
        }
    }

    @Test
    fun theMeasuredRateMatchesTheLink() {
        val reader = readerFor(FakeRemoteVideo(bytes(10_500), clock, bytesPerSecond = 5_000.0)).also { it.start() }
        awaitAllFetched(reader, 10_500)
        assertEquals(5_000.0, reader.stats().bytesPerSecond!!, 50.0)
        reader.close()
    }

    @Test
    fun badSizesOrDurationsAreRejected() {
        for (duration in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            try {
                readerFor(FakeRemoteVideo(bytes(100), clock), duration = duration)
                fail("expected rejection for $duration")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
