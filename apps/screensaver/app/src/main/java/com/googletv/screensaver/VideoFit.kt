package com.googletv.screensaver

/** Where a clip is drawn: its size on screen, centered, with black around it. */
internal data class VideoFit(val width: Int, val height: Int)

internal object VideoFitRules {
    /**
     * Small, old clips look worse stretched to fill a TV than shown smaller. A clip is scaled up to fit the
     * view (never cropped), but by no more than this factor over its own pixel size.
     */
    const val MAX_UPSCALE = 2.0f

    fun fit(videoWidth: Int, videoHeight: Int, viewWidth: Int, viewHeight: Int, maxUpscale: Float = MAX_UPSCALE): VideoFit? {
        if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return null
        val toFit = minOf(viewWidth.toFloat() / videoWidth, viewHeight.toFloat() / videoHeight)
        val scale = minOf(toFit, maxUpscale)
        return VideoFit((videoWidth * scale).toInt().coerceAtLeast(1), (videoHeight * scale).toInt().coerceAtLeast(1))
    }
}
