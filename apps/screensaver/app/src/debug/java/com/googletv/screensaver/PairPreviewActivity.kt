package com.googletv.screensaver

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import com.googletv.screensaver.playback.PairLook
import com.googletv.screensaver.playback.PairStyle
import com.googletv.screensaver.playback.PhotoSourceFactory
import com.googletv.screensaver.playback.Slide
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

/**
 * Debug-only: shows the pairs listed in `files/pair-test.json` (written by the developer with `adb`; it holds
 * paths relative to `originals`, so it stays off Git) through the real decode, label, `PairFit` and background
 * code. Entries are `{"style": "PINSTRIPES", "variant": 0, "a": "dir/x.heic", "b": "dir/y.jpg"}`; `style` may be
 * omitted for a pair on black. `adb shell am start -n com.googletv.screensaver/.PairPreviewActivity --ei index 2`
 * shows entry 2; D-pad right and left step through the list. Nothing here logs a path or a label.
 */
class PairPreviewActivity : Activity() {
    private lateinit var view: SlideshowView
    private lateinit var entries: JSONArray
    private var index = 0
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = SlideshowView(this)
        view.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContentView(view)
        entries = JSONArray(File(filesDir, "pair-test.json").readText())
        index = intent.getIntExtra("index", 0).coerceIn(0, entries.length() - 1)
        show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT -> { index = (index + 1) % entries.length(); show(); true }
        KeyEvent.KEYCODE_DPAD_LEFT -> { index = (index + entries.length() - 1) % entries.length(); show(); true }
        else -> super.onKeyDown(keyCode, event)
    }

    private fun show() {
        val entry = entries.getJSONObject(index)
        val style = entry.optString("style").takeIf { it.isNotBlank() }?.let { PairStyle.valueOf(it) }
        val look = style?.let { PairLook(it, entry.optInt("variant", 0)) }
        val source = PhotoSourceFactory.create(this, SlideshowPreferences(this))
        val metrics = resources.displayMetrics
        val shownIndex = index
        worker.execute {
            val startedAt = System.currentTimeMillis()
            val result = runCatching {
                fun load(key: String) = source.loadImage(Slide(SMB_ROOT + entry.getString(key).replace('/', '\\'), ""))
                val a = load("a")
                val b = load("b")
                val decoded = System.currentTimeMillis()
                val scene = PairScene.build(a.bitmap, b.bitmap, look, metrics.widthPixels, metrics.heightPixels) ?: error("no layout")
                Triple(a, b, scene) to (decoded - startedAt to System.currentTimeMillis() - decoded)
            }
            handler.post {
                result.fold(
                    onSuccess = { (pair, times) ->
                        val (a, b, scene) = pair
                        view.showPair(Slide("a", a.label.orEmpty()), a.bitmap, Slide("b", b.label.orEmpty()), b.bitmap, scene)
                        Log.i(TAG, "Pair $shownIndex shown (${look?.let { PairVariants.describe(it) } ?: "black"}): photos ${a.bitmap.width}x${a.bitmap.height} + ${b.bitmap.width}x${b.bitmap.height}, " +
                            "layout ${scene.layout.first} ${scene.layout.second} slices ${scene.layout.leftSlice}/${scene.layout.middleSlice}/${scene.layout.rightSlice}, decode ${times.first} ms, background ${times.second} ms")
                    },
                    onFailure = { Log.w(TAG, "Pair $shownIndex failed (${it.javaClass.simpleName})") },
                )
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PairPreview"
        const val SMB_ROOT = "Photos Library.photoslibrary\\originals\\"
    }
}
