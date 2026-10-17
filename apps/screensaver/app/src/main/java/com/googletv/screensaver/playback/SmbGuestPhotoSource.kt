package com.googletv.screensaver.playback

import android.os.SystemClock
import android.util.Log
import com.googletv.screensaver.PhotoLabelMode
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.ArrayDeque
import java.util.EnumSet
import kotlin.random.Random
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    private val connectionLock = Any()
    private var connection: SmbConnection? = null
    private val epoch = AtomicInteger()

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

    override fun loadSlides(): List<Slide> {
        val startedAt = SystemClock.elapsedRealtime()
        var skippedDirectories = 0
        val slides = withDiskShare { diskShare ->
            skippedDirectories = 0
            val directories = ArrayDeque<String>()
            val pool = mutableListOf<Slide>()
            directories.add(rootPath)

            var supportedPhotoCount = 0
            while (directories.isNotEmpty()) {
                val directory = directories.removeFirst()
                val entries = try {
                    diskShare.list(directory)
                } catch (error: Exception) {
                    // A folder Guest cannot open must not hide the rest of the library, but a
                    // dropped connection (or an unreadable root) must fail the whole listing.
                    if (directory == rootPath || error.smbFailureKind() != SmbFailureKind.PER_FILE) {
                        throw error
                    }
                    skippedDirectories += 1
                    continue
                }
                for (entry in entries) {
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
                        if (pool.size < poolSize) {
                            pool += candidate
                        } else {
                            val replacementIndex = Random.nextInt(supportedPhotoCount)
                            if (replacementIndex < poolSize) pool[replacementIndex] = candidate
                        }
                    }
                }
            }
            pool.shuffle()
            pool
        }
        Log.i(
            LOG_TAG,
            "Photo listing found ${slides.size} pooled photos in " +
                "${SystemClock.elapsedRealtime() - startedAt} ms, $skippedDirectories folders skipped",
        )
        return slides
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

    /**
     * Runs [block] on a shared connection that stays open between photos, so a slideshow does not
     * negotiate, authenticate, and connect a share for every image. A failed call discards the
     * connection so the next one starts clean; a transient failure on a reused (possibly stale)
     * connection is retried once at once on a fresh one.
     */
    private fun <T> withDiskShare(block: (DiskShare) -> T): T {
        val startedEpoch = epoch.get()
        var smb = synchronized(connectionLock) { connection }
        val reused = smb != null
        if (smb == null) {
            smb = openConnection()
            val keep = synchronized(connectionLock) {
                (epoch.get() == startedEpoch).also { if (it) connection = smb }
            }
            if (!keep) {
                closeAsync(smb)
                throw CancellationException("SMB source closed")
            }
        }
        try {
            return block(smb.share)
        } catch (error: Throwable) {
            discard(smb)
            if (epoch.get() != startedEpoch) {
                // close() interrupted this request on purpose (the dream stopped); not a failure.
                Log.d(LOG_TAG, "SMB request cancelled by close")
                throw error
            }
            if (reused && error.smbFailureKind() == SmbFailureKind.TRANSIENT) {
                Log.i(LOG_TAG, "Reused SMB connection failed (${error.safeCauseChain()}); reconnecting")
                return withDiskShare(block)
            }
            Log.w(
                LOG_TAG,
                "SMB request failed on a ${if (reused) "reused" else "new"} connection " +
                    "(${error.safeCauseChain()}, ${error.safeSmbStatus()})",
            )
            throw error
        }
    }

    private fun openConnection(): SmbConnection {
        val config = SmbConfig.builder()
            .withSigningRequired(false)
            .withMultiProtocolNegotiate(true)
            .withTimeout(SMB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withSoTimeout(SMB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        var phase = "connect"
        val startedAt = SystemClock.elapsedRealtime()
        val client = SMBClient(config)
        var connection: Connection? = null
        var session: Session? = null
        try {
            connection = client.connect(host, SMB_PORT)
            phase = "authenticate"
            session = connection.authenticate(AuthenticationContext.guest())
            phase = "connectShare"
            val share = session.connectShare(shareName)
            if (share !is DiskShare) {
                share.close()
                throw SmbConfigurationException("The SMB endpoint is not a disk share.")
            }
            return SmbConnection(client, connection, session, share)
        } catch (error: Throwable) {
            Log.w(
                LOG_TAG,
                "SMB $phase failed after ${SystemClock.elapsedRealtime() - startedAt} ms " +
                    "(${error.safeCauseChain()}, ${error.safeSmbStatus()})",
            )
            runCatching { session?.close() }
            runCatching { connection?.close() }
            runCatching { client.close() }
            throw error
        }
    }

    private fun discard(smb: SmbConnection) {
        synchronized(connectionLock) { if (connection === smb) connection = null }
        closeAsync(smb)
    }

    /** Closes the shared connection and unblocks any request in flight. Safe from any thread. */
    override fun close() {
        epoch.incrementAndGet()
        val smb = synchronized(connectionLock) { connection.also { connection = null } }
        if (smb != null) closeAsync(smb)
    }

    // Closing sends a logoff and can block on a dead socket, so never do it on the caller's thread.
    private fun closeAsync(smb: SmbConnection) {
        Thread({ smb.closeQuietly() }, "smb-close").apply { isDaemon = true }.start()
    }

    private class SmbConnection(
        val client: SMBClient,
        val connection: Connection,
        val session: Session,
        val share: DiskShare,
    ) {
        fun closeQuietly() {
            runCatching { share.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
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
        private const val LOG_TAG = "PhotoSource"
        private const val SMB_PORT = 445
        private const val SMB_TIMEOUT_SECONDS = 15L
        private const val MAX_METADATA_BYTES = 512 * 1024
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    }
}
