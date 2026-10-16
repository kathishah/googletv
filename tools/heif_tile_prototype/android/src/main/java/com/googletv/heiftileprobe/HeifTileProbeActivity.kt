package com.googletv.heiftileprobe

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.nio.IntBuffer

class HeifTileProbeActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var image: ImageView
    private lateinit var run: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 18f }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        run = Button(this).apply {
            text = "Decode staged HEIC"
            setOnClickListener { decodeSample() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 24, 32, 24)
            addView(status)
            addView(run)
            addView(image, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        status.text = "Stage a 24 MP sample, then decode it."
        intent.getIntExtra(EXTRA_PROBE_INDEX, 0).takeIf { it > 0 }?.let(::decodeBatchSample)
        intent.getIntExtra(EXTRA_PREVIEW_INDEX, 0).takeIf { it > 0 }?.let { decodeSample() }
    }

    private fun decodeSample() {
        val file = sampleFile()
        if (!file.isFile) {
            status.text = "No sample at ${file.absolutePath}"
            return
        }
        status.text = "Decoding 45 HEIF grid tiles…"
        run.isEnabled = false
        Thread {
            val started = System.nanoTime()
            val pixels = nativeDecode(file.absolutePath, TARGET_WIDTH, TARGET_HEIGHT)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val details = nativeLastInfo()
            runOnUiThread {
                run.isEnabled = true
                if (pixels == null) {
                    status.text = "Failed after ${elapsedMs}ms\n$details"
                    image.setImageDrawable(null)
                    return@runOnUiThread
                }
                val bitmap = Bitmap.createBitmap(TARGET_WIDTH, TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(IntBuffer.wrap(pixels))
                image.setImageBitmap(bitmap)
                status.text = "Success in ${elapsedMs}ms\n$details"
            }
        }.start()
    }

    private fun decodeBatchSample(index: Int) {
        if (index == 1) File(filesDir, RESULT_FILE).writeText("")
        val file = sampleFile()
        if (!file.isFile) {
            status.text = "Sample $index: missing staged image"
            return
        }
        status.text = "Decoding sample $index…"
        run.isEnabled = false
        Thread {
            val started = System.nanoTime()
            val outcome = runCatching {
                nativeProbe(file.absolutePath, TARGET_WIDTH, TARGET_HEIGHT)
            }.getOrElse { "FAIL ${it.javaClass.simpleName}" }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val row = "$index\t$elapsedMs\t${outcome.replace('\t', ' ').replace('\n', ' ')}\n"
            File(filesDir, RESULT_FILE).appendText(row)
            runOnUiThread {
                run.isEnabled = true
                status.text = "Sample $index complete in ${elapsedMs}ms\n$outcome"
            }
        }.start()
    }

    private fun sampleFile() = File(filesDir, "sample.heic")

    private external fun nativeDecode(path: String, targetWidth: Int, targetHeight: Int): IntArray?
    private external fun nativeProbe(path: String, targetWidth: Int, targetHeight: Int): String
    private external fun nativeLastInfo(): String

    companion object {
        private const val TARGET_WIDTH = 1920
        private const val TARGET_HEIGHT = 1080
        private const val EXTRA_PROBE_INDEX = "probe_index"
        private const val EXTRA_PREVIEW_INDEX = "preview_index"
        private const val RESULT_FILE = "batch-results.tsv"

        init {
            System.loadLibrary("heif_tile_probe")
        }
    }
}
