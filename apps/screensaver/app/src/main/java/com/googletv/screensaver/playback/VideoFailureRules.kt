package com.googletv.screensaver.playback

/** Classifies player errors using the reader's real SMB failure when available. */
internal object VideoFailureRules {
    fun classify(cause: Throwable?): VideoFailure = when (cause?.smbFailureKind()) {
        SmbFailureKind.TRANSIENT, SmbFailureKind.CONFIGURATION -> VideoFailure.STALLED
        SmbFailureKind.PER_FILE, null -> VideoFailure.UNPLAYABLE
    }
}
