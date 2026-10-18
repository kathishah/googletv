package com.googletv.screensaver

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.googletv.screensaver.playback.BufferSafeguard
import com.googletv.screensaver.playback.PreparedVideo
import com.googletv.screensaver.playback.VideoEvents
import com.googletv.screensaver.playback.VideoFailure
import com.googletv.screensaver.playback.VideoPlayback
import com.googletv.screensaver.playback.VideoStage

/**
 * Plays prepared videos on a [SlideshowView]'s video surface: muted, letterboxed, with a platform
 * `MediaPlayer` per clip. A monitor pauses the player when the download falls behind and resumes it
 * once enough is buffered, and ends a clip at [MAX_CLIP_MILLIS] or when the download stops for good.
 */
class VideoSurfaceStage(private val view: SlideshowView) : VideoStage {
    private val handler = Handler(Looper.getMainLooper())

    override fun play(video: PreparedVideo, events: VideoEvents): VideoPlayback = Playback(video, events).also { it.start() }

    private inner class Playback(private val video: PreparedVideo, private val events: VideoEvents) : VideoPlayback {
        private val reader = video.reader
        private val safeguard = BufferSafeguard()
        private var player: MediaPlayer? = null
        private var ended = false
        private var started = false
        private var paused = false
        private var pausedSince = 0L
        private var pausesTotal = 0
        private var pausedMillisTotal = 0L
        private var downloadReported = false
        private var lastBytes = -1L
        private var lastProgressAt = SystemClock.elapsedRealtime()
        private var firstSampleAt = 0L
        private var surfaceTries = 0
        private val startRetry = Runnable { start() }
        private val beganAt = SystemClock.elapsedRealtime()

        private val monitor = object : Runnable {
            override fun run() {
                if (ended) return
                check()
                if (!ended) handler.postDelayed(this, MONITOR_MILLIS)
            }
        }

        fun start() {
            if (!view.videoSurface.holder.surface.isValid) {
                // The surface appears a frame or two after the view is attached.
                if (++surfaceTries > 20) return finish(failure = VideoFailure.UNPLAYABLE)
                handler.postDelayed(startRetry, 100)
                return
            }
            val mp = MediaPlayer()
            player = mp
            try {
                mp.setDisplay(view.videoSurface.holder)
                mp.setVolume(0f, 0f)
                mp.setOnPreparedListener { if (!ended) { it.start(); handler.post(monitor) } }
                mp.setOnVideoSizeChangedListener { _, width, height -> if (!ended) view.fitVideo(width, height) }
                mp.setOnInfoListener { _, what, _ ->
                    if (!ended && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && !started) {
                        started = true
                        view.revealVideo()
                        view.showVideoCaption(video.label)
                        events.onStarted()
                    }
                    true
                }
                mp.setOnCompletionListener { finish(failure = null) }
                mp.setOnErrorListener { _, what, extra ->
                    Log.w(LOG_TAG, "Video player error what=$what extra=$extra after ${SystemClock.elapsedRealtime() - beganAt} ms")
                    // Data that stopped arriving looks like a player error; a complete file that fails is the file's fault.
                    finish(failure = if (started && !reader.isComplete) VideoFailure.STALLED else VideoFailure.UNPLAYABLE)
                    true
                }
                mp.setDataSource(video.mediaDataSource())
                mp.prepareAsync()
            } catch (error: Throwable) {
                Log.w(LOG_TAG, "Video player setup failed (${error.javaClass.simpleName})")
                finish(failure = VideoFailure.UNPLAYABLE)
            }
        }

        private fun check() {
            val mp = player ?: return
            val positionMillis = runCatching { mp.currentPosition }.getOrDefault(0)
            if (positionMillis >= MAX_CLIP_MILLIS) return finish(failure = null)
            val now = SystemClock.elapsedRealtime()
            val bytes = reader.stats().bytesFetched
            if (bytes != lastBytes) {
                lastBytes = bytes
                lastProgressAt = now
            }
            if (!downloadReported && reader.isComplete) {
                downloadReported = true
                events.onDownloadComplete()
            }
            val durationMillis = video.durationSeconds * 1000
            val videoBytesPerSecond = reader.sizeBytes / video.durationSeconds
            val positionBytes = (positionMillis / durationMillis * reader.sizeBytes).toLong()
            val aheadSeconds = reader.contiguousBytesFrom(positionBytes) / videoBytesPerSecond
            val remainingDownload = reader.estimatedRemainingSeconds()
            val remainingPlayback = (durationMillis - positionMillis) / 1000.0
            val decision = safeguard.decide(
                paused = paused,
                aheadSeconds = aheadSeconds,
                downloadComplete = reader.isComplete,
                finishesInTime = remainingDownload == null || remainingDownload <= remainingPlayback,
                noProgressMillis = now - lastProgressAt,
            )
            when (decision) {
                BufferSafeguard.Decision.PAUSE -> if (started && !paused) {
                    runCatching { mp.pause() }
                    paused = true
                    pausedSince = now
                    pausesTotal += 1
                }
                BufferSafeguard.Decision.RESUME -> if (paused) {
                    runCatching { mp.start() }
                    paused = false
                    pausedMillisTotal += now - pausedSince
                }
                BufferSafeguard.Decision.ABANDON -> finish(failure = VideoFailure.STALLED)
                BufferSafeguard.Decision.CONTINUE -> Unit
            }
        }

        /** Ends the clip: stops the monitor, releases the player, deletes the temp file, then reports (unless cancelled). */
        private fun finish(failure: VideoFailure?, report: Boolean = true) {
            if (ended) return
            ended = true
            handler.removeCallbacks(monitor)
            handler.removeCallbacks(startRetry)
            if (paused) pausedMillisTotal += SystemClock.elapsedRealtime() - pausedSince
            player?.let { mp ->
                runCatching { mp.setOnCompletionListener(null); mp.setOnErrorListener(null); mp.setOnInfoListener(null) }
                runCatching { mp.stop() }
                runCatching { mp.release() }
            }
            player = null
            val stats = reader.stats()
            video.close()
            Log.i(
                LOG_TAG,
                "Video ${if (failure == null) "finished" else "failed ($failure)"} after ${SystemClock.elapsedRealtime() - beganAt} ms: " +
                    "$pausesTotal pauses ($pausedMillisTotal ms), fetched ${stats.bytesFetched} of ${reader.sizeBytes} bytes",
            )
            if (!report) return
            if (failure == null) events.onFinished() else events.onFailed(failure)
        }

        override fun cancel() = finish(failure = null, report = false)
    }

    private companion object {
        const val LOG_TAG = "VideoStage"
        const val MONITOR_MILLIS = 250L
        const val MAX_CLIP_MILLIS = 60_000
    }
}
