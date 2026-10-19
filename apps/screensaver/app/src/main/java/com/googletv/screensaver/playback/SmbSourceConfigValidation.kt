package com.googletv.screensaver.playback

/** Validates and normalizes the values used to open the read-only SMB photo source. */
fun SmbSourceConfig.validated(): SmbSourceConfig {
    val normalizedHost = host.trim()
    require(normalizedHost.isNotEmpty() && '/' !in normalizedHost && '\\' !in normalizedHost && '@' !in normalizedHost) {
        "Enter a valid SMB server address."
    }

    val normalizedShare = share.trim()
    require(normalizedShare.isNotEmpty() && '/' !in normalizedShare && '\\' !in normalizedShare) {
        "Enter a valid SMB share name."
    }

    val segments = rootPath.trim().replace('/', '\\').trim('\\').split('\\')
    require(segments.isNotEmpty() && segments.none { it.isBlank() || it == "." || it == ".." }) {
        "Enter a folder path inside the SMB share."
    }

    return copy(
        host = normalizedHost,
        share = normalizedShare,
        rootPath = segments.joinToString("\\"),
    )
}
