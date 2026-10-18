package com.googletv.screensaver

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import com.googletv.screensaver.playback.PairFit
import com.googletv.screensaver.playback.PairLayout
import com.googletv.screensaver.playback.PairLook
import com.googletv.screensaver.playback.PairRect
import com.googletv.screensaver.playback.PairStyle
import kotlin.math.tan
import kotlin.random.Random

/**
 * What the view needs to show a pair: where the two photos go and the full-screen background behind them
 * ([background] null = plain black, the fallback when a style cannot be built). Built off the main thread
 * when the pair is prepared; [recycle] frees the background bitmap once the slide is gone.
 */
class PairScene internal constructor(val layout: PairLayout, val background: Bitmap?) {
    fun recycle() {
        background?.recycle()
    }

    companion object {
        /** Null only when a bitmap has no size; the caller then shows its photos singly. */
        fun build(first: Bitmap, second: Bitmap, look: PairLook?, screenWidth: Int, screenHeight: Int): PairScene? {
            val layout = PairFit.fit(first.width, first.height, second.width, second.height, screenWidth, screenHeight) ?: return null
            val background = if (look == null) null else try {
                PairBackgroundRenderer.render(look, layout, screenWidth, screenHeight, PairBackgroundRenderer.meanColor(first), PairBackgroundRenderer.meanColor(second))
            } catch (error: Throwable) {
                // Out of memory or a canvas failure: the pair still shows, on black.
                Log.w("PairScene", "Background ${look.style} could not be built (${error.javaClass.simpleName}); using black")
                null
            }
            return PairScene(layout, background)
        }
    }
}

/**
 * Draws the three-slice backgrounds with Canvas (no image assets, no new dependencies). Sizes are written
 * for a 1920-wide screen and scaled by width, so the proportions of docs/mockups/portrait-pairs.html hold on any screen.
 * Frame effects hang off each photo's rectangle and are clipped by the top and bottom of the screen.
 */
internal object PairBackgroundRenderer {
    fun render(look: PairLook, layout: PairLayout, width: Int, height: Int, firstMean: Int, secondMean: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setHasAlpha(false)
        val canvas = Canvas(bitmap)
        val unit = width / 100f // the mock's "cqw": 1% of the screen width
        when (look.style) {
            PairStyle.PINSTRIPES -> pinstripes(canvas, width, height, unit, PairColors.tones(firstMean, secondMean), look.variant)
            PairStyle.DIAMOND_LATTICE -> lattice(canvas, width, height, unit, PairColors.tones(firstMean, secondMean), look.variant)
            PairStyle.WALNUT_FRAME -> walnut(canvas, layout, width, height, unit, PairVariants.woods[look.variant], look.variant)
            PairStyle.BRASS_FILLET_MAT -> brass(canvas, layout, width, height, unit, PairVariants.mats[look.variant])
        }
        return bitmap
    }

    /** The average colour of a bitmap from a coarse grid of pixels; cheap and good enough for a background tone. */
    fun meanColor(bitmap: Bitmap, grid: Int = 16): Int {
        val pixels = IntArray(grid * grid)
        for (row in 0 until grid) for (column in 0 until grid) {
            pixels[row * grid + column] = bitmap.getPixel(
                ((column + 0.5f) * bitmap.width / grid).toInt().coerceIn(0, bitmap.width - 1),
                ((row + 0.5f) * bitmap.height / grid).toInt().coerceIn(0, bitmap.height - 1),
            )
        }
        return PairColors.mean(pixels)
    }

    private fun pinstripes(canvas: Canvas, width: Int, height: Int, unit: Float, tones: Tones, variant: Int) {
        canvas.drawColor(tones.base)
        val scale = width / 1920f
        val pitch = (PairVariants.stripePitch[variant] * scale).coerceAtLeast(2f)
        val line = (2f * scale).coerceAtLeast(1f)
        val paint = Paint().apply { color = tones.line; isAntiAlias = false }
        var x = 0f
        while (x < width) {
            canvas.drawRect(x, 0f, x + line, height.toFloat(), paint)
            x += pitch
        }
    }

    private fun lattice(canvas: Canvas, width: Int, height: Int, unit: Float, tones: Tones, variant: Int) {
        canvas.drawColor(tones.base)
        val scale = width / 1920f
        val pitch = PairVariants.latticePitch[variant] * scale
        val across = pitch * 1.41421356f // horizontal spacing of 45 degree lines whose perpendicular spacing is `pitch`
        val paint = Paint().apply { color = tones.line; isAntiAlias = true; strokeWidth = (1.5f * scale).coerceAtLeast(1f) }
        val h = height.toFloat()
        var x = -h
        while (x < width) {
            canvas.drawLine(x, 0f, x + h, h, paint)
            canvas.drawLine(x + h, 0f, x, h, paint)
            x += across
        }
    }

    private fun walnut(canvas: Canvas, layout: PairLayout, width: Int, height: Int, unit: Float, wood: Wood, variant: Int) {
        val fill = Paint().apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), 0f, intArrayOf(wood.edge, wood.center, wood.edge), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        // Three families of nearly vertical grain lines, each a little tilted, with jittered spacing and strength.
        val random = Random(1000L + variant)
        grain(canvas, random, width, height, spacing = 0.47f * unit, thickness = 0.11f * unit, tiltDegrees = 0.6f, color = Color.BLACK, alpha = 0.14f)
        grain(canvas, random, width, height, spacing = 0.83f * unit, thickness = 0.22f * unit, tiltDegrees = -0.3f, color = Color.rgb(255, 235, 205), alpha = 0.07f)
        grain(canvas, random, width, height, spacing = 1.37f * unit, thickness = 0.06f * unit, tiltDegrees = 0.2f, color = Color.BLACK, alpha = 0.10f)
        for (photo in listOf(layout.first, layout.second)) {
            val rect = photo.rect()
            softShadow(canvas, rect, spread = 0.5f * unit, blur = 1.6f * unit, alpha = 0.55f)
            ring(canvas, rect, 0.55f * unit, Color.argb((0.30f * 255).toInt(), Color.red(wood.bevel), Color.green(wood.bevel), Color.blue(wood.bevel)))
            ring(canvas, rect, 0.30f * unit, wood.rebate)
        }
    }

    private fun brass(canvas: Canvas, layout: PairLayout, width: Int, height: Int, unit: Float, mat: Mat) {
        canvas.drawColor(mat.mat)
        // A faint linen weave: horizontal and vertical lines.
        val weave = Paint().apply { color = Color.argb(9, 255, 255, 255); isAntiAlias = false }
        val period = 0.4f * unit
        val thickness = (0.12f * unit).coerceAtLeast(1f)
        var y = 0f
        while (y < height) { canvas.drawRect(0f, y, width.toFloat(), y + thickness, weave); y += period }
        var x = 0f
        while (x < width) { canvas.drawRect(x, 0f, x + thickness, height.toFloat(), weave); x += period }
        for (photo in listOf(layout.first, layout.second)) {
            val rect = photo.rect()
            softShadow(canvas, rect, spread = 0.9f * unit, blur = 1.2f * unit, alpha = 0.40f)
            ring(canvas, rect, 0.78f * unit, mat.inner)
            ring(canvas, rect, 0.62f * unit, mat.mat)
            ring(canvas, rect, 0.22f * unit, mat.outer)
        }
    }

    /** Near-vertical lines across the screen. Each line is a parallelogram edge tilted by [tiltDegrees] over the screen height. */
    private fun grain(canvas: Canvas, random: Random, width: Int, height: Int, spacing: Float, thickness: Float, tiltDegrees: Float, color: Int, alpha: Float) {
        val paint = Paint().apply { isAntiAlias = true; strokeWidth = thickness.coerceAtLeast(1f) }
        val shift = tan(Math.toRadians(tiltDegrees.toDouble())).toFloat() * height
        var x = -Math.abs(shift)
        while (x < width) {
            val strength = (alpha * (0.6f + 0.8f * random.nextFloat())).coerceIn(0f, 1f)
            paint.color = Color.argb((strength * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawLine(x, 0f, x + shift, height.toFloat(), paint)
            x += spacing * (0.65f + 0.7f * random.nextFloat())
        }
    }

    /** A soft dark halo around [rect]: the rect grown by [spread] and blurred by [blur] (a CSS-style blur radius, so sigma = blur / 2). The rings and the photo are drawn over it. */
    private fun softShadow(canvas: Canvas, rect: RectF, spread: Float, blur: Float, alpha: Float) {
        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.argb((alpha * 255).toInt(), 0, 0, 0)
            maskFilter = BlurMaskFilter((blur / 2f / 0.57735f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRect(RectF(rect).apply { inset(-spread, -spread) }, paint)
    }

    private fun ring(canvas: Canvas, rect: RectF, grow: Float, color: Int) {
        canvas.drawRect(RectF(rect).apply { inset(-grow, -grow) }, Paint().apply { this.color = color; isAntiAlias = false })
    }

    private fun PairRect.rect() = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}
