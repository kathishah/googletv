package com.googletv.screensaver.playback

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.googletv.screensaver.AlbumCoverRules
import java.io.File

/**
 * Takes one poster frame from a video on the share without downloading the clip: it fetches only the
 * end of the file (the index) and the first few blocks through a [ProgressiveReader], hands that to
 * `MediaMetadataRetriever`, and closes the reader, which stops the rest of the download and deletes
 * the temp file. Blocks the retriever asks for beyond those are fetched on demand.
 */
internal object VideoPoster {
    private const val HEAD_BLOCKS = 3
    private const val READY_TIMEOUT_MILLIS = 20_000L
    private const val LOG_TAG = "VideoPoster"

    /** A frame scaled to fit [targetWidth] by [targetHeight], or null if the clip cannot be read or decoded. */
    fun load(config: SmbSourceConfig, video: VideoItem, cacheDirectory: File, targetWidth: Int, targetHeight: Int): Bitmap? {
        val began = System.nanoTime()
        val reader = try {
            ProgressiveReader(SmbRemoteVideo.open(config, video.path), ProgressiveReader.newCacheFile(cacheDirectory), video.durationSeconds)
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "Poster source could not be opened (${error.safeCauseChain()}, ${error.safeSmbStatus()})")
            return null
        }
        try {
            reader.start()
            if (!reader.awaitHeadAndTail(HEAD_BLOCKS, READY_TIMEOUT_MILLIS)) {
                Log.w(LOG_TAG, "Poster source was not ready in time")
                return null
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(ProgressiveMediaDataSource(reader))
                // A second in avoids the black or blurry first frame many clips start with.
                val atMicros = minOf(1_000_000L, (video.durationSeconds * 1_000_000 / 4).toLong())
                val frame = retriever.getFrameAtTime(atMicros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return null
                val (width, height) = AlbumCoverRules.posterSize(frame.width, frame.height, targetWidth, targetHeight)
                val poster = if (width == frame.width && height == frame.height) frame
                else Bitmap.createScaledBitmap(frame, width, height, true).also { frame.recycle() }
                Log.i(
                    LOG_TAG,
                    "Poster frame ${poster.width}x${poster.height} in ${(System.nanoTime() - began) / 1_000_000} ms, " +
                        "${reader.stats().bytesFetched / 1024} KiB of ${video.sizeBytes / 1024} KiB fetched",
                )
                return poster
            } finally {
                runCatching { retriever.release() }
            }
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "Poster frame failed (${error.javaClass.simpleName})")
            return null
        } finally {
            reader.close()
        }
    }
}
