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

enum class SlideKind { PHOTO, VIDEO }

/**
 * A video slide's [id] is its SMB path; [video] carries the duration and size the player plans with.
 * A photo slide with a [partner] is a pair: the slide's own photo is on the left, the [partner]'s on the
 * right, and [look] (null = plain black) is the background behind both. Only portrait photos are paired.
 */
data class Slide(
    val id: String,
    val description: String,
    val kind: SlideKind = SlideKind.PHOTO,
    val video: VideoItem? = null,
    val partner: Slide? = null,
    val look: PairLook? = null,
)

/**
 * Blocking photo-source API. SlideshowPlayer calls every method on its worker thread.
 * Implementations list metadata and decode one image on demand; they do not schedule slides or
 * render views.
 */
interface PhotoSource {
    fun loadSlides(): List<Slide>

    fun loadImage(slide: Slide): LoadedPhoto

    /**
     * Whether [slide] is a photo that may share a slide with another (the catalog lists it as portrait).
     * A photo the source knows nothing about is not pairable, so the safe failure is today's single photo.
     */
    fun isPairable(slide: Slide): Boolean = false

    /** Opens a video slide and waits until it is safe to play. Only sources that list video slides implement this. */
    fun prepareVideo(slide: Slide): PreparedVideo = throw UnsupportedOperationException("This source has no videos.")

    /** Releases any shared connection. Callable from any thread; the source can be used again. */
    fun close() {}
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
        "art_creation_of_adam" to R.drawable.art_creation_of_adam,
        "art_last_supper" to R.drawable.art_last_supper,
        "art_lamentation_of_christ" to R.drawable.art_lamentation_of_christ,
    )

    // Public-domain artworks; sources are recorded in docs/PHOTO_SOURCES.md.
    override fun loadSlides() = listOf(
        Slide("art_creation_of_adam", "The Creation of Adam · Michelangelo"),
        Slide("art_last_supper", "The Last Supper · Leonardo da Vinci"),
        Slide("art_lamentation_of_christ", "Lamentation of Christ · Mantegna"),
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
        // The artworks carry no EXIF, so their title is the caption unless labels are off.
        val label = metadata.label ?: slide.description.takeIf { labelMode != PhotoLabelMode.NONE }
        return LoadedPhoto(bitmap, label)
    }
}

internal data class PhotoMetadata(val label: String?, val orientation: Int)

/** Converts expected metadata failures into a fallback while preserving executor cancellation. */
private inline fun <T> captureExpectedFailure(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: Exception) {
    if (error is InterruptedException) {
        Thread.currentThread().interrupt()
        throw error
    }
    Result.failure(error)
}

internal fun readExifMetadata(
    open: () -> InputStream,
    includeLocation: Boolean,
    includeDate: Boolean,
    placeNameLookup: ((Double, Double) -> String?)? = null,
): PhotoMetadata {
    return captureExpectedFailure {
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
            captureExpectedFailure {
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
    val imageTempDirectory = fallbackCacheDirectory?.let { ImageDecodeTemps.prepare(it) }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return decodeAfterPlatformFailure(
            open, targetWidth, targetHeight, imageTempDirectory, useNativeHeif,
        )
    }

    if (useNativeHeif && bounds.outWidth.toLong() * bounds.outHeight >= LARGE_HEIF_PIXEL_THRESHOLD) {
        return decodeWithNativeHeif(open, targetWidth, targetHeight, imageTempDirectory)
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
            open, targetWidth, targetHeight, imageTempDirectory, useNativeHeif,
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
    val tempFile = ImageDecodeTemps.create(cacheDirectory, "heif-decode-", ".heic")
    try {
        open().use { input -> tempFile.outputStream().use(input::copyTo) }
        return NativeHeifDecoder.decode(tempFile, targetWidth, targetHeight)
    } finally {
        ImageDecodeTemps.release(tempFile)
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
    val tempFile = ImageDecodeTemps.create(cacheDirectory, "smb-image-", ".tmp")
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
        ImageDecodeTemps.release(tempFile)
    }
}

private const val LARGE_HEIF_PIXEL_THRESHOLD = 24_000_000L

/** Keeps full-resolution decoder inputs separate from ordinary app cache and bounds crash leftovers. */
private object ImageDecodeTemps {
    private const val DIRECTORY = "image-decode-temp"
    private const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
    private const val MAX_BYTES = 256L * 1024 * 1024
    private val lock = Any()
    private val active = mutableSetOf<String>()

    fun prepare(cacheDirectory: File): File = synchronized(lock) {
        // Remove leftovers from older builds, which placed these full originals directly in cache/.
        cacheDirectory.listFiles { file -> file.isFile &&
            (file.name.startsWith("smb-image-") || file.name.startsWith("heif-decode-"))
        }?.forEach(File::delete)
        val directory = File(cacheDirectory, DIRECTORY)
        directory.mkdirs()
        val files = directory.listFiles { file -> file.isFile &&
            (file.name.startsWith("smb-image-") || file.name.startsWith("heif-decode-"))
        }?.toList().orEmpty()
        val now = System.currentTimeMillis()
        files.filter { it.absolutePath !in active && now - it.lastModified() > MAX_AGE_MILLIS }.forEach(File::delete)
        val remaining = directory.listFiles { file -> file.isFile }?.sortedBy { it.lastModified() }.orEmpty()
        var total = remaining.sumOf { it.length() }
        for (file in remaining) {
            if (total <= MAX_BYTES) break
            if (file.absolutePath in active) continue
            total -= file.length()
            file.delete()
        }
        directory
    }

    fun create(directory: File, prefix: String, suffix: String): File = synchronized(lock) {
        File.createTempFile(prefix, suffix, directory).also { active += it.absolutePath }
    }

    fun release(file: File) = synchronized(lock) {
        active -= file.absolutePath
        file.delete()
        Unit
    }
}
