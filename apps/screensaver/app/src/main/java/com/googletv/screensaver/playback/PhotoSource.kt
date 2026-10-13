package com.googletv.screensaver.playback

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.googletv.screensaver.R
import java.io.InputStream

data class Slide(
    val id: String,
    val description: String,
)

/**
 * Blocking photo-source API. SlideshowPlayer calls every method on its worker thread.
 * Implementations list metadata and decode one image on demand; they do not schedule slides or
 * render views.
 */
interface PhotoSource {
    fun loadSlides(): List<Slide>

    fun loadImage(slide: Slide): Bitmap
}

class BundledSamplePhotoSource(
    private val resources: Resources,
    private val targetWidth: Int,
    private val targetHeight: Int,
) : PhotoSource {
    private val resourceIds = mapOf(
        "sample_mountain_lake" to R.drawable.sample_mountain_lake,
        "sample_redwood_forest" to R.drawable.sample_redwood_forest,
    )

    override fun loadSlides() = listOf(
        Slide("sample_mountain_lake", "Mountain lake at sunrise"),
        Slide("sample_redwood_forest", "Redwood forest trail"),
    )

    override fun loadImage(slide: Slide): Bitmap {
        val resourceId = resourceIds[slide.id] ?: error("Unknown bundled slide")
        return decodeSampledBitmap(
            open = { resources.openRawResource(resourceId) },
            targetWidth = targetWidth,
            targetHeight = targetHeight,
        )
    }
}

internal fun decodeSampledBitmap(
    open: () -> InputStream,
    targetWidth: Int,
    targetHeight: Int,
): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported or unreadable image" }

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > targetWidth ||
        bounds.outHeight / sampleSize > targetHeight
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return open().use { input ->
        BitmapFactory.decodeStream(input, null, options)
            ?: error("Unsupported or unreadable image")
    }
}
