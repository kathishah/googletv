package com.googletv.screensaver.playback

/** Where the picture sits inside the native HEIF decoder's output canvas. */
internal data class HeifContent(val width: Int, val height: Int, val left: Int, val top: Int)

/**
 * The native tile decoder always returns a canvas of the target size (the screen) with the picture centered
 * and black around it. The decoder reports the picture's rectangle as `content=WxH+X+Y` in its result line;
 * the bitmap is cropped to it so a portrait photo is a portrait bitmap, which is what pairing needs.
 */
internal object HeifCanvas {
    private val CONTENT = Regex("""content=(\d+)x(\d+)\+(\d+)\+(\d+)""")

    /** Null when the line has no rectangle, or the rectangle does not fit a [canvasWidth] x [canvasHeight] canvas. */
    fun parseContent(resultLine: String, canvasWidth: Int, canvasHeight: Int): HeifContent? {
        val match = CONTENT.find(resultLine) ?: return null
        val (w, h, x, y) = match.destructured.toList().map { it.toLongOrNull() ?: return null }
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > canvasWidth || y + h > canvasHeight) return null
        return HeifContent(w.toInt(), h.toInt(), x.toInt(), y.toInt())
    }
}
