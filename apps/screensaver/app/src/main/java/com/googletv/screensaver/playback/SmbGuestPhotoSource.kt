package com.googletv.screensaver.playback

import android.graphics.Bitmap
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
import java.io.FilterInputStream
import java.io.InputStream
import java.util.ArrayDeque
import java.util.EnumSet
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
    private val maxSlides: Int = MAX_SLIDES_PER_SCAN,
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
        require(maxSlides > 0)
    }

    override fun loadSlides(): List<Slide> = withDiskShare { diskShare ->
        val directories = ArrayDeque<String>()
        val slides = mutableListOf<Slide>()
        directories.add(rootPath)

        while (directories.isNotEmpty() && slides.size < maxSlides) {
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
                    slides += Slide(path, "Photo ${slides.size + 1}")
                    if (slides.size >= maxSlides) break
                }
            }
        }
        slides
    }

    override fun loadImage(slide: Slide): Bitmap = withDiskShare { diskShare ->
        decodeSampledBitmap(
            open = { openReadOnlyFile(diskShare, slide.id) },
            targetWidth = targetWidth,
            targetHeight = targetHeight,
        )
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
        const val MAX_SLIDES_PER_SCAN = 250
        private const val SMB_PORT = 445
        private const val SMB_TIMEOUT_SECONDS = 15L
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    }
}
