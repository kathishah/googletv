package com.googletv.screensaver

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
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

    fun show(slide: Slide) {
        image.setImageResource(slide.imageResource)
        image.contentDescription = slide.description
        caption.text = slide.description
        caption.visibility = View.VISIBLE
    }

    fun showEmptyState() {
        image.setImageDrawable(null)
        caption.text = "No photos are available yet"
        caption.gravity = Gravity.CENTER
        caption.textSize = 24f
        caption.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
