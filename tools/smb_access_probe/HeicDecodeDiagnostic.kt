package com.googletv.screensaver.playback

// Diagnostic only; not part of the app. Copy temporarily into the app's playback package, build
// assembleDebug, push the APK to /data/local/tmp, and run it with app_process. Reads the host, then
// "WIDTHxHEIGHT\trelativePath" lines, from stdin. Reads each file into memory over SMB Guest
// (read-only) and tries several decode paths. Prints dimensions and outcomes only, never paths.

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.media.MediaCodecList
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.nio.ByteBuffer
import java.util.EnumSet
import java.util.concurrent.TimeUnit

fun main() {
    val lines = generateSequence(::readLine).toList()
    val host = lines.first().trim()
    val samples = lines.drop(1).filter { it.isNotBlank() }.map { it.split('\t') }

    println("== HEVC decoders")
    MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }.forEach { info ->
        info.supportedTypes.filter { it.equals("video/hevc", true) || it.equals("image/vnd.android.heic", true) }.forEach { type ->
            val caps = info.getCapabilitiesForType(type)
            val v = caps.videoCapabilities
            println("${info.name} type=$type hw=${info.isHardwareAccelerated} widths=${v?.supportedWidths} heights=${v?.supportedHeights} " +
                "512x512=${v?.isSizeSupported(512, 512)} 5712x4284=${v?.isSizeSupported(5712, 4284)} 4032x3024=${v?.isSizeSupported(4032, 3024)}")
        }
    }

    val config = SmbConfig.builder()
        .withSigningRequired(false)
        .withMultiProtocolNegotiate(true)
        .withTimeout(15, TimeUnit.SECONDS)
        .withSoTimeout(15, TimeUnit.SECONDS)
        .build()
    SMBClient(config).use { client ->
        client.connect(host, 445).use { connection ->
            val session = connection.authenticate(AuthenticationContext.guest())
            val share = session.connectShare("Photos") as DiskShare
            samples.forEachIndexed { index, (dims, rel) ->
                val bytes = share.openFile(
                    rel.replace('/', '\\'), EnumSet.of(AccessMask.GENERIC_READ),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
                ).use { f -> f.inputStream.use { it.readBytes() } }
                println("== sample ${index + 1} mac=$dims bytes=${bytes.size}")
                runTrials(bytes)
            }
            share.close()
            session.close()
        }
    }
}

private fun runTrials(bytes: ByteArray) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    println("  BitmapFactory bounds: ${bounds.outWidth}x${bounds.outHeight} mime=${bounds.outMimeType}")
    for (sample in listOf(4, 8, 16)) {
        trial("BitmapFactory sample=$sample") {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
    trial("ImageDecoder sample=4 software") {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, _, _ ->
            d.setTargetSampleSize(4); d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    trial("ImageDecoder targetSize=1428x1071 software") {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, _, _ ->
            d.setTargetSize(1428, 1071); d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    trial("BitmapRegionDecoder full rect sample=4") {
        @Suppress("DEPRECATION")
        val rd = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)!!
        try {
            rd.decodeRegion(Rect(0, 0, rd.width, rd.height), BitmapFactory.Options().apply { inSampleSize = 4 })
        } finally {
            rd.recycle()
        }
    }
    trial("MediaMetadataRetriever primary image") {
        MediaMetadataRetriever().let { r ->
            try {
                r.setDataSource(ByteArrayDataSource(bytes))
                print("    [meta ${r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_WIDTH)}x" +
                    "${r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_HEIGHT)} " +
                    "count=${r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_COUNT)}] ")
                r.getPrimaryImage(MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 })
            } finally {
                r.release()
            }
        }
    }
}

private fun trial(name: String, block: () -> Bitmap?) {
    val result = try {
        block()?.let { b -> "ok ${b.width}x${b.height}".also { b.recycle() } } ?: "null"
    } catch (t: Throwable) {
        "throws " + generateSequence(t) { it.cause }.joinToString(" <- ") { e ->
            e.javaClass.simpleName + ((e as? ImageDecoder.DecodeException)?.let { "(error=${it.error})" } ?: "") +
                (e.message?.take(120)?.let { ": $it" } ?: "")
        }
    }
    println("  $name: $result")
}

private class ByteArrayDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val n = minOf(size.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        return n
    }
    override fun getSize(): Long = data.size.toLong()
    override fun close() {}
}
