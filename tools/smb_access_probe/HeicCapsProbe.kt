package com.googletv.screensaver.playback

// Diagnostic only; not part of the app. Prints which HEVC/HEIF decoders accept the tile formats
// used by 12 MP and 24 MP iPhone HEICs. Run with app_process; no input, no photo data.

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat

fun main() {
    val cases = listOf(
        Triple(512, 512, CodecProfileLevel.HEVCMainTierLevel3),
        Triple(640, 896, CodecProfileLevel.HEVCMainTierLevel31),
        Triple(640, 896, CodecProfileLevel.HEVCMainTierLevel3),
        Triple(896, 640, CodecProfileLevel.HEVCMainTierLevel31),
        Triple(512, 512, CodecProfileLevel.HEVCMainTierLevel31),
    )
    val list = MediaCodecList(MediaCodecList.ALL_CODECS)
    for (mime in listOf("image/vnd.android.heic", "video/hevc")) {
        println("== findDecoderForFormat mime=$mime")
        for ((w, h, level) in cases) {
            val withPl = MediaFormat.createVideoFormat(mime, w, h).apply {
                setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain)
                setInteger(MediaFormat.KEY_LEVEL, level)
            }
            val sizeOnly = MediaFormat.createVideoFormat(mime, w, h)
            println("  ${w}x$h level=0x${level.toString(16)}: withProfileLevel=${list.findDecoderForFormat(withPl)} sizeOnly=${list.findDecoderForFormat(sizeOnly)}")
        }
    }
    println("== per decoder")
    list.codecInfos.filter { !it.isEncoder && (it.name.contains("hevc", true) || it.name.contains("heif", true)) }.forEach { info ->
        info.supportedTypes.forEach { type ->
            val caps = info.getCapabilitiesForType(type)
            val v = caps.videoCapabilities
            val maxLevel = caps.profileLevels.filter { it.profile == CodecProfileLevel.HEVCProfileMain }.maxOfOrNull { it.level }
            val sizes = cases.joinToString(" ") { (w, h, level) ->
                val f = MediaFormat.createVideoFormat(type, w, h).apply {
                    setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain)
                    setInteger(MediaFormat.KEY_LEVEL, level)
                }
                "${w}x$h/L0x${level.toString(16)}:size=${v?.isSizeSupported(w, h)},fmt=${caps.isFormatSupported(f)}"
            }
            println("${info.name} [$type] alias=${info.isAlias} align=${v?.widthAlignment}x${v?.heightAlignment} " +
                "mainMaxLevel=0x${maxLevel?.toString(16)} $sizes")
        }
    }
}
