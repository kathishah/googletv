package com.googletv.screensaver

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.googletv.screensaver.playback.PhotoSourceFactory
import com.googletv.screensaver.playback.PairingConfig
import com.googletv.screensaver.playback.SlideshowPlayer
import com.googletv.screensaver.playback.AlbumEntry
import com.googletv.screensaver.playback.AlbumCatalogProgress
import com.googletv.screensaver.playback.SmbGuestPhotoSource
import com.googletv.screensaver.playback.validated
import java.util.concurrent.Executors
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class MainActivity : Activity() {
    private lateinit var preferences: SlideshowPreferences
    private lateinit var settingsTable: LinearLayout
    private lateinit var settingsScreen: View
    private lateinit var smbSettingsScreen: View
    private lateinit var smbFields: List<EditText>
    private lateinit var smbMessage: TextView
    private lateinit var albumSummary: TextView
    private lateinit var albumError: TextView
    private lateinit var typeNote: TextView
    private lateinit var layoutNote: TextView
    private lateinit var settingsStatus: TextView
    private val choiceButtons = mutableMapOf<String, List<Button>>()
    private val albumExecutor = Executors.newSingleThreadExecutor()
    private var sourceMode = PhotoSourceMode.BUNDLED
    private var selectionMode = PhotoSelectionMode.ALL
    private var mediaType = MediaType.default
    private var intervalPreset = IntervalPreset.default
    private var poolSize = PhotoPoolSize.default
    private var labelMode = PhotoLabelMode.default
    private var layoutMode = LayoutMode.default
    private var selectedAlbumIds = emptySet<String>()
    private var selectedAlbumPhotoCount = 0
    private var selectedAlbumVideoCount = -1
    private var draftSmbConfig = com.googletv.screensaver.playback.SmbSourceConfig(
        "", SlideshowPreferences.DEFAULT_SMB_SHARE, SlideshowPreferences.DEFAULT_SMB_PATH,
    )
    private var player: SlideshowPlayer? = null
    private var pickerOpen = false
    private var smbSettingsOpen = false
    private var catalogLoadToken = 0
    private var albumCoverSource: SmbGuestPhotoSource? = null
    private val requestedCoverIds = mutableSetOf<String>()
    private val albumCoverCache = LinkedHashMap<String, Bitmap>(32, 0.75f, true)
    private val albumCoverCacheDirectory by lazy { File(filesDir, "album-cover-thumbnails") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = SlideshowPreferences(this)
        restoreDraftFromPreferences()
        settingsScreen = buildScreen()
        smbSettingsScreen = buildSmbSettingsScreen()
        setContentView(settingsScreen)
        refreshSettingsControls()
        val initialFocus = choiceButtons["source"]?.getOrNull(if (sourceMode == PhotoSourceMode.SMB_GUEST) 1 else 0)
        initialFocus?.post { initialFocus.requestFocus() }
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
        albumCoverSource?.close()
        albumCoverSource = null
        albumExecutor.shutdownNow()
        pickerOpen = false
        catalogLoadToken++
        albumCoverCache.clear()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_BACK && pickerOpen) {
            finishAlbumPicker()
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_BACK && smbSettingsOpen) {
            saveSmbDraftFromFields()
            smbSettingsOpen = false
            setContentView(settingsScreen)
            focusChoice("source", if (sourceMode == PhotoSourceMode.SMB_GUEST) 1 else 0)
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_BACK && player != null) {
            leavePreview()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(14), dp(48), dp(18))
            setBackgroundColor(Color.rgb(16, 24, 33))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        header.addView(text(getString(R.string.app_name), 20f, true).apply {
            setTextColor(Color.rgb(150, 199, 236))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("Build ${BuildConfig.BUILD_TAG} · ${BuildConfig.BUILD_TIME}", 14f, false).apply {
            setTextColor(Color.rgb(145, 160, 176))
        })
        root.addView(header)
        root.addView(text("Slideshow settings", 27f, true).apply {
            setPadding(0, dp(3), 0, dp(4))
        })

        settingsTable = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(settingsTable, LinearLayout.LayoutParams(-1, -2))
        root.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        albumError = text("", 14f, false).apply {
            setTextColor(Color.rgb(255, 190, 130))
            visibility = View.GONE
        }
        smbMessage = text("", 14f, false).apply {
            setTextColor(Color.rgb(255, 190, 130))
            visibility = View.GONE
        }
        root.addView(albumError)
        root.addView(smbMessage)
        typeNote = text("", 13f, false).apply {
            setTextColor(Color.rgb(150, 199, 236))
            visibility = View.GONE
            setPadding(0, dp(1), 0, dp(1))
        }
        root.addView(typeNote)
        settingsStatus = text("Changes take effect when saved.", 13f, false).apply {
            setTextColor(Color.rgb(170, 184, 199))
            setPadding(0, dp(1), 0, dp(1))
        }
        root.addView(settingsStatus)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        actions.addView(button("Preview") { saveDraftAndPreview() }, LinearLayout.LayoutParams(dp(128), dp(48)).apply { marginStart = dp(8) })
        actions.addView(button("Save") { saveDraft() }.apply {
            setBackgroundTintList(ColorStateList.valueOf(SELECTED_COLOR))
        }, LinearLayout.LayoutParams(dp(128), dp(48)).apply { marginStart = dp(8) })
        actions.addView(button("Cancel") { cancelDraft() }, LinearLayout.LayoutParams(dp(128), dp(48)).apply { marginStart = dp(8) })
        root.addView(actions)
        renderSettingsTable()
        return root
    }

    private fun buildSmbSettingsScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(96), dp(48), dp(96), dp(48))
            setBackgroundColor(Color.rgb(16, 24, 33))
        }
        root.addView(text("SMB settings", 32f, true))
        root.addView(text("Photos share connection", 20f, false).apply {
            setTextColor(Color.rgb(190, 204, 218))
            setPadding(0, dp(8), 0, dp(24))
        })
        val host = input(getString(R.string.smb_host_hint), draftSmbConfig.host)
        val share = input(getString(R.string.smb_share_hint), draftSmbConfig.share)
        val path = input(getString(R.string.smb_path_hint), draftSmbConfig.rootPath)
        smbFields = listOf(host, share, path)
        listOf("IP address", "Share name", "Library path").zip(smbFields).forEach { (label, field) ->
            root.addView(text(label, 16f, true).apply { setPadding(0, dp(8), 0, dp(5)) })
            root.addView(field, LinearLayout.LayoutParams(-1, dp(54)))
        }
        root.addView(text(getString(R.string.smb_source_note), 14f, false).apply {
            setTextColor(Color.rgb(170, 184, 199))
            setPadding(0, dp(14), 0, dp(18))
        })
        root.addView(button("Back") {
            saveSmbDraftFromFields()
            smbSettingsOpen = false
            setContentView(settingsScreen)
            focusChoice("source", if (sourceMode == PhotoSourceMode.SMB_GUEST) 1 else 0)
        }, LinearLayout.LayoutParams(dp(180), dp(58)))
        return root
    }

    private fun renderSettingsTable() {
        settingsTable.removeAllViews()
        choiceButtons.clear()
        addChoiceRow("Photo source", "source", listOf(
            "Sample pics" to { sourceMode = PhotoSourceMode.BUNDLED; refreshSettingsControls() },
            "SMB share" to { sourceMode = PhotoSourceMode.SMB_GUEST; refreshSettingsControls() },
            "SMB settings" to {
                saveSmbDraftFromFields()
                smbSettingsOpen = true
                setContentView(smbSettingsScreen)
                smbFields.firstOrNull()?.requestFocus()
            },
        ))
        val photos = addChoiceRow("Media", "photos", listOf(
            "All" to { selectionMode = PhotoSelectionMode.ALL; refreshSettingsControls() },
            "Choose Albums" to { openAlbumPicker() },
        ))
        albumSummary = text("", 12f, false).apply {
            setTextColor(Color.rgb(170, 184, 199))
            setPadding(dp(8), 0, 0, 0)
        }
        photos.second.addView(albumSummary)
        addChoiceRow("Type", "type", MediaType.all.map { type -> type.label to {
            mediaType = type
            refreshSettingsControls()
        } })
        addChoiceRow("Pool", "pool", PhotoPoolSize.all.map { it.label to {
            poolSize = it
            refreshSettingsControls()
        } })
        addChoiceRow("Interval", "interval", IntervalPreset.all.map { it.label.replace(" sec", " secs") to {
            intervalPreset = it
            refreshSettingsControls()
        } })
        val layout = addChoiceRow("Layout", "layout", LayoutMode.all.map { mode -> mode.label to {
            layoutMode = mode
            refreshSettingsControls()
        } })
        layoutNote = text(LAYOUT_NOTE, 12f, false).apply {
            setTextColor(Color.rgb(170, 184, 199))
            setPadding(dp(8), 0, 0, 0)
        }
        layout.second.addView(layoutNote)
        addChoiceRow("Label", "label", listOf(
            "Location + date" to { labelMode = PhotoLabelMode.LOCATION_AND_DATE; refreshSettingsControls() },
            "None" to { labelMode = PhotoLabelMode.NONE; refreshSettingsControls() },
        ))
        refreshSettingsControls()
    }

    private fun addChoiceRow(
        title: String,
        key: String,
        choices: List<Pair<String, () -> Unit>>,
    ): Pair<LinearLayout, LinearLayout> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(23, 35, 47))
            setPadding(dp(12), dp(3), dp(8), dp(3))
            val background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(23, 35, 47)); cornerRadius = dp(8).toFloat()
            }
            this.background = background
        }
        val label = text(title, 16f, true)
        row.addView(label, LinearLayout.LayoutParams(dp(165), dp(38)))
        val optionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val buttons = choices.mapIndexed { index, (caption, action) ->
            button(caption, action).apply {
                textSize = 13f
                minHeight = dp(36)
                setPadding(dp(5), 0, dp(5), 0)
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    if (index < choices.lastIndex) marginEnd = dp(7)
                }
            }.also(optionRow::addView)
        }
        choiceButtons[key] = buttons
        row.addView(optionRow, LinearLayout.LayoutParams(0, -2, 1f))
        settingsTable.addView(row, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(3) })
        return row to optionRow
    }

    private fun refreshSettingsControls() {
        fun mark(key: String, selected: Int) {
            choiceButtons[key].orEmpty().forEachIndexed { index, view ->
                view.setBackgroundTintList(ColorStateList.valueOf(if (index == selected) SELECTED_COLOR else BUTTON_COLOR))
            }
        }
        mark("source", if (sourceMode == PhotoSourceMode.BUNDLED) 0 else 1)
        mark("photos", if (selectionMode == PhotoSelectionMode.ALL) 0 else 1)
        mark("type", MediaType.all.indexOf(mediaType).coerceAtLeast(0))
        mark("pool", PhotoPoolSize.all.indexOf(poolSize).coerceAtLeast(0))
        mark("interval", IntervalPreset.all.indexOfFirst { it.seconds == intervalPreset.seconds }.coerceAtLeast(0))
        mark("layout", LayoutMode.all.indexOf(layoutMode).coerceAtLeast(0))
        mark("label", if (labelMode == PhotoLabelMode.NONE) 1 else 0)
        choiceButtons["pool"].orEmpty().forEach { it.isEnabled = selectionMode == PhotoSelectionMode.ALL }
        albumSummary.text = if (selectionMode == PhotoSelectionMode.ALBUMS) {
            MediaTypeRules.summaryText(mediaType, selectedAlbumIds.size, selectedAlbumPhotoCount, selectedAlbumVideoCount)
        } else ""
        albumSummary.visibility = if (selectionMode == PhotoSelectionMode.ALBUMS) View.VISIBLE else View.GONE
        val note = MediaTypeRules.typeNote(mediaType)
        typeNote.text = note.orEmpty()
        typeNote.visibility = if (note != null) View.VISIBLE else View.GONE
    }

    private fun focusChoice(key: String, index: Int) {
        choiceButtons[key]?.getOrNull(index)?.requestFocus()
    }

    private fun restoreDraftFromPreferences() {
        sourceMode = preferences.photoSourceMode()
        selectionMode = preferences.photoSelectionMode()
        mediaType = preferences.mediaType()
        intervalPreset = IntervalPreset.all.firstOrNull { it.seconds == preferences.intervalSeconds() } ?: IntervalPreset.default
        poolSize = preferences.photoPoolSize()
        layoutMode = preferences.layoutMode()
        labelMode = preferences.photoLabelMode().let {
            if (it == PhotoLabelMode.DATE_ONLY) PhotoLabelMode.LOCATION_AND_DATE else it
        }
        selectedAlbumIds = preferences.selectedAlbumIds()
        selectedAlbumPhotoCount = preferences.selectedAlbumPhotoCount()
        selectedAlbumVideoCount = preferences.selectedAlbumVideoCount()
        draftSmbConfig = preferences.smbSourceConfig()
    }

    private fun saveSmbDraftFromFields() {
        if (::smbFields.isInitialized) {
            draftSmbConfig = draftSmbConfig.copy(
                host = smbFields[0].text.toString().trim(),
                share = smbFields[1].text.toString().trim(),
                rootPath = smbFields[2].text.toString().trim(),
            )
        }
    }

    private fun saveDraft(): Boolean {
        saveSmbDraftFromFields()
        return runCatching {
            if (sourceMode == PhotoSourceMode.SMB_GUEST) draftSmbConfig.validated()
            preferences.setPhotoSourceMode(sourceMode)
            preferences.setPhotoSelectionMode(selectionMode)
            preferences.setMediaType(mediaType)
            preferences.setInterval(intervalPreset)
            preferences.setPhotoPoolSize(poolSize)
            preferences.setLayoutMode(layoutMode)
            preferences.setPhotoLabelMode(labelMode)
            preferences.saveSmbSourceConfig(draftSmbConfig)
            preferences.setAlbumSelection(selectedAlbumIds, selectedAlbumPhotoCount, selectedAlbumVideoCount)
            // A trace for the log (no private values) so an unexpected change in settings can be dated.
            android.util.Log.i("Settings", "Saved: Type=$mediaType, Layout=$layoutMode, Media=$selectionMode, source=$sourceMode, ${selectedAlbumIds.size} albums")
            settingsStatus.text = "Settings saved."
            true
        }.getOrElse {
            smbMessage.text = it.message ?: getString(R.string.smb_config_error)
            smbMessage.visibility = View.VISIBLE
            false
        }
    }

    private fun cancelDraft() {
        restoreDraftFromPreferences()
        smbFields.forEachIndexed { index, field ->
            field.setText(listOf(draftSmbConfig.host, draftSmbConfig.share, draftSmbConfig.rootPath)[index])
        }
        albumError.visibility = View.GONE
        smbMessage.visibility = View.GONE
        settingsStatus.text = "Changes discarded."
        refreshSettingsControls()
        focusChoice("source", if (sourceMode == PhotoSourceMode.BUNDLED) 0 else 1)
    }

    private fun saveDraftAndPreview() {
        if (saveDraft()) enterPreview()
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

    private fun openAlbumPicker(refresh: Boolean = false) {
        albumCoverSource?.close()
        albumCoverSource = null
        requestedCoverIds.clear()
        pickerOpen = true
        selectionMode = PhotoSelectionMode.ALBUMS
        preferences.setPhotoSelectionMode(PhotoSelectionMode.ALBUMS)
        val token = ++catalogLoadToken
        saveSmbDraftFromFields()
        val config = draftSmbConfig.copy(
            host = smbFields[0].text.toString().trim(), share = smbFields[1].text.toString().trim(),
            rootPath = smbFields[2].text.toString().trim(),
        )
        draftSmbConfig = config
        val loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(64), dp(40), dp(64), dp(40))
            setBackgroundColor(Color.rgb(16, 24, 33))
        }
        loading.addView(text("Choose albums", 30f, true))
        loading.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            topMargin = dp(24); bottomMargin = dp(18)
        })
        val transferProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = true
        }
        loading.addView(transferProgress, LinearLayout.LayoutParams(-1, dp(12)).apply {
            topMargin = dp(6); bottomMargin = dp(12)
        })
        val progressText = text("Preparing album list…", 18f, false).apply { setTextColor(Color.rgb(190, 204, 218)) }
        loading.addView(progressText)
        setContentView(loading)
        albumExecutor.execute {
            val result = runCatching {
                val source = SmbGuestPhotoSource(
                    config, 640, 360, labelMode = labelMode, fallbackCacheDirectory = cacheDir,
                    catalogCacheDirectory = filesDir,
                )
                try {
                    source.loadAlbumIndex(
                        java.io.File(filesDir, "photos-album-catalog-v1.json"),
                        refresh = refresh,
                    ) { update -> runOnUiThread {
                        if (pickerOpen && token == catalogLoadToken) {
                            val copied = update.bytesCopied
                            val total = update.totalBytes
                            if (copied != null && total != null && total > 0L) {
                                transferProgress.isIndeterminate = false
                                val fraction = (copied.coerceAtMost(total) * transferProgress.max / total).toInt()
                                transferProgress.progress = fraction
                                val percent = (fraction * 100 / transferProgress.max)
                                progressText.text = "${update.message} · $percent% (${formatBytes(copied)} / ${formatBytes(total)})"
                            } else {
                                transferProgress.isIndeterminate = true
                                progressText.text = "${update.message}…"
                            }
                        }
                    } }
                } finally {
                    source.close()
                }
            }
            runOnUiThread {
                if (isFinishing || !pickerOpen || token != catalogLoadToken) return@runOnUiThread
                result.fold(
                    { index -> showAlbumPicker(index.albums, index.sourceModifiedMillis) },
                    { err -> showAlbumPickerError(
                        if (err is com.googletv.screensaver.playback.AlbumIndexException) err.message ?: "Album catalog unavailable"
                        else "Couldn't build the album catalog. Check the Photos share and try again.",
                    ) },
                )
            }
        }
    }

    private fun showAlbumPickerError(message: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(72), dp(40), dp(72), dp(40))
            setBackgroundColor(Color.rgb(16, 24, 33))
        }
        root.addView(text("Choose albums", 30f, true))
        root.addView(text(message, 19f, false).apply {
            setTextColor(Color.rgb(255, 190, 130))
            setPadding(0, dp(18), 0, dp(24))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val retry = button("Retry / Refresh") { openAlbumPicker(refresh = true) }
        actions.addView(retry)
        actions.addView(button("Back to settings") {
            closeAlbumPicker()
            setContentView(settingsScreen)
            refreshSettingsControls()
        }, LinearLayout.LayoutParams(-2, dp(56)).apply { marginStart = dp(12) })
        root.addView(actions)
        setContentView(root)
        retry.requestFocus()
    }

    private fun showAlbumPicker(albums: List<AlbumEntry>, sourceModifiedMillis: Long? = null) {
        albumCoverSource = SmbGuestPhotoSource(draftSmbConfig, 480, 300, labelMode = labelMode, fallbackCacheDirectory = cacheDir)
        val staged = selectedAlbumIds.toMutableSet()
        var sortName = false
        val availableIds = albums.map { it.id }.toSet()
        // Type Videos lists only albums that have videos; hidden albums keep any selection they had.
        val visibleAlbums = albums.filter { MediaTypeRules.showsAlbum(mediaType, it.photoCount, it.videoCount) }
        val visibleIds = visibleAlbums.map { it.id }.toSet()
        android.util.Log.i("AlbumPicker", "Showing ${visibleAlbums.size} of ${albums.size} albums")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(20))
            setBackgroundColor(Color.rgb(16, 24, 33))
        }
        val topLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topLine.addView(text(getString(R.string.app_name), 18f, true), LinearLayout.LayoutParams(0, dp(28), 1f))
        topLine.addView(
            text("PHOTO SOURCE · SMB SHARE" + if (mediaType.includesVideos) " · TYPE: ${mediaType.label.uppercase()}" else "", 12f, true)
                .apply { setTextColor(Color.rgb(181, 193, 204)) },
        )
        root.addView(topLine)

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(15), 0, dp(5))
        }
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBlock.addView(text("Choose albums", 30f, true))
        val dataAge = sourceModifiedMillis?.let {
            "  ·  Library data as of " + java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(it))
        }.orEmpty()
        titleBlock.addView(text("Select the albums you want to see in your slideshow.$dataAge${MediaTypeRules.subtitleSuffix(mediaType)}", 14f, false).apply {
            setTextColor(Color.rgb(181, 193, 204))
            setPadding(0, dp(4), 0, 0)
        })
        heading.addView(titleBlock, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(text("✓  Cover previews stay on this TV", 12f, true).apply {
            setTextColor(Color.rgb(195, 232, 209))
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = roundedBackground(Color.rgb(22, 54, 38), Color.rgb(57, 117, 83), 999)
        })
        root.addView(heading)

        val sortButton = button("Sort: Newest ▾") { }
        sortButton.textSize = 13f
        sortButton.minHeight = dp(44)
        sortButton.setPadding(dp(12), 0, dp(12), 0)
        val selectAll = button("Select all") { }
        val clearAll = button("Clear all") { }
        val refresh = button("Refresh") { openAlbumPicker(refresh = true) }
        listOf(selectAll, clearAll, refresh).forEach {
            it.textSize = 13f
            it.minHeight = dp(44)
            it.setPadding(dp(12), 0, dp(12), 0)
            it.setBackgroundTintList(ColorStateList.valueOf(BUTTON_COLOR))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        controls.addView(text("Albums", 13f, true).apply { setTextColor(Color.rgb(181, 193, 204)) }, LinearLayout.LayoutParams(0, dp(44), 1f))
        controls.addView(refresh, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(8) })
        controls.addView(sortButton, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(8) })
        controls.addView(selectAll, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(8) })
        controls.addView(clearAll, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(controls)

        val notice = text("", 13f, false).apply {
            setTextColor(Color.rgb(255, 190, 130))
            visibility = View.GONE
            setPadding(0, dp(3), 0, dp(3))
        }
        val removeUnavailable = button("Remove unavailable") { }
        removeUnavailable.textSize = 12f
        removeUnavailable.minHeight = dp(40)
        val noticeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        noticeRow.addView(notice, LinearLayout.LayoutParams(0, -2, 1f))
        noticeRow.addView(removeUnavailable, LinearLayout.LayoutParams(-2, dp(40)))
        removeUnavailable.visibility = View.GONE
        root.addView(noticeRow)

        val carousel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(12), dp(18), dp(12), dp(22))
        }
        val horizontalScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(5), 0, dp(8))
            addView(carousel, android.view.ViewGroup.LayoutParams(-2, -1))
        }
        root.addView(horizontalScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val cards = linkedMapOf<String, FrameLayout>()
        val coverViews = linkedMapOf<String, ImageView>()
        val checks = linkedMapOf<String, TextView>()
        val orderedAlbums = {
            if (sortName) visibleAlbums.sortedWith(compareBy<AlbumEntry> { it.title.lowercase() })
            else visibleAlbums.sortedWith(compareByDescending<AlbumEntry> { it.yearMonth ?: "" }.thenBy { it.title.lowercase() })
        }
        fun photoCount(): Int = albums.asSequence()
            .filter { it.id in staged }
            .flatMap { it.paths.asSequence() }
            .toSet()
            .size
        fun videoCount(): Int = albums.asSequence()
            .filter { it.id in staged }
            .flatMap { album -> album.videos.asSequence().map { it.path } }
            .toSet()
            .size
        val total = text("", 22f, true)
        fun updateSelectionUi() {
            selectedAlbumIds = staged.toSet()
            selectedAlbumPhotoCount = photoCount()
            selectedAlbumVideoCount = videoCount()
            selectionMode = PhotoSelectionMode.ALBUMS
            preferences.setAlbumSelection(selectedAlbumIds, selectedAlbumPhotoCount, selectedAlbumVideoCount)
            preferences.setPhotoSelectionMode(PhotoSelectionMode.ALBUMS)
            cards.forEach { (id, card) ->
                val selected = id in staged
                val background = card.background as? android.graphics.drawable.GradientDrawable
                background?.setStroke(dp(if (selected) 2 else 1), if (selected) Color.rgb(98, 168, 232) else Color.rgb(74, 91, 108))
                checks[id]?.apply {
                    text = if (selected) "✓" else ""
                    (background as? android.graphics.drawable.GradientDrawable)?.setColor(
                        if (selected) Color.rgb(48, 104, 155) else 0x9912202B.toInt(),
                    )
                }
                card.contentDescription = "${albums.first { it.id == id }.title}, ${if (selected) "selected" else "not selected"}"
            }
            val unavailable = (staged - availableIds).size
            val noItems = visibleAlbums.count {
                it.id in staged && MediaTypeRules.eligibleCount(mediaType, it.photoCount, it.videoCount) == 0
            }
            val hiddenSelected = albums.count { it.id in staged && it.id !in visibleIds }
            val warning = when {
                visibleAlbums.isEmpty() -> "No albums have videos. Choose another Type in settings."
                unavailable > 0 -> "$unavailable previously selected album(s) aren't in the current catalog. Their selection is preserved."
                noItems > 0 -> MediaTypeRules.emptySelectionWarning(mediaType, noItems).orEmpty()
                MediaTypeRules.hiddenSelectionNote(mediaType, hiddenSelected) != null -> MediaTypeRules.hiddenSelectionNote(mediaType, hiddenSelected).orEmpty()
                staged.isEmpty() -> "No albums selected. Choose one or more albums, or select All photos in settings."
                else -> ""
            }
            notice.text = warning
            notice.visibility = if (warning.isNotEmpty()) View.VISIBLE else View.GONE
            removeUnavailable.visibility = if (unavailable > 0) View.VISIBLE else View.GONE
            val shownSelected = if (mediaType == MediaType.VIDEOS) staged.count { it in visibleIds } else staged.size
            total.text = MediaTypeRules.totalText(mediaType, shownSelected, photoCount(), videoCount())
        }
        fun persistStagedSelection() {
            updateSelectionUi()
            settingsStatus.text = "Album selection saved."
        }
        fun makeCard(album: AlbumEntry): FrameLayout {
            val cardBackground = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(27, 40, 51))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(if (album.id in staged) 2 else 1), if (album.id in staged) Color.rgb(98, 168, 232) else Color.rgb(74, 91, 108))
            }
            val card = FrameLayout(this).apply {
                isFocusable = true
                isClickable = true
                clipToOutline = true
                background = cardBackground
                contentDescription = "${album.title}, ${if (album.id in staged) "selected" else "not selected"}"
            }
            val cover = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = album.title
                background = android.graphics.drawable.GradientDrawable().apply {
                    colors = intArrayOf(Color.rgb(72, 97, 112), Color.rgb(174, 128, 85), Color.rgb(211, 191, 137))
                    orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
                }
            }
            card.addView(cover, FrameLayout.LayoutParams(-1, -1))
            val shade = View(this).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, 0x6608111B, 0xF208111B.toInt()),
                )
            }
            card.addView(shade, FrameLayout.LayoutParams(-1, dp(104), Gravity.BOTTOM))
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM
                setPadding(dp(12), dp(8), dp(12), dp(10))
            }
            copy.addView(text(album.title, 14f, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            copy.addView(text(MediaTypeRules.cardCountText(mediaType, album.photoCount, album.videoCount), 12f, false).apply {
                setTextColor(Color.rgb(225, 234, 241))
                setPadding(0, dp(2), 0, 0)
            })
            card.addView(copy, FrameLayout.LayoutParams(-1, dp(90), Gravity.BOTTOM))
            val check = TextView(this).apply {
                text = if (album.id in staged) "✓" else ""
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(7).toFloat()
                    setColor(if (album.id in staged) Color.rgb(48, 104, 155) else 0x9912202B.toInt())
                    setStroke(dp(2), if (album.id in staged) Color.rgb(128, 198, 255) else Color.WHITE)
                }
            }
            card.addView(check, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(8), dp(8), 0)
            })
            if (MediaTypeRules.showsVideoBadge(mediaType, album.videoCount)) {
                card.addView(text("▶ video", 11f, true).apply {
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    background = roundedBackground(0xCC0B141D.toInt(), Color.rgb(131, 149, 166), 999)
                }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(8), dp(8), 0, 0) })
            }
            if (MediaTypeRules.isEmptyForType(mediaType, album.photoCount, album.videoCount)) card.alpha = 0.6f
            cards[album.id] = card
            coverViews[album.id] = cover
            checks[album.id] = check
            card.setOnFocusChangeListener { focusedView, focused ->
                focusedView.foreground = if (focused) focusOutline() else null
                if (focused) loadAlbumCover(album, cover)
            }
            card.setOnClickListener {
                if (!staged.add(album.id)) staged.remove(album.id)
                persistStagedSelection()
            }
            return card
        }
        fun reorderCards(focusId: String? = null) {
            carousel.removeAllViews()
            orderedAlbums().forEachIndexed { index, album ->
                val card = cards[album.id] ?: makeCard(album)
                carousel.addView(card, LinearLayout.LayoutParams(dp(190), dp(220)).apply {
                    if (index > 0) marginStart = dp(16)
                })
            }
            val focusAlbum = focusId?.let(cards::get)
            focusAlbum?.requestFocus()
        }
        val doneButton = button("Done") { finishAlbumPicker() }.apply {
            textSize = 14f
            minWidth = dp(124)
            minHeight = dp(48)
            setPadding(dp(18), 0, dp(18), 0)
            setBackgroundTintList(ColorStateList.valueOf(SELECTED_COLOR))
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.rgb(67, 84, 101))
            }
        }
        val summary = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(summary, LinearLayout.LayoutParams(0, dp(56), 1f))
        summary.addView(total)
        val savedNote = text("Saved automatically · Back also returns", 12f, false).apply {
            setTextColor(Color.rgb(181, 193, 204))
            setPadding(dp(14), 0, dp(18), 0)
        }
        bottom.addView(savedNote)
        bottom.addView(doneButton, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(8) })
        root.addView(bottom)

        selectAll.setOnClickListener {
            staged.addAll(visibleIds)
            persistStagedSelection()
        }
        clearAll.setOnClickListener {
            staged.clear()
            persistStagedSelection()
        }
        removeUnavailable.setOnClickListener {
            staged.retainAll(availableIds)
            persistStagedSelection()
        }
        sortButton.setOnClickListener {
            val focusedAlbum = cards.entries.firstOrNull { it.value.hasFocus() }?.key
            sortName = !sortName
            sortButton.text = if (sortName) "Sort: A–Z ▾" else "Sort: Newest ▾"
            reorderCards(focusedAlbum)
        }

        reorderCards()
        updateSelectionUi()
        setContentView(root)
        root.post {
            orderedAlbums().take(5).forEach { album -> coverViews[album.id]?.let { loadAlbumCover(album, it) } }
            cards.values.firstOrNull()?.requestFocus()
        }
    }

    private fun loadAlbumCover(album: AlbumEntry, cover: ImageView) {
        val usePoster = AlbumCoverRules.usesVideoPoster(mediaType, album.photoCount, album.videoCount)
        val posterCandidates = if (usePoster) AlbumCoverRules.posterCandidates(album.videos) else emptyList()
        val cacheKey = albumCoverCacheKey(album)
        albumCoverCache[cacheKey]?.let { cover.setImageBitmap(it); return }
        if (!usePoster && album.paths.isEmpty()) return
        if (!requestedCoverIds.add(cacheKey)) return
        val source = albumCoverSource ?: return
        val config = draftSmbConfig
        val pickerToken = catalogLoadToken
        albumExecutor.execute {
            val coverStartedAt = android.os.SystemClock.elapsedRealtime()
            val cacheFile = File(albumCoverCacheDirectory, "$cacheKey.jpg")
            var bitmap = if (cacheFile.isFile) BitmapFactory.decodeFile(cacheFile.absolutePath) else null
            val coverFromCache = bitmap != null
            if (bitmap == null) {
                cacheFile.delete()
                bitmap = if (usePoster) {
                    posterCandidates.firstNotNullOfOrNull { video ->
                        com.googletv.screensaver.playback.VideoPoster.load(config, video, File(cacheDir, "video-cache"), 480, 300)
                    }
                } else album.paths.take(4).firstNotNullOfOrNull { relativePath ->
                    runCatching {
                        source.loadImage(com.googletv.screensaver.playback.Slide(
                            "${config.rootPath}\\${relativePath.replace('/', '\\')}", album.title,
                        )).bitmap
                    }.getOrNull()
                }
                if (bitmap != null) writeAlbumCoverCache(cacheFile, bitmap!!)
            } else {
                cacheFile.setLastModified(System.currentTimeMillis())
            }
            val loadedBitmap = bitmap
            android.util.Log.i(
                "AlbumPicker",
                "${if (usePoster) "Poster cover" else "Cover"} ${if (loadedBitmap == null) "unavailable" else if (coverFromCache) "loaded from cache" else "loaded from share"} in " +
                    "${android.os.SystemClock.elapsedRealtime() - coverStartedAt} ms",
            )
            if (loadedBitmap != null) runOnUiThread {
                if (pickerOpen && pickerToken == catalogLoadToken && !isFinishing) {
                    albumCoverCache[cacheKey] = loadedBitmap
                    while (albumCoverCache.size > 32) {
                        albumCoverCache.remove(albumCoverCache.entries.iterator().next().key)
                    }
                    cover.setImageBitmap(loadedBitmap)
                } else loadedBitmap.recycle()
            }
        }
    }

    private fun albumCoverCacheKey(album: AlbumEntry): String =
        if (AlbumCoverRules.usesVideoPoster(mediaType, album.photoCount, album.videoCount)) {
            AlbumCoverRules.posterCacheKey(album.id, AlbumCoverRules.posterCandidates(album.videos))
        } else {
            AlbumCoverRules.photoCacheKey(album.id, album.paths)
        }

    private fun writeAlbumCoverCache(file: File, bitmap: Bitmap) {
        runCatching {
            val directory = file.parentFile ?: return
            directory.mkdirs()
            val temporary = File(directory, "${file.name}.tmp")
            FileOutputStream(temporary).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temporary.delete()
            }
            pruneAlbumCoverCache(directory)
        }
    }

    private fun pruneAlbumCoverCache(directory: File) {
        val files = directory.listFiles { file -> file.isFile && file.extension == "jpg" }
            ?.sortedBy { it.lastModified() }
            ?.toMutableList() ?: return
        var totalBytes = files.sumOf { it.length() }
        while (files.size > 96 || totalBytes > 32L * 1024L * 1024L) {
            val oldest = files.removeFirstOrNull() ?: break
            totalBytes -= oldest.length()
            oldest.delete()
        }
    }

    private fun finishAlbumPicker() {
        selectedAlbumIds = preferences.selectedAlbumIds()
        selectedAlbumPhotoCount = preferences.selectedAlbumPhotoCount()
        selectedAlbumVideoCount = preferences.selectedAlbumVideoCount()
        selectionMode = preferences.photoSelectionMode()
        closeAlbumPicker()
        albumError.visibility = View.GONE
        setContentView(settingsScreen)
        refreshSettingsControls()
        focusChoice("photos", if (selectionMode == PhotoSelectionMode.ALBUMS) 1 else 0)
    }

    private fun closeAlbumPicker() {
        pickerOpen = false
        catalogLoadToken++
        albumCoverSource?.close()
        albumCoverSource = null
        requestedCoverIds.clear()
    }

    private fun enterPreview() {
        try {
            PhotoSourceFactory.create(this, preferences)
        } catch (error: IllegalArgumentException) {
            smbMessage.text = error.message ?: getString(R.string.smb_config_error)
            smbMessage.visibility = View.VISIBLE
            return
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
            videoStage = if (preferences.mediaType().includesVideos) VideoSurfaceStage(view) else null,
            pairing = if (preferences.layoutMode() == LayoutMode.PORTRAIT_PAIRS) {
                PairingConfig(view::showPair, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            } else null,
        ).also { it.start() }
    }

    private fun leavePreview() {
        player?.stop()
        player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(settingsScreen)
        refreshSettingsControls()
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
        setOnFocusChangeListener { focusedView, focused ->
            focusedView.foreground = if (focused) focusOutline() else null
        }
        setOnClickListener { onClick() }
    }

    private fun focusOutline(): android.graphics.drawable.Drawable {
        val outer = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(8).toFloat()
            setStroke(dp(5), Color.rgb(93, 174, 237))
        }
        val inner = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(6).toFloat()
            setStroke(dp(2), Color.WHITE)
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(outer, inner)).apply {
            setLayerInset(1, dp(3), dp(3), dp(3), dp(3))
        }
    }

    private fun text(value: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun roundedBackground(fill: Int, stroke: Int, radius: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun formatBytes(bytes: Long): String {
        val gib = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gib >= 1.0) String.format(java.util.Locale.US, "%.1f GiB", gib)
        else String.format(java.util.Locale.US, "%.0f MiB", bytes / (1024.0 * 1024.0))
    }

    companion object {
        private const val LAYOUT_NOTE = "Two portrait photos side by side. Landscape photos stay full screen."
        private val SELECTED_COLOR = Color.rgb(48, 104, 155)
        private val BUTTON_COLOR = Color.rgb(42, 52, 64)
    }
}
