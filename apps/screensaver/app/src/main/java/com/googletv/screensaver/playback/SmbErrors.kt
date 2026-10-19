package com.googletv.screensaver.playback

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/** What a failed SMB operation says about the photo, the connection, and the configuration. */
internal enum class SmbFailureKind {
    /** The network, connection, or server was briefly unavailable. The photo itself is fine. */
    TRANSIENT,

    /** This one photo or folder cannot be read or decoded. Skip it. */
    PER_FILE,

    /** Retrying cannot help until the server, share, or Guest settings change. */
    CONFIGURATION,
}

/** The SMB endpoint is reachable but is not something this source can read. */
internal class SmbConfigurationException(message: String) : RuntimeException(message)

/**
 * The share answered, but none of a sample of listed photos could be opened (access denied, missing
 * file, sharing violation). Seen while the clone was being refreshed. It is treated as temporary, so
 * the slideshow retries and shows the stored artworks instead of stopping with an error.
 */
internal class ShareUnreadableException(sampleSize: Int) :
    RuntimeException("None of $sampleSize sampled photos could be opened")

/** Per-file refusals say nothing about the reusable SMB session. */
internal fun SmbFailureKind.shouldDiscardConnection(): Boolean = this != SmbFailureKind.PER_FILE

/** A stale reused session gets one immediate reconnect; fresh-session failures follow normal retry. */
internal fun SmbFailureKind.shouldReconnectReusedConnection(reused: Boolean): Boolean =
    reused && this == SmbFailureKind.TRANSIENT

/** Only these statuses establish that a database sidecar is genuinely absent. */
internal fun com.hierynomus.mserref.NtStatus.isMissingRemoteFile(): Boolean = this in MISSING_FILE_STATUSES

internal fun Throwable.isMissingRemoteFile(): Boolean =
    causes().filterIsInstance<SMBApiException>().any { it.status.isMissingRemoteFile() }

internal fun Throwable.smbFailureKind(): SmbFailureKind {
    val chain = causes()
    if (chain.any { it is SMB2GuestSigningRequiredException || it is SmbConfigurationException }) {
        return SmbFailureKind.CONFIGURATION
    }
    if (chain.any { it is ShareUnreadableException }) return SmbFailureKind.TRANSIENT
    chain.filterIsInstance<SMBApiException>().firstOrNull()?.let { api ->
        return when (api.status) {
            in CONFIGURATION_STATUSES -> SmbFailureKind.CONFIGURATION
            in TRANSIENT_STATUSES -> SmbFailureKind.TRANSIENT
            else -> SmbFailureKind.PER_FILE
        }
    }
    // SMBJ reports dropped sockets and timeouts as TransportException, a wrapped TimeoutException,
    // or a bare SMBRuntimeException. A plain IOException is deliberately not enough: the platform
    // image decoder throws one for a photo it can never decode.
    val connectionFailure = chain.any {
        it is TransportException || it is SMBRuntimeException || it is TimeoutException ||
            it is SocketException || it is InterruptedIOException || it is UnknownHostException
    }
    return if (connectionFailure) SmbFailureKind.TRANSIENT else SmbFailureKind.PER_FILE
}

private val CONFIGURATION_STATUSES = setOf(
    NtStatus.STATUS_LOGON_FAILURE,
    NtStatus.STATUS_ACCOUNT_DISABLED,
    NtStatus.STATUS_BAD_NETWORK_NAME,
    NtStatus.STATUS_BAD_NETWORK_PATH,
)

private val MISSING_FILE_STATUSES = setOf(
    NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
    NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
    NtStatus.STATUS_NO_SUCH_FILE,
)

private val TRANSIENT_STATUSES = setOf(
    NtStatus.STATUS_USER_SESSION_DELETED,
    NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
    NtStatus.STATUS_NETWORK_NAME_DELETED,
    NtStatus.STATUS_CONNECTION_DISCONNECTED,
    NtStatus.STATUS_CONNECTION_RESET,
    NtStatus.STATUS_IO_TIMEOUT,
    NtStatus.STATUS_INSUFFICIENT_RESOURCES,
    NtStatus.STATUS_REQUEST_NOT_ACCEPTED,
    NtStatus.STATUS_CANCELLED,
)

/**
 * Describes a failure for logs using only exception class names and SMB status names. Messages are
 * never included because SMBJ puts server addresses and share paths in them.
 */
internal fun Throwable.safeCauseChain(): String =
    causes().joinToString(" <- ") { it.javaClass.name.substringAfterLast("hierynomus.") }

internal fun Throwable.safeSmbStatus(): String? =
    causes().filterIsInstance<SMBApiException>().firstOrNull()?.status?.name

private fun Throwable.causes(): List<Throwable> =
    generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }
        .take(MAX_CHAIN_DEPTH)
        .toList()

private const val MAX_CHAIN_DEPTH = 8
