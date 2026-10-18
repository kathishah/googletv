package com.googletv.screensaver

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Bitmap
import android.view.Gravity
import android.view.SurfaceView
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

    /**
     * Video is drawn here. It sits under [image], which hides it until [revealVideo] makes the photo
     * layer invisible, so a photo never flashes black while a clip is being prepared.
     */
    val videoSurface = SurfaceView(context)

    init {
        setBackgroundColor(Color.BLACK)
        addView(videoSurface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            caption,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
    }

    /** Hides the photo layer so the video surface shows through. */
    fun revealVideo() {
        image.visibility = View.INVISIBLE
    }

    /** Sizes the video surface to the clip's displayed size: fitted with black bars (never cropped), enlarged at most [VideoFitRules.MAX_UPSCALE] times. */
    fun fitVideo(videoWidth: Int, videoHeight: Int) {
        val fit = VideoFitRules.fit(videoWidth, videoHeight, width, height) ?: return
        videoSurface.layoutParams = LayoutParams(fit.width, fit.height, Gravity.CENTER)
    }

    /** The label over a playing video, styled like a photo's. */
    fun showVideoCaption(description: String?) {
        applyCaption(description.orEmpty())
    }

    fun show(slide: Slide, bitmap: Bitmap) {
        image.visibility = View.VISIBLE
        image.setImageBitmap(bitmap)
        image.contentDescription = slide.description.ifBlank { "Slideshow photo" }
        applyCaption(slide.description)
    }

    private fun applyCaption(description: String) {
        if (description.isBlank()) {
            caption.visibility = View.GONE
            return
        }
        caption.text = description
        caption.setTextColor(Color.argb(218, 255, 255, 255))
        caption.setShadowLayer(3f, 1f, 1f, Color.argb(112, 0, 0, 0))
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
        image.visibility = View.VISIBLE
        image.setImageDrawable(null)
        showStatus(context.getString(R.string.empty_photo_state), 24f)
    }

    fun showLoadingState() {
        image.visibility = View.VISIBLE
        image.setImageDrawable(null)
        showStatus(context.getString(R.string.loading_photos), 22f)
    }

    fun showErrorState(message: String) {
        image.visibility = View.VISIBLE
        image.setImageDrawable(null)
        showStatus(message, 20f)
    }

    private fun showStatus(message: String, size: Float) {
        caption.text = message
        caption.setTextColor(Color.WHITE)
        caption.setShadowLayer(4f, 1f, 1f, Color.BLACK)
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
