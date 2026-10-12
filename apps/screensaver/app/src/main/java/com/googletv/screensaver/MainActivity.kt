package com.googletv.screensaver

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.WindowManager
import com.googletv.screensaver.playback.BundledSamplePhotoSource
import com.googletv.screensaver.playback.SlideshowPlayer

class MainActivity : Activity() {
    private lateinit var preferences: SlideshowPreferences
    private lateinit var intervalSummary: TextView
    private lateinit var intervalButtons: LinearLayout
    private lateinit var settingsScreen: View
    private var player: SlideshowPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preferences = SlideshowPreferences(this)
        settingsScreen = buildScreen()
        setContentView(settingsScreen)
        refreshIntervalControls()
    }

    override fun onResume() {
        super.onResume()
        if (player != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player?.start()
    }

    override fun onPause() {
        player?.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_BACK && player != null) {
            leavePreview()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun buildScreen(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(72), dp(44), dp(72), dp(44))
            setBackgroundColor(Color.rgb(16, 24, 33))
        }

        content.addView(text("Photo Screensaver", 38f, true))
        content.addView(text("A sample slideshow for your TV", 21f, false).apply {
            setTextColor(Color.rgb(190, 204, 218))
            setPadding(0, dp(8), 0, dp(26))
        })
        content.addView(text("Slide interval", 22f, true))

        intervalSummary = text("", 18f, false).apply {
            setTextColor(Color.rgb(190, 204, 218))
            setPadding(0, dp(8), 0, dp(14))
        }
        content.addView(intervalSummary)

        intervalButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(intervalButtons)

        val previewButton = button("Preview slideshow") {
            enterPreview()
        }.apply {
            setPadding(dp(24), 0, dp(24), 0)
            setBackgroundTintList(ColorStateList.valueOf(Color.rgb(48, 104, 155)))
        }
        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(24), 0, dp(12))
            addView(previewButton)
        }
        content.addView(previewRow)
        content.addView(text("Use the TV remote to choose an interval and start a preview.", 17f, false).apply {
            setTextColor(Color.rgb(190, 204, 218))
        })

        return content
    }

    private fun enterPreview() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = SlideshowView(this)
        setContentView(view)
        player = SlideshowPlayer(
            photoSource = BundledSamplePhotoSource(),
            intervalMillis = preferences::intervalMillis,
            showSlide = view::show,
            showEmptyState = view::showEmptyState,
        ).also { it.start() }
    }

    private fun leavePreview() {
        player?.stop()
        player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(settingsScreen)
        refreshIntervalControls()
    }

    private fun refreshIntervalControls() {
        val selectedSeconds = preferences.intervalSeconds()
        val selected = IntervalPreset.all.firstOrNull { it.seconds == selectedSeconds } ?: IntervalPreset.default
        intervalSummary.text = getString(R.string.current_interval, selected.label)
        intervalButtons.removeAllViews()
        var selectedButton: Button? = null

        IntervalPreset.all.forEach { preset ->
            val isSelected = preset.seconds == selected.seconds
            val intervalButton = button(preset.label) {
                preferences.setInterval(preset)
                refreshIntervalControls()
            }.apply {
                setBackgroundTintList(
                    ColorStateList.valueOf(
                        if (isSelected) Color.rgb(48, 104, 155) else Color.rgb(42, 52, 64),
                    ),
                )
            }
            if (isSelected) selectedButton = intervalButton
            intervalButtons.addView(
                intervalButton,
                LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginEnd = dp(12) },
            )
        }
        selectedButton?.let { button -> button.post { button.requestFocus() } }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        minHeight = dp(64)
        setOnClickListener { onClick() }
    }

    private fun text(value: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

}
