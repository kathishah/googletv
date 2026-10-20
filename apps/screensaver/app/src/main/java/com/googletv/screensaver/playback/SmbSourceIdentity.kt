package com.googletv.screensaver.playback

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** A private, non-reversible cache key for one normalized SMB library location. */
object SmbSourceIdentity {
    fun forConfig(config: SmbSourceConfig): String {
        val normalized = config.validated()
        val identityInput = listOf(
            normalized.host.lowercase(Locale.ROOT),
            normalized.share.lowercase(Locale.ROOT),
            normalized.rootPath,
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(identityInput.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun catalogFile(directory: File, config: SmbSourceConfig): File =
        File(directory, "photos-album-catalog-${forConfig(config)}.json")
}
