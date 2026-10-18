package com.googletv.screensaver.playback

import android.media.MediaDataSource

/**
 * A video that is safe to start: its end of the file is on this TV and the download is expected to
 * finish before the clip does. The holder owns it and must call [close], which removes the temp file.
 */
class PreparedVideo internal constructor(
    internal val reader: ProgressiveReader,
    val durationSeconds: Double,
    /** Date and place for the caption, or null when the clip has no usable metadata or labels are off. */
    val label: String?,
) {
    internal fun mediaDataSource(): MediaDataSource = ProgressiveMediaDataSource(reader)

    fun close() = reader.close()
}

/** Why a clip stopped. [UNPLAYABLE] clips are dropped from the pool; [STALLED] ones are retried later. */
enum class VideoFailure { UNPLAYABLE, STALLED }

/** Callbacks from the video stage. All are delivered on the main thread, at most once per kind. */
interface VideoEvents {
    /** The first frame is on screen. */
    fun onStarted()

    /** The whole file is on this TV, so the next slide can be prepared without competing for the link. */
    fun onDownloadComplete()

    /** The clip played to its end or to the length cap. */
    fun onFinished()

    fun onFailed(reason: VideoFailure)
}

interface VideoPlayback {
    /** Stops the clip and releases everything without any callback. Safe to call more than once. */
    fun cancel()
}

/** Shows prepared videos. Only the in-app slideshow has one; the screensaver never does. */
interface VideoStage {
    fun play(video: PreparedVideo, events: VideoEvents): VideoPlayback
}

/** A clip that was judged not worth starting now. [retryLater] clips stay in the pool. */
class VideoSkippedException(message: String, val retryLater: Boolean) : Exception(message)
