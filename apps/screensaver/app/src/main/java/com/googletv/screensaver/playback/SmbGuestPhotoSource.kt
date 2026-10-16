package com.googletv.screensaver.playback

import android.graphics.Bitmap
import com.googletv.screensaver.PhotoLabelMode
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.ArrayDeque
import java.util.EnumSet
import kotlin.random.Random
import java.util.concurrent.TimeUnit

data class SmbSourceConfig(
    val host: String,
    val share: String,
    val rootPath: String,
)

/** Read-only SMB Guest source. No write-capable file handles are opened. */
class SmbGuestPhotoSource(
    config: SmbSourceConfig,
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val poolSize: Int = DEFAULT_POOL_SIZE,
    private val labelMode: PhotoLabelMode = PhotoLabelMode.default,
    private val placeNameLookup: ((Double, Double) -> String?)? = null,
    private val fallbackCacheDirectory: java.io.File,
) : PhotoSource {
    private val host = config.host.trim()
    private val shareName = config.share.trim()
    private val rootPath = normalizeRelativePath(config.rootPath)

    init {
        require(host.isNotEmpty() && '/' !in host && '\\' !in host && '@' !in host) {
            "Enter a valid SMB server address."
        }
        require(shareName.isNotEmpty() && '/' !in shareName && '\\' !in shareName) {
            "Enter a valid SMB share name."
        }
        require(rootPath.isNotEmpty()) { "Enter a folder path inside the SMB share." }
        require(poolSize > 0)
    }

    override fun loadSlides(): List<Slide> = withDiskShare { diskShare ->
        val directories = ArrayDeque<String>()
        val slides = mutableListOf<Slide>()
        directories.add(rootPath)

        var supportedPhotoCount = 0
        while (directories.isNotEmpty()) {
            val directory = directories.removeFirst()
            for (entry in diskShare.list(directory)) {
                val name = entry.fileName
                if (name == "." || name == ".." || name.startsWith('.')) continue
                val path = "$directory\\$name"
                val isDirectory = entry.fileAttributes and
                    FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                if (isDirectory) {
                    directories.addLast(path)
                } else if (isSupportedPhoto(name)) {
                    supportedPhotoCount += 1
                    val candidate = Slide(path, "Photo $supportedPhotoCount")
                    if (slides.size < poolSize) {
                        slides += candidate
                    } else {
                        val replacementIndex = Random.nextInt(supportedPhotoCount)
                        if (replacementIndex < poolSize) slides[replacementIndex] = candidate
                    }
                }
            }
        }
        slides.shuffle()
        slides
    }

    override fun loadImage(slide: Slide): LoadedPhoto = withDiskShare { diskShare ->
        val open = { openReadOnlyFile(diskShare, slide.id) }
        val metadata = readExifMetadata(
            open = { openMetadataPrefix(open) },
            includeLocation = labelMode == PhotoLabelMode.LOCATION_AND_DATE,
            includeDate = labelMode != PhotoLabelMode.NONE,
            placeNameLookup = placeNameLookup,
        )
        val bitmap = decodeSampledBitmap(
            open = open,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            fallbackCacheDirectory = fallbackCacheDirectory,
            exifOrientation = metadata.orientation,
            useNativeHeif = slide.id.endsWith(".heic", ignoreCase = true) ||
                slide.id.endsWith(".heif", ignoreCase = true),
        )
        LoadedPhoto(bitmap, metadata.label)
    }

    private fun openMetadataPrefix(open: () -> InputStream): InputStream {
        // Android's platform ExifInterface does not reliably read metadata directly from SMBJ's
        // network stream. A bounded local stream preserves EXIF parsing without retaining photos.
        val bytes = ByteArray(MAX_METADATA_BYTES)
        var count = 0
        open().use { input ->
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read <= 0) break
                count += read
            }
        }
        return ByteArrayInputStream(bytes, 0, count)
    }

    private fun openReadOnlyFile(diskShare: DiskShare, path: String): InputStream {
        val file: File = diskShare.openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        return object : FilterInputStream(file.inputStream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    file.close()
                }
            }
        }
    }

    private fun <T> withDiskShare(block: (DiskShare) -> T): T {
        val config = SmbConfig.builder()
            .withSigningRequired(false)
            .withMultiProtocolNegotiate(true)
            .withTimeout(SMB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withSoTimeout(SMB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        return SMBClient(config).use { client ->
            client.connect(host, SMB_PORT).use { connection ->
                connection.authenticate(AuthenticationContext.guest()).use { session ->
                    val connectedShare = session.connectShare(shareName)
                    require(connectedShare is DiskShare) { "The SMB endpoint is not a disk share." }
                    connectedShare.use(block)
                }
            }
        }
    }

    private fun normalizeRelativePath(path: String): String {
        val segments = path.trim().replace('/', '\\').trim('\\').split('\\')
        require(segments.isNotEmpty() && segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Enter a folder path inside the SMB share."
        }
        return segments.joinToString("\\")
    }

    private fun isSupportedPhoto(filename: String): Boolean {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return extension in SUPPORTED_EXTENSIONS
    }

    companion object {
        const val DEFAULT_POOL_SIZE = 250
        private const val SMB_PORT = 445
        private const val SMB_TIMEOUT_SECONDS = 15L
        private const val MAX_METADATA_BYTES = 512 * 1024
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    }
}
