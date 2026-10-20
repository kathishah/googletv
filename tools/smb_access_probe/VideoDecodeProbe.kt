package com.googletv.screensaver.playback

// Diagnostic only; not part of the app. Run with app_process on the TV (see journal/2026-10-17-1813-video-decode-survey.md).
// Part 1 prints decoder capabilities for the video formats found in the library, with no input.
// Part 2 demuxes and decodes the first few seconds of each clip in a directory on the TV and prints
// one line per clip by index: no filenames, paths, or hosts.
//   CLASSPATH=/data/local/tmp/vprobe.apk app_process /system/bin \
//     com.googletv.screensaver.playback.VideoDecodeProbeKt /data/local/tmp/vsample

import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

private const val MAX_FRAMES = 90
private const val MAX_MILLIS = 12_000L

fun main(args: Array<String>) {
    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    printCapabilities(list)
    val directory = File(args.firstOrNull() ?: return)
    println("== decode probe (first $MAX_FRAMES frames or ${MAX_MILLIS / 1000} s per clip)")
    directory.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEachIndexed { index, file ->
        println("clip ${index + 1}: ${probeClip(list, file)}")
    }
}

private fun printCapabilities(list: MediaCodecList) {
    println("== video decoders (regular codec list)")
    val wanted = listOf("video/avc", "video/hevc", "video/dolby-vision", "video/mp4v-es", "video/3gpp", "video/x-motion-jpeg")
    for (info in list.codecInfos.filter { !it.isEncoder }) {
        for (type in info.supportedTypes.filter { it in wanted }) {
            val caps = info.getCapabilitiesForType(type)
            val video = caps.videoCapabilities ?: continue
            val profiles = caps.profileLevels.map { it.profile }.distinct().joinToString(",") { "0x" + it.toString(16) }
            val rates = listOf(Triple(1280, 720, 240.0), Triple(1920, 1080, 60.0), Triple(1920, 1080, 120.0), Triple(1920, 1080, 240.0),
                Triple(3840, 2160, 30.0), Triple(3840, 2160, 60.0)).joinToString(" ") { (w, h, fps) ->
                "${w}x$h@${fps.toInt()}=" + if (video.areSizeAndRateSupported(w, h, fps)) "y" else "n"
            }
            println("  ${info.name} $type hw=${info.isHardwareAccelerated} profiles=[$profiles] $rates")
        }
    }
    println("== audio decoders present")
    for (mime in listOf("audio/mp4a-latm", "audio/3gpp", "audio/raw", "audio/mpeg")) {
        val format = MediaFormat.createAudioFormat(mime, 44100, 2)
        println("  $mime: ${list.findDecoderForFormat(format) ?: "none"}")
    }
}

private fun probeClip(list: MediaCodecList, file: File): String {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
    } catch (error: Throwable) {
        return "extractor failed (${error.javaClass.simpleName})"
    }
    try {
        val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
        val videoIndex = tracks.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        val audioMime = tracks.firstNotNullOfOrNull { it.getString(MediaFormat.KEY_MIME)?.takeIf { m -> m.startsWith("audio/") } }
        val audioNote = if (audioMime == null) "no audio" else {
            val af = tracks.first { it.getString(MediaFormat.KEY_MIME) == audioMime }
            val decoder = runCatching { list.findDecoderForFormat(MediaFormat().apply { af.keys.forEach { key -> copyKey(af, this, key) } }) }.getOrNull()
            "audio $audioMime decoder=${decoder ?: "NONE"}"
        }
        if (videoIndex < 0) return "no video track ($audioNote)"
        val format = tracks[videoIndex]
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val description = "$mime ${format.getInteger(MediaFormat.KEY_WIDTH)}x${format.getInteger(MediaFormat.KEY_HEIGHT)}" +
            " rotation=${if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0}" +
            " transfer=${if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else -1}"
        val lookup = MediaFormat().apply { format.keys.forEach { key -> copyKey(format, this, key) } }
        lookup.removeKey(MediaFormat.KEY_FRAME_RATE)
        val decoderName = list.findDecoderForFormat(lookup) ?: return "$description: NO DECODER ($audioNote)"
        extractor.selectTrack(videoIndex)
        val result = decode(extractor, format, decoderName)
        return "$description decoder=$decoderName $result ($audioNote)"
    } catch (error: Throwable) {
        return "probe failed (${error.javaClass.simpleName})"
    } finally {
        extractor.release()
    }
}

private fun decode(extractor: MediaExtractor, format: MediaFormat, decoderName: String): String {
    val codec = try {
        MediaCodec.createByCodecName(decoderName)
    } catch (error: Throwable) {
        return "create failed (${error.javaClass.simpleName})"
    }
    var frames = 0
    var firstFrameMillis = -1L
    val started = System.nanoTime()
    try {
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone && frames < MAX_FRAMES && (System.nanoTime() - started) / 1_000_000 < MAX_MILLIS) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val out = codec.dequeueOutputBuffer(info, 10_000)
            if (out >= 0) {
                if (info.size > 0) {
                    frames += 1
                    if (firstFrameMillis < 0) firstFrameMillis = (System.nanoTime() - started) / 1_000_000
                }
                codec.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
        val totalMillis = (System.nanoTime() - started) / 1_000_000
        return if (frames > 0) "OK frames=$frames firstFrame=${firstFrameMillis}ms total=${totalMillis}ms" else "NO FRAMES after ${totalMillis}ms"
    } catch (error: Throwable) {
        return "decode failed after $frames frames (${error.javaClass.simpleName})"
    } finally {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}

private fun copyKey(from: MediaFormat, to: MediaFormat, key: String) {
    when {
        // Only integer and string keys matter for decoder selection.
        runCatching { from.getInteger(key) }.isSuccess -> to.setInteger(key, from.getInteger(key))
        runCatching { from.getString(key) }.getOrNull() != null -> to.setString(key, from.getString(key))
    }
}
