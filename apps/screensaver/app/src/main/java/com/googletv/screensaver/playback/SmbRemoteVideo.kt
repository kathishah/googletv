package com.googletv.screensaver.playback

import android.media.MediaDataSource
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One video on the share over its own read-only Guest connection. A connection per clip means
 * [abort] can drop it without touching the slideshow's shared connection, and a reader thread is
 * never interrupted (which would break the connection mid-read).
 */
internal class SmbRemoteVideo private constructor(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
    private val file: File,
    override val sizeBytes: Long,
) : RemoteVideoFile {
    private val closed = AtomicBoolean(false)

    override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        if (closed.get()) throw IOException("The video connection is closed.")
        return file.read(buffer, offset, bufferOffset, length)
    }

    override fun abort() {
        if (!closed.compareAndSet(false, true)) return
        // Closing sends a logoff and can block on a dead socket, so never do it on the caller's thread.
        Thread({
            runCatching { file.close() }
            runCatching { share.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
        }, "smb-video-close").apply { isDaemon = true }.start()
    }

    companion object {
        /** Opens [relativePath] (relative to the library's `originals` folder) from the configured share. */
        fun open(config: SmbSourceConfig, relativePath: String): SmbRemoteVideo {
            val root = config.rootPath.trim().replace('/', '\\').trim('\\')
            val path = "$root\\${relativePath.replace('/', '\\')}"
            val smbConfig = SmbConfig.builder()
                .withSigningRequired(false)
                .withMultiProtocolNegotiate(true)
                .withTimeout(SMB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .withSoTimeout(SMB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            val client = SMBClient(smbConfig)
            var connection: Connection? = null
            var session: Session? = null
            var share: DiskShare? = null
            try {
                connection = client.connect(config.host.trim(), SMB_PORT)
                session = connection.authenticate(AuthenticationContext.guest())
                share = session.connectShare(config.share.trim()) as? DiskShare ?: throw IOException("The SMB endpoint is not a disk share.")
                val file = share.openFile(
                    path,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
                )
                return SmbRemoteVideo(client, connection, session, share, file, file.fileInformation.standardInformation.endOfFile)
            } catch (error: Throwable) {
                runCatching { share?.close() }
                runCatching { session?.close() }
                runCatching { connection?.close() }
                runCatching { client.close() }
                throw error
            }
        }

        private const val SMB_PORT = 445
        private const val SMB_TIMEOUT_SECONDS = 15L
    }
}

/**
 * Gives a [ProgressiveReader] to `MediaPlayer` or `MediaMetadataRetriever`. Closing it does nothing:
 * the framework may close a data source while others are still reading, and the reader's owner (the
 * video stage) is the one that closes it, which also removes the temp file.
 */
internal class ProgressiveMediaDataSource(private val reader: ProgressiveReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = reader.readAt(position, buffer, offset, size)

    override fun getSize(): Long = reader.sizeBytes

    override fun close() = Unit
}
