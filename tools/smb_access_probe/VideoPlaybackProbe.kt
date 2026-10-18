package com.googletv.screensaver

// Diagnostic only; not part of the app. A temporary activity that plays clips from the SMB share
// through the platform MediaPlayer and logs load, first-frame, stall, and throughput numbers.
// Setup (see docs/DEVICE_TESTING.md): copy into the app's package, add
//   <activity android:name=".VideoPlaybackProbe" android:exported="true" />
// to the manifest, build, install, write "label<TAB>path relative to originals<TAB>prefetch blocks"
// lines to files/vtest.txt with run-as (a path starting with "/" is played from local storage instead of
// SMB, as a control for network effects), then `adb shell am start -n com.googletv.screensaver/.VideoPlaybackProbe`.
// Logs use the VideoProbe tag and contain only the labels from that file: no filenames or hosts.

import android.app.Activity
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.File
import java.util.EnumSet
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "VideoProbe"
private const val MAX_PLAY_MILLIS = 50_000L

class VideoPlaybackProbe : Activity(), SurfaceHolder.Callback {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var surfaceView: SurfaceView
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = SurfaceView(this)
        // Sized to the clip's aspect ratio on each video-size change; a SurfaceView otherwise stretches.
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xff000000.toInt())
            addView(surfaceView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        })
        surfaceView.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (started) return
        started = true
        Thread({ runAll(holder) }, "video-probe").start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

    private fun runAll(holder: SurfaceHolder) {
        val config = com.googletv.screensaver.SlideshowPreferences(this).smbSourceConfig()
        val root = config.rootPath.trim('/', '\\').replace('/', '\\')
        val lines = File(filesDir, "vtest.txt").readLines().filter { it.isNotBlank() }
        Log.i(TAG, "start: ${lines.size} runs")
        val smbConfig = SmbConfig.builder().withSigningRequired(false).withMultiProtocolNegotiate(true)
            .withTimeout(15, TimeUnit.SECONDS).withSoTimeout(15, TimeUnit.SECONDS).build()
        for (line in lines) {
            val (label, relative, prefetch) = line.split('\t')
            if (relative.startsWith("/")) {
                try { playOne(holder, null, label, relative, 0) } catch (error: Throwable) { Log.w(TAG, "$label: failed (${error.javaClass.simpleName})") }
                continue
            }
            try {
                // A fresh connection per clip: stopping read-ahead interrupts in-flight reads, which
                // breaks whichever SMB connection they were using.
                SMBClient(smbConfig).use { client ->
                    client.connect(config.host.trim()).use { connection ->
                        val session = connection.authenticate(AuthenticationContext.guest())
                        (session.connectShare(config.share.trim()) as DiskShare).use { share ->
                            val path = "$root\\${relative.replace('/', '\\')}"
                            if (prefetch.toInt() < 0) rawThroughput(share, label, path, -prefetch.toInt())
                            else playOne(holder, share, label, path, prefetch.toInt())
                        }
                    }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "$label: failed (${error.javaClass.simpleName})")
            }
        }
        Log.i(TAG, "finished")
        main.post { finish() }
    }

    /** Reads the whole file in 1 MiB blocks on [threads] threads, with no player, and logs the rate. */
    private fun rawThroughput(share: DiskShare, label: String, path: String, threads: Int) {
        val file = share.openFile(
            path, EnumSet.of(AccessMask.GENERIC_READ), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        val size = file.fileInformation.standardInformation.endOfFile
        val blocks = ((size + (1 shl 20) - 1) shr 20).toInt()
        val pool = Executors.newFixedThreadPool(threads)
        val began = System.nanoTime()
        val total = AtomicLong()
        (0 until blocks).map { block ->
            pool.submit(Callable {
                val start = block.toLong() shl 20
                val wanted = minOf(1L shl 20, size - start).toInt()
                val data = ByteArray(wanted)
                var filled = 0
                while (filled < wanted) {
                    val read = file.read(data, start + filled, filled, wanted - filled)
                    if (read <= 0) break
                    filled += read
                }
                total.addAndGet(filled.toLong())
            })
        }.forEach { it.get() }
        val seconds = (System.nanoTime() - began) / 1e9
        pool.shutdown()
        runCatching { file.close() }
        Log.i(TAG, "$label: raw read threads=$threads bytes=${total.get()} seconds=${"%.1f".format(seconds)} rate=${"%.1f".format(total.get() / seconds / 1e6)}MB/s (${"%.0f".format(total.get() * 8 / seconds / 1e6)} Mbps)")
    }

    private fun playOne(holder: SurfaceHolder, share: DiskShare?, label: String, path: String, prefetch: Int) {
        val file = share?.openFile(
            path, EnumSet.of(AccessMask.GENERIC_READ), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        val size = file?.fileInformation?.standardInformation?.endOfFile ?: File(path).length()
        val source = file?.let { SmbMediaDataSource(it, size, prefetch) }
        val done = CountDownLatch(1)
        val released = CountDownLatch(1)
        val begin = SystemClock.elapsedRealtime()
        var preparedAt = -1L
        var firstFrameAt = -1L
        var startedAt = -1L
        var videoSize = "?"
        var bufferingEvents = 0
        var bufferingStart = 0L
        var bufferingMillis = 0L
        var stalls = 0
        var stallMillis = 0L
        var lastPosition = -1
        var stillSamples = 0
        var shape = "not captured"
        var outcome = "timeout"
        var player: MediaPlayer? = null

        val sampler = object : Runnable {
            override fun run() {
                val mp = player ?: return
                if (startedAt >= 0 && firstFrameAt >= 0) {
                    val position = runCatching { mp.currentPosition }.getOrDefault(-1)
                    if (position == lastPosition) {
                        stillSamples += 1
                        if (stillSamples == 2) stalls += 1
                        if (stillSamples >= 2) stallMillis += 250
                    } else stillSamples = 0
                    lastPosition = position
                    if (shape == "not captured" && SystemClock.elapsedRealtime() - firstFrameAt > 3_000) {
                        shape = "capturing"
                        captureShape { shape = it }
                    }
                }
                if (SystemClock.elapsedRealtime() - begin > MAX_PLAY_MILLIS) done.countDown() else main.postDelayed(this, 250)
            }
        }

        main.post {
            try {
                val mp = MediaPlayer()
                player = mp
                mp.setDisplay(holder)
                mp.setVolume(0f, 0f)
                mp.setOnPreparedListener {
                    preparedAt = SystemClock.elapsedRealtime() - begin
                    startedAt = SystemClock.elapsedRealtime()
                    it.start()
                }
                mp.setOnInfoListener { _, what, _ ->
                    when (what) {
                        MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> if (firstFrameAt < 0) firstFrameAt = SystemClock.elapsedRealtime()
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> { bufferingEvents += 1; bufferingStart = SystemClock.elapsedRealtime() }
                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> if (bufferingStart > 0) { bufferingMillis += SystemClock.elapsedRealtime() - bufferingStart; bufferingStart = 0 }
                    }
                    true
                }
                mp.setOnVideoSizeChangedListener { _, w, h ->
                    videoSize = "${w}x$h"
                    val parent = surfaceView.parent as FrameLayout
                    if (w > 0 && h > 0 && parent.width > 0) {
                        val scale = minOf(parent.width.toFloat() / w, parent.height.toFloat() / h)
                        surfaceView.layoutParams = FrameLayout.LayoutParams((w * scale).toInt(), (h * scale).toInt(), Gravity.CENTER)
                    }
                }
                mp.setOnCompletionListener { outcome = "completed"; done.countDown() }
                mp.setOnErrorListener { _, what, extra -> outcome = "error what=$what extra=$extra"; done.countDown(); true }
                if (source != null) mp.setDataSource(source) else mp.setDataSource(path)
                mp.prepareAsync()
                main.postDelayed(sampler, 250)
            } catch (error: Throwable) {
                outcome = "setup failed (${error.javaClass.simpleName})"
                done.countDown()
            }
        }
        done.await(MAX_PLAY_MILLIS + 15_000, TimeUnit.MILLISECONDS)
        val playedMillis = if (startedAt >= 0) SystemClock.elapsedRealtime() - startedAt else 0
        main.post {
            main.removeCallbacks(sampler)
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
            released.countDown()
        }
        released.await(5, TimeUnit.SECONDS)
        source?.close()
        runCatching { file?.close() }
        val mbps = if (source != null && source.fetchNanos.get() > 0) source.bytes.get() / (source.fetchNanos.get() / 1e9) / 1e6 else 0.0
        Log.i(
            TAG,
            "$label: prefetch=${prefetch}MiB outcome=$outcome prepared=${preparedAt}ms firstFrame=" +
                "${if (firstFrameAt >= 0) firstFrameAt - begin else -1}ms played=${playedMillis}ms videoSize=$videoSize " +
                "bufferingEvents=$bufferingEvents bufferingMs=$bufferingMillis stalls=$stalls stallMs=$stallMillis " +
                "smbBytes=${source?.bytes?.get() ?: 0} fileBytes=$size readAtCalls=${source?.readCalls?.get() ?: 0} fetches=${source?.fetches?.get() ?: 0} " +
                "refetches=${source?.refetches?.get() ?: 0} maxFetch=${source?.maxFetchMillis?.get() ?: 0}ms " +
                "fetchRate=${"%.1f".format(mbps)}MB/s shape=$shape",
        )
    }

    /** Copies the rendered video surface and reports the size of its non-black area. */
    private fun captureShape(report: (String) -> Unit) {
        val bitmap = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888)
        PixelCopy.request(surfaceView, bitmap, { result ->
            if (result != PixelCopy.SUCCESS) {
                report("pixelcopy failed ($result)")
                return@request
            }
            var minX = bitmap.width; var maxX = -1; var minY = bitmap.height; var maxY = -1
            var lumaSum = 0L
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val p = pixels[y * bitmap.width + x]
                val luma = (((p shr 16) and 0xff) * 3 + ((p shr 8) and 0xff) * 6 + (p and 0xff)) / 10
                lumaSum += luma
                if (luma > 24) { if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y }
            }
            val w = if (maxX >= 0) maxX - minX + 1 else 0
            val h = if (maxY >= 0) maxY - minY + 1 else 0
            report("litArea=${w}x$h of ${bitmap.width}x${bitmap.height} ${if (w > 0 && h > w) "portrait" else if (w > 0) "landscape" else "dark"} meanLuma=${lumaSum / pixels.size}")
        }, main)
    }
}

/** Serves a share file to MediaPlayer in 1 MiB blocks, optionally reading ahead on six threads. */
private class SmbMediaDataSource(
    private val file: SmbFile,
    private val length: Long,
    private val prefetchBlocks: Int,
) : MediaDataSource() {
    val bytes = AtomicLong()
    val fetchNanos = AtomicLong()
    val fetches = AtomicLong()
    val maxFetchMillis = AtomicLong()
    val readCalls = AtomicLong()
    val refetches = AtomicLong()
    private val blockSize = 1 shl 20
    private val maxCachedBlocks = 96
    private val executor = Executors.newFixedThreadPool(6)
    private val blocks = ConcurrentHashMap<Long, Future<ByteArray>>()
    private val fetchedBefore = ConcurrentHashMap.newKeySet<Long>()
    private val lastBlock = (length - 1) / blockSize

    override fun getSize(): Long = length

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= length) return -1
        readCalls.incrementAndGet()
        var copied = 0
        var current = position
        var block = current / blockSize
        while (copied < size && current < length) {
            block = current / blockSize
            val data = blockFuture(block).get()
            val inBlock = (current - block * blockSize).toInt()
            val count = minOf(size - copied, data.size - inBlock)
            if (count <= 0) break
            System.arraycopy(data, inBlock, buffer, offset + copied, count)
            copied += count
            current += count
        }
        for (ahead in 1..prefetchBlocks) if ((block + ahead) * blockSize < length) blockFuture(block + ahead)
        if (blocks.size > maxCachedBlocks) {
            // Keep the head and tail (the player jumps between them) and the blocks nearest the read point.
            blocks.keys.filter { it > 1 && it < lastBlock - 1 }.sortedByDescending { kotlin.math.abs(it - block) }
                .take(blocks.size - maxCachedBlocks).forEach { blocks.remove(it) }
        }
        return if (copied == 0) -1 else copied
    }

    private fun blockFuture(block: Long): Future<ByteArray> =
        blocks.computeIfAbsent(block) { executor.submit(Callable { fetch(block) }) }

    private fun fetch(block: Long): ByteArray {
        if (!fetchedBefore.add(block)) refetches.incrementAndGet()
        val start = block * blockSize
        val wanted = minOf(blockSize.toLong(), length - start).toInt()
        val data = ByteArray(wanted)
        val began = System.nanoTime()
        var filled = 0
        while (filled < wanted) {
            val read = file.read(data, start + filled, filled, wanted - filled)
            if (read <= 0) break
            filled += read
        }
        val elapsed = System.nanoTime() - began
        bytes.addAndGet(filled.toLong())
        fetchNanos.addAndGet(elapsed)
        fetches.incrementAndGet()
        maxFetchMillis.accumulateAndGet(elapsed / 1_000_000, ::maxOf)
        return if (filled == wanted) data else data.copyOf(filled)
    }

    override fun close() {
        executor.shutdownNow()
        blocks.clear()
    }
}
