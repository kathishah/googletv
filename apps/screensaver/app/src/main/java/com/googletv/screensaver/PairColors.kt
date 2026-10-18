package com.googletv.screensaver

import com.googletv.screensaver.playback.PairLook
import com.googletv.screensaver.playback.PairStyle

/** The two tones of a photo-tinted background: a dark [base] and a slightly lighter [line] drawn on it. Both are 0xFFRRGGBB. */
internal data class Tones(val base: Int, val line: Int)

/** Wood for a frame: the grain runs from [edge] at the sides to [center] in the middle. [rebate] and [bevel] are the lines beside each photo. */
internal data class Wood(val name: String, val edge: Int, val center: Int, val rebate: Int, val bevel: Int)

/** A mat with two thin lines around each photo: [outer] is next to the photo, [inner] is the farther one. */
internal data class Mat(val name: String, val mat: Int, val outer: Int, val inner: Int)

/** The variants of each [PairStyle]; every list has exactly [PairLook.VARIANTS] entries. */
internal object PairVariants {
    /** Pinstripe pitch in pixels on a 1920-wide screen (the lines are 2 px). */
    val stripePitch = intArrayOf(8, 10, 14)

    /** Diamond lattice spacing between parallel lines, perpendicular distance, in pixels on a 1920-wide screen. */
    val latticePitch = floatArrayOf(20f, 26.5f, 37f)

    val woods = listOf(
        Wood("walnut", 0xFF5B3B24.toInt(), 0xFF704A2C.toInt(), 0xFF24140A.toInt(), 0xFFFFE2B4.toInt()),
        Wood("oak", 0xFFB58A55.toInt(), 0xFFCFA56D.toInt(), 0xFF4A3319.toInt(), 0xFFFFF0D2.toInt()),
        Wood("cherry", 0xFF6E2F20.toInt(), 0xFF8A4429.toInt(), 0xFF2A100A.toInt(), 0xFFFFD2B8.toInt()),
    )

    val mats = listOf(
        Mat("brass on charcoal", 0xFF1B1814.toInt(), 0xFFC9A95F.toInt(), 0xFF9C8044.toInt()),
        Mat("silver on navy", 0xFF14202E.toInt(), 0xFFCFD6DD.toInt(), 0xFF8D98A4.toInt()),
        Mat("brass on navy", 0xFF14202E.toInt(), 0xFFC9A95F.toInt(), 0xFF9C8044.toInt()),
    )

    /** A short name for logs ("Walnut frame, oak"); no photo information. */
    fun describe(look: PairLook): String = when (look.style) {
        PairStyle.PINSTRIPES -> "${look.style.label}, ${stripePitch[look.variant]} px"
        PairStyle.DIAMOND_LATTICE -> "${look.style.label}, ${arrayOf("small", "medium", "large")[look.variant]}"
        PairStyle.WALNUT_FRAME -> "${look.style.label}, ${woods[look.variant].name}"
        PairStyle.BRASS_FILLET_MAT -> "${look.style.label}, ${mats[look.variant].name}"
    }
}

/** Colour arithmetic for the backgrounds, on plain ints so it runs in JVM tests. */
internal object PairColors {
    private const val BASE_VALUE = 0.20f
    private const val LINE_VALUE = 0.32f

    fun mean(pixels: IntArray): Int {
        if (pixels.isEmpty()) return 0xFF808080.toInt()
        var r = 0L; var g = 0L; var b = 0L
        for (p in pixels) { r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF }
        val n = pixels.size
        return pack((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** Half of each channel of [a] plus half of [b]. */
    fun mix(a: Int, b: Int): Int = pack(
        (((a shr 16) and 0xFF) + ((b shr 16) and 0xFF)) / 2,
        (((a shr 8) and 0xFF) + ((b shr 8) and 0xFF)) / 2,
        ((a and 0xFF) + (b and 0xFF)) / 2,
    )

    /**
     * Tones for pinstripes and lattice, taken from the two photos' mean colours: the mixed mean's hue, kept
     * quiet. The base is dark (value 0.20) and the line only a little lighter (0.32), so the pattern never
     * competes with the photos or their labels. Near-grey photos still get a faint tint.
     */
    fun tones(firstMean: Int, secondMean: Int): Tones {
        val (h, s, _) = toHsv(mix(firstMean, secondMean))
        return Tones(
            base = fromHsv(h, s.coerceIn(0.12f, 0.55f), BASE_VALUE),
            line = fromHsv(h, (s * 0.9f).coerceIn(0.10f, 0.45f), LINE_VALUE),
        )
    }

    /** Hue in degrees 0 until 360, saturation and value 0..1. */
    fun toHsv(color: Int): Triple<Float, Float, Float> {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return Triple(h, if (max == 0f) 0f else d / max, max)
    }

    fun fromHsv(hue: Float, saturation: Float, value: Float): Int {
        val c = value * saturation
        val x = c * (1f - Math.abs((hue / 60f) % 2f - 1f))
        val m = value - c
        val (r, g, b) = when ((hue / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return pack(Math.round((r + m) * 255f), Math.round((g + m) * 255f), Math.round((b + m) * 255f))
    }

    private fun pack(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
