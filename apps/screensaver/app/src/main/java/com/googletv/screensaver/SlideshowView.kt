package com.googletv.screensaver

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.googletv.screensaver.playback.Slide

/** Shared renderer used by the manual preview and the system dream. */
class SlideshowView(context: Context) : FrameLayout(context) {
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Slideshow photo"
        setBackgroundColor(Color.BLACK)
    }

    private val caption = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 18f
        typeface = Typeface.DEFAULT
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
        setPadding(dp(28), dp(18), dp(28), dp(28))
        gravity = Gravity.BOTTOM or Gravity.START
        visibility = View.GONE
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            caption,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
    }

    fun show(slide: Slide, bitmap: Bitmap) {
        image.setImageBitmap(bitmap)
        image.contentDescription = slide.description.ifBlank { "Slideshow photo" }
        if (slide.description.isBlank()) {
            caption.visibility = View.GONE
            return
        }
        caption.text = slide.description
        caption.gravity = Gravity.BOTTOM or Gravity.START
        caption.textSize = 18f
        caption.layoutParams = (caption.layoutParams as LayoutParams).apply {
            width = LayoutParams.MATCH_PARENT
            height = LayoutParams.WRAP_CONTENT
            gravity = Gravity.BOTTOM
        }
        caption.visibility = View.VISIBLE
    }

    fun showEmptyState() {
        image.setImageDrawable(null)
        showStatus(context.getString(R.string.empty_photo_state), 24f)
    }

    fun showLoadingState() {
        image.setImageDrawable(null)
        showStatus(context.getString(R.string.loading_photos), 22f)
    }

    fun showErrorState(message: String) {
        image.setImageDrawable(null)
        showStatus(message, 20f)
    }

    private fun showStatus(message: String, size: Float) {
        caption.text = message
        caption.gravity = Gravity.CENTER
        caption.textSize = size
        caption.layoutParams = (caption.layoutParams as LayoutParams).apply {
            width = LayoutParams.MATCH_PARENT
            height = LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER
        }
        caption.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
