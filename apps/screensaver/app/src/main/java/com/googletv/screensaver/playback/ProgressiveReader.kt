package com.googletv.screensaver.playback

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.ArrayDeque
import java.util.BitSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A video on the share, read at arbitrary offsets. */
internal interface RemoteVideoFile {
    val sizeBytes: Long

    /** Reads up to [length] bytes at [offset] into [buffer]; returns the count, or -1 at end of file. */
    fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int

    /** Unblocks any read in flight and releases the connection. Safe from any thread; idempotent. */
    fun abort()
}

internal data class ProgressiveConfig(
    val blockBytes: Int = 4 * 1024 * 1024,
    /** Link rate assumed until blocks have been measured: 56 Mbps, the slowest sustained rate seen. */
    val assumedBytesPerSecond: Double = 7_000_000.0,
    /** Fraction of the measured rate the plan relies on. */
    val margin: Double = 0.85,
    /** A clip whose start would need a longer wait than this is skipped. */
    val maxStartWaitMillis: Long = 60_000L,
    val minPrebufferBytes: Long = 8L * 1024 * 1024,
    val minPrebufferSeconds: Double = 2.0,
    /** How long a read waits for data before the clip is abandoned. */
    val readTimeoutMillis: Long = 10_000L,
    /** Free space that must remain after the clip's temp file is allocated. */
    val minFreeSpaceBytes: Long = 6L * 1024 * 1024 * 1024,
    /** A clip must have moved at least this much data for its pace to be remembered. */
    val minBytesToRemember: Long = 16L * 1024 * 1024,
)

internal sealed interface GateResult {
    data class Ready(val waitedMillis: Long, val bytesPerSecond: Double) : GateResult
    data class TooSlow(val estimatedWaitMillis: Long) : GateResult
    data class Failed(val error: Throwable) : GateResult
    data object Cancelled : GateResult
}

internal class InsufficientStorageException(message: String) : IOException(message)

internal data class ProgressiveStats(
    val blocksFetched: Int,
    val bytesFetched: Long,
    val bufferedBytes: Long,
    /** Smoothed effective rate (reading and writing each block), or null before the first block. */
    val bytesPerSecond: Double?,
    val refetches: Int,
    val readSeconds: Double = 0.0,
    val writeSeconds: Double = 0.0,
)

/**
 * Remembers how fast recent clips actually downloaded, so a short fast burst at the start of the next
 * clip is not taken at face value. Shared across clips; the estimate is the minimum of the last few
 * clip averages, so one slow stretch is remembered for a while.
 */
internal class LinkRateMemory(private val keep: Int = 3) {
    private val recent = java.util.ArrayDeque<Double>()

    @Synchronized
    fun record(bytesPerSecond: Double) {
        if (!bytesPerSecond.isFinite() || bytesPerSecond <= 0.0) return
        recent.addLast(bytesPerSecond)
        while (recent.size > keep) recent.removeFirst()
    }

    @Synchronized
    fun estimate(): Double? = recent.minOrNull()

    companion object {
        val shared = LinkRateMemory()
    }
}

/**
 * Reads one video from the share into a sparse local temp file while the player plays it.
 *
 * A single reader thread fetches the last block (the `moov` index of most iPhone `.mov` files), then
 * the first, then everything forward in order; a block the player asks for out of order jumps the
 * queue. [readAt] serves present blocks from the local file and waits (up to the read timeout) for
 * missing ones. [awaitReadyToPlay] holds playback until the download is expected to finish before
 * the clip does: `(size - buffered) / (rate * margin) <= duration`, with at least a short prebuffer.
 * Playback is skipped when that wait would exceed the configured maximum.
 *
 * The temp file is removed by [close]. Threads are never interrupted; [RemoteVideoFile.abort] is
 * what unblocks a read in flight, because interrupting an SMB read breaks its connection.
 */
internal class ProgressiveReader(
    private val remote: RemoteVideoFile,
    private val cacheFile: File,
    private val durationSeconds: Double,
    private val config: ProgressiveConfig = ProgressiveConfig(),
    /** Clock for rate measurement; tests pass a virtual one. Waits always use real time. */
    private val nanoTime: () -> Long = System::nanoTime,
    private val freeSpaceBytes: () -> Long = { cacheFile.parentFile?.usableSpace ?: 0L },
    private val rateMemory: LinkRateMemory = LinkRateMemory.shared,
) {
    val sizeBytes: Long = remote.sizeBytes
    private val blockBytes = config.blockBytes
    private val blockCount = ((sizeBytes + blockBytes - 1) / blockBytes).toInt()
    private val lastBlock = blockCount - 1

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val present = BitSet(blockCount)
    private val priority = ArrayDeque<Int>()
    private val fetchedOnce = BitSet(blockCount)
    private var presentBytes = 0L
    private var bytesFetched = 0L
    private var blocksFetched = 0
    private var refetches = 0
    private var rate: Double? = null
    private var readNanos = 0L
    private var writeNanos = 0L
    private var failure: Throwable? = null
    private var forward = 1
    private var stopped = false
    private var started = false
    private var thread: Thread? = null
    private var file: RandomAccessFile? = null
    private var channel: FileChannel? = null

    init {
        require(sizeBytes > 0 && durationSeconds > 0.0 && durationSeconds.isFinite()) { "A clip needs a positive size and duration." }
    }

    /** Allocates the temp file and starts the reader. Throws [InsufficientStorageException] if space is short. */
    fun start() {
        lock.withLock {
            check(!started) { "Already started" }
            started = true
        }
        // The folder must exist first: a missing folder reports no free space.
        cacheFile.parentFile?.mkdirs()
        if (freeSpaceBytes() < sizeBytes + config.minFreeSpaceBytes) {
            throw InsufficientStorageException("Not enough free space to buffer this video.")
        }
        val raf = RandomAccessFile(cacheFile, "rw")
        raf.setLength(sizeBytes)
        file = raf
        channel = raf.channel
        val reader = Thread(::readLoop, "video-reader").apply { isDaemon = true }
        thread = reader
        reader.start()
    }

    private fun readLoop() {
        try {
            val buffer = ByteArray(blockBytes)
            val initial = ArrayDeque<Int>().apply {
                add(lastBlock)
                if (lastBlock != 0) add(0)
            }
            while (true) {
                val block = lock.withLock { nextBlockLocked(initial) } ?: break
                fetch(block, buffer)
            }
        } catch (error: Throwable) {
            lock.withLock {
                if (!stopped) failure = error
                changed.signalAll()
            }
        }
    }

    /** The next block to fetch: requested out of order first, then tail and head, then forward. Null when done or stopped. */
    private fun nextBlockLocked(initial: ArrayDeque<Int>): Int? {
        while (!stopped) {
            val candidate = priority.poll() ?: initial.poll() ?: nextForwardLocked() ?: return null
            if (!present[candidate]) return candidate
        }
        return null
    }

    private fun nextForwardLocked(): Int? {
        while (forward < blockCount && present[forward]) forward += 1
        return if (forward < blockCount) forward++ else null
    }

    private fun fetch(block: Int, buffer: ByteArray) {
        val start = block.toLong() * blockBytes
        val length = minOf(blockBytes.toLong(), sizeBytes - start).toInt()
        val began = nanoTime()
        var filled = 0
        while (filled < length) {
            val read = remote.readAt(start + filled, buffer, filled, length - filled)
            if (read <= 0) throw IOException("The video ended early on the share.")
            filled += read
        }
        val readDone = nanoTime()
        val target = channel ?: return
        var written = 0
        val wrapped = ByteBuffer.wrap(buffer, 0, length)
        while (written < length) written += target.write(wrapped, start + written)
        // The effective rate counts the write too: that is how fast data actually becomes playable.
        val finished = nanoTime()
        val elapsedSeconds = (finished - began) / 1e9
        lock.withLock {
            readNanos += readDone - began
            writeNanos += finished - readDone
            if (stopped) return
            if (!fetchedOnce.get(block)) fetchedOnce.set(block) else refetches += 1
            present.set(block)
            presentBytes += length
            bytesFetched += length
            blocksFetched += 1
            if (elapsedSeconds > 0) {
                val sample = length / elapsedSeconds
                rate = rate?.let { 0.7 * it + 0.3 * sample } ?: sample
            }
            changed.signalAll()
        }
    }

    /** MediaDataSource-style read: copies from the local file, waiting for the block if it is not there yet. */
    @Throws(IOException::class)
    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position < 0 || position >= sizeBytes || size < 0) return -1
        var copied = 0
        var current = position
        while (copied < size && current < sizeBytes) {
            val block = (current / blockBytes).toInt()
            awaitBlock(block)
            val blockStart = block.toLong() * blockBytes
            val blockEnd = minOf(blockStart + blockBytes, sizeBytes)
            val count = minOf((size - copied).toLong(), blockEnd - current).toInt()
            val source = channel ?: throw IOException("The video is closed.")
            val target = ByteBuffer.wrap(buffer, offset + copied, count)
            var read = 0
            while (read < count) {
                val n = source.read(target, current + read)
                if (n < 0) throw IOException("The local video cache is shorter than expected.")
                read += n
            }
            copied += count
            current += count
        }
        return if (copied == 0) -1 else copied
    }

    private fun awaitBlock(block: Int) {
        lock.withLock {
            if (present[block]) return
            if (!priority.contains(block)) priority.add(block)
            changed.signalAll()
            var remaining = TimeUnit.MILLISECONDS.toNanos(config.readTimeoutMillis)
            while (!present[block]) {
                failure?.let { throw IOException("The video could not be read from the share.", it) }
                if (stopped) throw IOException("The video is closed.")
                if (remaining <= 0) throw IOException("Timed out waiting for video data.")
                remaining = changed.awaitNanos(remaining)
            }
        }
    }

    /** Blocks until playback can start safely, the clip is judged too slow, or something fails. */
    fun awaitReadyToPlay(): GateResult {
        val startedAt = nanoTime()
        val realDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.maxStartWaitMillis + 15_000L)
        return lock.withLock {
            var decision = gateDecisionLocked(startedAt, realDeadline)
            while (decision == null) {
                changed.await(100, TimeUnit.MILLISECONDS)
                decision = gateDecisionLocked(startedAt, realDeadline)
            }
            decision
        }
    }

    /** The gate's verdict now, or null to keep waiting. */
    private fun gateDecisionLocked(startedAt: Long, realDeadline: Long): GateResult? {
        failure?.let { return GateResult.Failed(it) }
        if (stopped) return GateResult.Cancelled
        if (present[lastBlock] && present[0]) {
            val bytesPerSecond = planningRateLocked()
            val required = requiredBufferBytes(bytesPerSecond)
            val elapsedMillis = (nanoTime() - startedAt) / 1_000_000
            // Waiting longer than the limit is never acceptable, even if the data has since arrived.
            if (elapsedMillis > config.maxStartWaitMillis) return GateResult.TooSlow(elapsedMillis)
            if (presentBytes >= required) return GateResult.Ready(elapsedMillis, bytesPerSecond)
            val estimatedMillis = ((required - presentBytes) / bytesPerSecond * 1000).toLong()
            if (elapsedMillis + estimatedMillis > config.maxStartWaitMillis) return GateResult.TooSlow(elapsedMillis + estimatedMillis)
        }
        if (System.nanoTime() > realDeadline) return GateResult.TooSlow(config.maxStartWaitMillis)
        return null
    }

    /**
     * The rate the gate plans with: this clip's smoothed effective rate (or the assumed one before any
     * block is measured), capped by what recent clips actually managed.
     */
    private fun planningRateLocked(): Double {
        val own = rate ?: config.assumedBytesPerSecond
        val remembered = rateMemory.estimate()
        return if (remembered != null) minOf(own, remembered) else own
    }

    /** Bytes that must be buffered before play starts, from `(size - buffered) / (rate * margin) <= duration`. */
    private fun requiredBufferBytes(bytesPerSecond: Double): Double {
        val videoBytesPerSecond = sizeBytes / durationSeconds
        val prebuffer = minOf(config.minPrebufferBytes.toDouble(), videoBytesPerSecond * config.minPrebufferSeconds)
        val toFinishInTime = sizeBytes - bytesPerSecond * config.margin * durationSeconds
        return minOf(sizeBytes.toDouble(), maxOf(prebuffer, toFinishInTime))
    }

    /**
     * Waits until the last block and the first [headBlocks] blocks are on this TV, which is enough for
     * `MediaMetadataRetriever` to take a poster frame. Returns false on timeout, failure, or close.
     */
    fun awaitHeadAndTail(headBlocks: Int, timeoutMillis: Long): Boolean {
        val needed = headBlocks.coerceIn(1, blockCount)
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        return lock.withLock {
            while (!(present[lastBlock] && (0 until needed).all { present[it] })) {
                if (failure != null || stopped || remaining <= 0) return@withLock false
                remaining = changed.awaitNanos(remaining)
            }
            true
        }
    }

    /** Bytes already on this TV in an unbroken run starting at [position]; zero if its block is missing. */
    fun contiguousBytesFrom(position: Long): Long = lock.withLock {
        if (position < 0 || position >= sizeBytes) return 0L
        var block = (position / blockBytes).toInt()
        if (!present[block]) return 0L
        val firstMissing = present.nextClearBit(block)
        val end = if (firstMissing >= blockCount) sizeBytes else firstMissing.toLong() * blockBytes
        end - position
    }

    /** The original reader error, if the remote read failed. */
    fun failureCause(): Throwable? = lock.withLock { failure }

    /** True once every block is on this TV. */
    val isComplete: Boolean get() = lock.withLock { presentBytes >= sizeBytes }

    /** Seconds the rest of the download should take at the planning rate, or null if nothing is left. */
    fun estimatedRemainingSeconds(): Double? = lock.withLock {
        val remaining = sizeBytes - presentBytes
        if (remaining <= 0) null else remaining / (planningRateLocked() * config.margin)
    }

    fun stats(): ProgressiveStats = lock.withLock {
        ProgressiveStats(blocksFetched, bytesFetched, presentBytes, rate, refetches, readNanos / 1e9, writeNanos / 1e9)
    }

    /** Stops the reader, aborts any read in flight, and deletes the temp file. Safe to call more than once. */
    fun close() {
        lock.withLock {
            stopped = true
            changed.signalAll()
        }
        remote.abort()
        thread?.join(3_000L)
        // Remember this clip's overall pace for the next one, if it moved enough data to mean anything.
        lock.withLock {
            val seconds = (readNanos + writeNanos) / 1e9
            if (bytesFetched >= config.minBytesToRemember && seconds > 0) rateMemory.record(bytesFetched / seconds)
        }
        runCatching { channel?.close() }
        runCatching { file?.close() }
        channel = null
        file = null
        cacheFile.delete()
    }

    companion object {
        private const val STALE_PREFIX = "video-"
        private const val STALE_SUFFIX = ".part"

        /** A new temp file name for a clip in [directory]. */
        fun newCacheFile(directory: File): File = File(directory, "$STALE_PREFIX${java.util.UUID.randomUUID()}$STALE_SUFFIX")

        /** Deletes temp files left behind by a crash. Call before any reader is active. */
        fun purgeStale(directory: File): Int =
            directory.listFiles { file -> file.name.startsWith(STALE_PREFIX) && file.name.endsWith(STALE_SUFFIX) }
                .orEmpty().count { it.delete() }
    }
}
