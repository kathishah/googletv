package com.googletv.screensaver.playback

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import com.googletv.screensaver.PhotoLabelMode
import com.googletv.screensaver.R
import java.io.BufferedInputStream
import java.io.File
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
    private val placeNameLookup: ((Double, Double) -> String?)? = null,
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
        val metadata = readExifMetadata(
            open = { resources.openRawResource(resourceId) },
            includeLocation = labelMode == PhotoLabelMode.LOCATION_AND_DATE,
            includeDate = labelMode != PhotoLabelMode.NONE,
            placeNameLookup = placeNameLookup,
        )
        val bitmap = decodeSampledBitmap(
            open = { resources.openRawResource(resourceId) },
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            exifOrientation = metadata.orientation,
        )
        return LoadedPhoto(bitmap, metadata.label)
    }
}

internal data class PhotoMetadata(val label: String?, val orientation: Int)

internal fun readExifMetadata(
    open: () -> InputStream,
    includeLocation: Boolean,
    includeDate: Boolean,
    placeNameLookup: ((Double, Double) -> String?)? = null,
): PhotoMetadata {
    return runCatching {
        val exif = open().use { stream ->
            BufferedInputStream(stream).use {
                ExifInterface(it, ExifInterface.STREAM_TYPE_FULL_IMAGE_DATA)
            }
        }
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val location = if (includeLocation) {
            runCatching {
                val coordinates = FloatArray(2)
                if (exif.getLatLong(coordinates)) {
                    placeNameLookup?.invoke(coordinates[0].toDouble(), coordinates[1].toDouble())
                        ?: String.format(Locale.US, "%.4f°, %.4f°", coordinates[0], coordinates[1])
                } else {
                    null
                }
            }.getOrNull()
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
        PhotoMetadata(listOfNotNull(location, date).joinToString(" · ").ifEmpty { null }, orientation)
    }.getOrElse {
        PhotoMetadata(null, ExifInterface.ORIENTATION_NORMAL)
    }
}

internal fun decodeSampledBitmap(
    open: () -> InputStream,
    targetWidth: Int,
    targetHeight: Int,
    fallbackCacheDirectory: File? = null,
    exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL,
    useNativeHeif: Boolean = false,
): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return decodeAfterPlatformFailure(
            open, targetWidth, targetHeight, fallbackCacheDirectory, useNativeHeif,
        )
    }

    if (useNativeHeif && bounds.outWidth.toLong() * bounds.outHeight >= LARGE_HEIF_PIXEL_THRESHOLD) {
        return decodeWithNativeHeif(open, targetWidth, targetHeight, fallbackCacheDirectory)
    }

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > targetWidth ||
        bounds.outHeight / sampleSize > targetHeight
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = open().use { input -> BitmapFactory.decodeStream(input, null, options) }
        ?: return decodeAfterPlatformFailure(
            open, targetWidth, targetHeight, fallbackCacheDirectory, useNativeHeif,
        )
    // BitmapFactory leaves EXIF orientation unapplied. ImageDecoder's platform path already
    // applies it, so only transform the BitmapFactory result here.
    return applyExifOrientation(decoded, exifOrientation)
}

private fun decodeAfterPlatformFailure(
    open: () -> InputStream,
    targetWidth: Int,
    targetHeight: Int,
    cacheDirectory: File?,
    useNativeHeif: Boolean,
): Bitmap {
    var platformError: Exception? = null
    try {
        return decodeWithImageDecoder(open, targetWidth, targetHeight, cacheDirectory)
    } catch (error: Exception) {
        platformError = error
    }

    if (!useNativeHeif) throw platformError
        ?: IllegalStateException("Platform photo decoding failed.")

    return try {
        decodeWithNativeHeif(open, targetWidth, targetHeight, cacheDirectory)
    } catch (nativeError: Exception) {
        platformError?.let(nativeError::addSuppressed)
        throw nativeError
    }
}

private fun decodeWithNativeHeif(
    open: () -> InputStream,
    targetWidth: Int,
    targetHeight: Int,
    cacheDirectory: File?,
): Bitmap {
    requireNotNull(cacheDirectory) { "Native HEIF decoding requires a private cache directory." }
    val tempFile = File.createTempFile("heif-decode-", ".heic", cacheDirectory)
    try {
        open().use { input -> tempFile.outputStream().use(input::copyTo) }
        return NativeHeifDecoder.decode(tempFile, targetWidth, targetHeight)
    } finally {
        tempFile.delete()
    }
}

private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val width = bitmap.width.toFloat()
    val height = bitmap.height.toFloat()
    val transform = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(
            -1f, 0f, width,
            0f, 1f, 0f,
            0f, 0f, 1f,
        )
        ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(
            -1f, 0f, width,
            0f, -1f, height,
            0f, 0f, 1f,
        )
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(
            1f, 0f, 0f,
            0f, -1f, height,
            0f, 0f, 1f,
        )
        ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(
            0f, 1f, 0f,
            1f, 0f, 0f,
            0f, 0f, 1f,
        )
        ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(
            0f, -1f, height,
            1f, 0f, 0f,
            0f, 0f, 1f,
        )
        ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(
            0f, -1f, height,
            -1f, 0f, width,
            0f, 0f, 1f,
        )
        ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(
            0f, 1f, 0f,
            -1f, 0f, width,
            0f, 0f, 1f,
        )
        else -> return bitmap
    }
    val matrix = Matrix().apply { setValues(transform) }
    val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (oriented !== bitmap) bitmap.recycle()
    return oriented
}

private fun decodeWithImageDecoder(
    open: () -> InputStream,
    targetWidth: Int,
    targetHeight: Int,
    cacheDirectory: File?,
): Bitmap {
    requireNotNull(cacheDirectory) { "Unsupported or unreadable image" }
    val tempFile = File.createTempFile("smb-image-", ".tmp", cacheDirectory)
    try {
        open().use { input -> tempFile.outputStream().use(input::copyTo) }
        val source = ImageDecoder.createSource(tempFile)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            var sampleSize = 1
            while (info.size.width / sampleSize > targetWidth ||
                info.size.height / sampleSize > targetHeight
            ) {
                sampleSize *= 2
            }
            decoder.setTargetSampleSize(sampleSize)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } finally {
        tempFile.delete()
    }
}

private const val LARGE_HEIF_PIXEL_THRESHOLD = 24_000_000L
