package com.googletv.screensaver

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.googletv.screensaver.playback.PhotoSourceFactory
import com.googletv.screensaver.playback.SlideshowPlayer

class MainActivity : Activity() {
    private lateinit var preferences: SlideshowPreferences
    private lateinit var intervalSummary: TextView
    private lateinit var intervalButtons: LinearLayout
    private lateinit var photoPoolSummary: TextView
    private lateinit var photoPoolButtons: LinearLayout
    private lateinit var photoLabelSummary: TextView
    private lateinit var photoLabelButtons: LinearLayout
    private lateinit var sampleSourceButton: Button
    private lateinit var smbSourceButton: Button
    private lateinit var smbFields: List<EditText>
    private lateinit var smbSourceNote: TextView
    private lateinit var smbMessage: TextView
    private lateinit var settingsScreen: View
    private var sourceMode = PhotoSourceMode.BUNDLED
    private var player: SlideshowPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = SlideshowPreferences(this)
        sourceMode = preferences.photoSourceMode()
        settingsScreen = buildScreen()
        setContentView(settingsScreen)
        refreshIntervalControls()
        refreshPhotoPoolControls()
        refreshPhotoLabelControls()
        refreshSourceControls()
        val initialFocus = if (sourceMode == PhotoSourceMode.SMB_GUEST) smbSourceButton else sampleSourceButton
        initialFocus.post { initialFocus.requestFocus() }
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

    override fun onDestroy() {
        player?.dispose()
        player = null
        super.onDestroy()
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
            setPadding(dp(72), dp(40), dp(72), dp(40))
            setBackgroundColor(Color.rgb(16, 24, 33))
        }

        content.addView(text(getString(R.string.app_name), 38f, true))
        content.addView(text(getString(R.string.app_description), 20f, false).apply {
            setTextColor(Color.rgb(190, 204, 218))
            setPadding(0, dp(6), 0, dp(14))
        })
        content.addView(text("Build ${BuildConfig.BUILD_TAG} · ${BuildConfig.BUILD_TIME}", 14f, false).apply {
            setTextColor(Color.rgb(145, 160, 176))
            setPadding(0, 0, 0, dp(14))
        })
        content.addView(text(getString(R.string.photo_source_title), 22f, true))
        val sourceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        sampleSourceButton = button(getString(R.string.sample_source)) {
            selectSource(PhotoSourceMode.BUNDLED)
        }
        smbSourceButton = button(getString(R.string.smb_guest_source)) {
            selectSource(PhotoSourceMode.SMB_GUEST)
        }
        sourceRow.addView(sampleSourceButton, LinearLayout.LayoutParams(0, dp(60), 1f).apply {
            marginEnd = dp(12)
        })
        sourceRow.addView(smbSourceButton, LinearLayout.LayoutParams(0, dp(60), 1f))
        content.addView(sourceRow)

        val smbHost = input(getString(R.string.smb_host_hint), preferences.smbSourceConfig().host)
        val smbShare = input(getString(R.string.smb_share_hint), preferences.smbSourceConfig().share)
        val smbPath = input(getString(R.string.smb_path_hint), preferences.smbSourceConfig().rootPath)
        smbFields = listOf(smbHost, smbShare, smbPath)
        smbFields.forEach(content::addView)
        smbSourceNote = text(getString(R.string.smb_source_note), 15f, false).apply {
            setTextColor(Color.rgb(190, 204, 218))
            setPadding(0, dp(3), 0, dp(8))
        }
        content.addView(smbSourceNote)
        smbMessage = text("", 16f, false).apply {
            setTextColor(Color.rgb(255, 190, 130))
            visibility = View.GONE
        }
        content.addView(smbMessage)

        content.addView(text(getString(R.string.slide_interval_title), 22f, true).apply {
            setPadding(0, dp(12), 0, 0)
        })
        intervalSummary = text("", 18f, false).apply {
            setTextColor(Color.rgb(190, 204, 218))
            setPadding(0, dp(6), 0, dp(10))
        }
        content.addView(intervalSummary)
        intervalButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(intervalButtons)

        content.addView(text(getString(R.string.photo_pool_title), 22f, true).apply {
            setPadding(0, dp(16), 0, 0)
        })
        photoPoolSummary = text("", 18f, false).apply {
            setTextColor(Color.rgb(190, 204, 218))
            setPadding(0, dp(6), 0, dp(10))
        }
        content.addView(photoPoolSummary)
        photoPoolButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(photoPoolButtons)

        content.addView(text(getString(R.string.photo_label_title), 22f, true).apply {
            setPadding(0, dp(16), 0, 0)
        })
        photoLabelSummary = text("", 18f, false).apply {
            setTextColor(Color.rgb(190, 204, 218))
            setPadding(0, dp(6), 0, dp(10))
        }
        content.addView(photoLabelSummary)
        photoLabelButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(photoLabelButtons)

        val previewButton = button(getString(R.string.preview_slideshow)) {
            enterPreview()
        }.apply { setPadding(dp(24), 0, dp(24), 0) }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(8))
            addView(previewButton)
        })

        return ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
    }

    private fun input(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        textSize = 17f
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(160, 174, 190))
        setPadding(dp(16), dp(8), dp(16), dp(8))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply {
            bottomMargin = dp(6)
        }
    }

    private fun selectSource(mode: PhotoSourceMode) {
        sourceMode = mode
        preferences.setPhotoSourceMode(mode)
        smbMessage.visibility = View.GONE
        refreshSourceControls()
    }

    private fun refreshSourceControls() {
        sampleSourceButton.setBackgroundTintList(
            ColorStateList.valueOf(if (sourceMode == PhotoSourceMode.BUNDLED) SELECTED_COLOR else BUTTON_COLOR),
        )
        smbSourceButton.setBackgroundTintList(
            ColorStateList.valueOf(if (sourceMode == PhotoSourceMode.SMB_GUEST) SELECTED_COLOR else BUTTON_COLOR),
        )
        smbFields.forEach { it.visibility = if (sourceMode == PhotoSourceMode.SMB_GUEST) View.VISIBLE else View.GONE }
        smbSourceNote.visibility = if (sourceMode == PhotoSourceMode.SMB_GUEST) View.VISIBLE else View.GONE
        smbMessage.visibility = if (sourceMode == PhotoSourceMode.SMB_GUEST && smbMessage.text.isNotBlank()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun enterPreview() {
        if (sourceMode == PhotoSourceMode.SMB_GUEST) {
            val config = preferences.smbSourceConfig().copy(
                host = smbFields[0].text.toString().trim(),
                share = smbFields[1].text.toString().trim(),
                rootPath = smbFields[2].text.toString().trim(),
            )
            try {
                preferences.saveSmbSourceConfig(config)
                PhotoSourceFactory.create(this, preferences)
            } catch (error: IllegalArgumentException) {
                smbMessage.text = error.message ?: getString(R.string.smb_config_error)
                smbMessage.visibility = View.VISIBLE
                return
            }
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = com.googletv.screensaver.SlideshowView(this)
        setContentView(view)
        player?.dispose()
        player = SlideshowPlayer(
            photoSource = PhotoSourceFactory.create(this, preferences),
            intervalMillis = preferences::intervalMillis,
            showSlide = view::show,
            showLoading = view::showLoadingState,
            showEmptyState = view::showEmptyState,
            showError = view::showErrorState,
            fallbackSource = PhotoSourceFactory.createFallback(this, preferences),
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

        IntervalPreset.all.forEach { preset ->
            val isSelected = preset.seconds == selected.seconds
            val intervalButton = button(preset.label) {
                preferences.setInterval(preset)
                refreshIntervalControls()
            }.apply {
                setBackgroundTintList(ColorStateList.valueOf(if (isSelected) SELECTED_COLOR else BUTTON_COLOR))
            }
            intervalButtons.addView(
                intervalButton,
                LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginEnd = dp(10) },
            )
        }
    }

    private fun refreshPhotoPoolControls() {
        val selected = preferences.photoPoolSize()
        photoPoolSummary.text = getString(R.string.current_photo_pool, selected.label)
        photoPoolButtons.removeAllViews()
        PhotoPoolSize.all.forEach { size ->
            val selectedSize = size == selected
            val option = button(size.label) {
                preferences.setPhotoPoolSize(size)
                refreshPhotoPoolControls()
            }.apply {
                setBackgroundTintList(ColorStateList.valueOf(if (selectedSize) SELECTED_COLOR else BUTTON_COLOR))
            }
            photoPoolButtons.addView(option, compactOptionLayout())
        }
    }

    private fun refreshPhotoLabelControls() {
        val selected = preferences.photoLabelMode()
        photoLabelSummary.text = getString(R.string.current_photo_label, selected.label)
        photoLabelButtons.removeAllViews()
        PhotoLabelMode.all.forEach { mode ->
            val selectedMode = mode == selected
            val option = button(mode.label) {
                preferences.setPhotoLabelMode(mode)
                refreshPhotoLabelControls()
            }.apply {
                setBackgroundTintList(ColorStateList.valueOf(if (selectedMode) SELECTED_COLOR else BUTTON_COLOR))
            }
            photoLabelButtons.addView(option, compactOptionLayout())
        }
    }

    private fun compactOptionLayout() = LinearLayout.LayoutParams(
        0,
        dp(60),
        1f,
    ).apply { marginEnd = dp(8) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 17f
        isAllCaps = false
        minHeight = dp(60)
        setOnClickListener { onClick() }
    }

    private fun text(value: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val SELECTED_COLOR = Color.rgb(48, 104, 155)
        private val BUTTON_COLOR = Color.rgb(42, 52, 64)
    }
}
