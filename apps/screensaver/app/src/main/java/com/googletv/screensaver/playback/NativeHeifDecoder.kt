package com.googletv.screensaver.playback

import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.IOException

/** Native libheif decoder used when Android cannot decode a large HEIF grid. */
internal object NativeHeifDecoder {
    init {
        System.loadLibrary("heif_decoder")
    }

    fun decode(file: File, targetWidth: Int, targetHeight: Int): Bitmap {
        require(targetWidth > 0 && targetHeight > 0)
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val started = System.nanoTime()
        val result = try {
            decodeInto(file.absolutePath, bitmap)
        } catch (error: Exception) {
            bitmap.recycle()
            throw IOException("Native HEIF decoding failed.", error)
        }
        if (!result.startsWith("PASS ")) {
            bitmap.recycle()
            throw IOException(result.removePrefix("FAIL ").ifBlank { "Native HEIF decoding failed." })
        }
        Log.i(LOG_TAG, "Tile decode completed in ${(System.nanoTime() - started) / 1_000_000} ms")
        return bitmap
    }

    private external fun decodeInto(path: String, bitmap: Bitmap): String

    private const val LOG_TAG = "HeifDecoder"
}
