package com.googletv.screensaver

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Bitmap
import android.text.TextUtils
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.googletv.screensaver.playback.PairRect
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

    // Portrait pairs. All of this stays hidden (and the photo layer is MATCH_PARENT again) for a single
    // photo, so Layout = Single draws exactly what it drew before pairs existed.
    private val pairBackground = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
    }
    private val secondImage = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
    }
    private val firstPairCaption = pairCaption()
    private val secondPairCaption = pairCaption()
    private var pairMode = false
    private var pairScene: PairScene? = null

    init {
        setBackgroundColor(Color.BLACK)
        addView(videoSurface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(pairBackground, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(secondImage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            caption,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
        addView(firstPairCaption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        addView(secondPairCaption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
    }

    /** Hides the photo layer so the video surface shows through. */
    fun revealVideo() {
        exitPair()
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
        exitPair()
        image.visibility = View.VISIBLE
        image.setImageBitmap(bitmap)
        image.contentDescription = slide.description.ifBlank { "Slideshow photo" }
        applyCaption(slide.description)
    }

    /**
     * Two portrait photos side by side on [scene]'s background, each with its own label inside its own
     * photo. The scene (rectangles and background) was built off the main thread by [PairScene.build].
     * The previous pair's background is freed once this one is on screen.
     */
    fun showPair(first: Slide, firstBitmap: Bitmap, second: Slide, secondBitmap: Bitmap, scene: PairScene) {
        val previous = pairScene
        pairMode = true
        pairScene = scene
        caption.visibility = View.GONE
        pairBackground.setImageBitmap(scene.background)
        pairBackground.visibility = View.VISIBLE
        place(image, scene.layout.first)
        place(secondImage, scene.layout.second)
        image.setBackgroundColor(Color.TRANSPARENT)
        image.visibility = View.VISIBLE
        image.setImageBitmap(firstBitmap)
        image.contentDescription = first.description.ifBlank { "Slideshow photo, left" }
        secondImage.visibility = View.VISIBLE
        secondImage.setImageBitmap(secondBitmap)
        secondImage.contentDescription = second.description.ifBlank { "Slideshow photo, right" }
        applyPairCaption(firstPairCaption, first.description, scene.layout.first)
        applyPairCaption(secondPairCaption, second.description, scene.layout.second)
        previous?.recycle()
    }

    private fun exitPair() {
        if (!pairMode) return
        pairMode = false
        val previous = pairScene
        pairScene = null
        pairBackground.visibility = View.GONE
        pairBackground.setImageDrawable(null)
        secondImage.visibility = View.GONE
        secondImage.setImageDrawable(null)
        firstPairCaption.visibility = View.GONE
        secondPairCaption.visibility = View.GONE
        image.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        image.setBackgroundColor(Color.BLACK)
        previous?.recycle()
    }

    private fun place(view: View, rect: PairRect) {
        view.layoutParams = LayoutParams(rect.width, rect.height, Gravity.TOP or Gravity.START).apply {
            leftMargin = rect.left
            topMargin = rect.top
        }
    }

    private fun pairCaption() = TextView(context).apply {
        textSize = 18f
        typeface = Typeface.DEFAULT
        setPadding(dp(24), dp(18), dp(24), dp(28))
        gravity = Gravity.BOTTOM or Gravity.START
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
    }

    /** A label in the bottom-left corner of its own photo, styled like a single photo's. */
    private fun applyPairCaption(view: TextView, description: String, rect: PairRect) {
        if (description.isBlank()) {
            view.visibility = View.GONE
            return
        }
        view.text = description
        view.setTextColor(Color.argb(218, 255, 255, 255))
        view.setShadowLayer(3f, 1f, 1f, Color.argb(112, 0, 0, 0))
        val bottomMargin = if (height > 0) (height - rect.bottom).coerceAtLeast(0) else 0
        view.layoutParams = LayoutParams(rect.width, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = rect.left
            this.bottomMargin = bottomMargin
        }
        view.visibility = View.VISIBLE
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
        exitPair()
        image.visibility = View.VISIBLE
        image.setImageDrawable(null)
        showStatus(context.getString(R.string.empty_photo_state), 24f)
    }

    fun showLoadingState() {
        exitPair()
        image.visibility = View.VISIBLE
        image.setImageDrawable(null)
        showStatus(context.getString(R.string.loading_photos), 22f)
    }

    fun showErrorState(message: String) {
        exitPair()
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
