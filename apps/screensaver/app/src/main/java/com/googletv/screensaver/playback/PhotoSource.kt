package com.googletv.screensaver.playback

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.googletv.screensaver.PhotoLabelMode
import com.googletv.screensaver.R
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    fun loadImage(slide: Slide): LoadedPhoto
}

data class LoadedPhoto(val bitmap: Bitmap, val label: String?)

class BundledSamplePhotoSource(
    private val resources: Resources,
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val labelMode: PhotoLabelMode = PhotoLabelMode.default,
) : PhotoSource {
    private val resourceIds = mapOf(
        "sample_mountain_lake" to R.drawable.sample_mountain_lake,
        "sample_redwood_forest" to R.drawable.sample_redwood_forest,
    )

    override fun loadSlides() = listOf(
        Slide("sample_mountain_lake", "Mountain lake at sunrise"),
        Slide("sample_redwood_forest", "Redwood forest trail"),
    )

    override fun loadImage(slide: Slide): LoadedPhoto {
        val resourceId = resourceIds[slide.id] ?: error("Unknown bundled slide")
        val bitmap = decodeSampledBitmap(
            open = { resources.openRawResource(resourceId) },
            targetWidth = targetWidth,
            targetHeight = targetHeight,
        )
        val label = readExifLabel(
            open = { resources.openRawResource(resourceId) },
            includeLocation = labelMode == PhotoLabelMode.LOCATION_AND_DATE,
            includeDate = labelMode != PhotoLabelMode.NONE,
        )
        return LoadedPhoto(bitmap, label)
    }
}

internal fun readExifLabel(open: () -> InputStream, includeLocation: Boolean, includeDate: Boolean): String? {
    if (!includeLocation && !includeDate) return null
    return runCatching {
        val exif = open().use { stream ->
            BufferedInputStream(stream).use {
                ExifInterface(it, ExifInterface.STREAM_TYPE_FULL_IMAGE_DATA)
            }
        }
        val location = if (includeLocation) {
            val coordinates = FloatArray(2)
            if (exif.getLatLong(coordinates)) {
                String.format(Locale.US, "%.4f°, %.4f°", coordinates[0], coordinates[1])
            } else {
                null
            }
        } else {
            null
        }
        val date = if (includeDate) {
            val timestamp = exif.dateTimeOriginal.takeIf { it >= 0 } ?: exif.dateTime
            timestamp.takeIf { it >= 0 }?.let {
                SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(it))
            }
        } else {
            null
        }
        listOfNotNull(location, date).joinToString(" · ").ifEmpty { null }
    }.getOrNull()
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
