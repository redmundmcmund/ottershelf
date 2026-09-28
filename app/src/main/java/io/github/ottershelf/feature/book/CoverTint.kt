package io.github.ottershelf.feature.book

import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The colour a book page is tinted with: BookOrbit's web `cover-tint.ts` (primary tone only), on a
 * 32x32 sample of the cover. Near-black and near-white pixels (text, borders) are skipped, pixels
 * are weighted by saturation squared and voted into hue bins, so a cover's dominant colour wins
 * rather than whatever sits in the middle, and a cover with no real colour gets no tint at all.
 * (Ported unchanged from the Nexus app.) Needs a software bitmap: it reads the pixels.
 */
object CoverTint {
    private const val SAMPLE = 32
    private const val MIN_LIGHTNESS = 0.18f
    private const val MAX_LIGHTNESS = 0.82f
    private const val MIN_SATURATION = 0.35f
    private const val MAX_SATURATION = 0.7f
    private const val BINS = 36
    private const val MIN_WEIGHT = 3f
    private const val MIN_TINT_SATURATION = 0.1f

    /** The tint as an opaque ARGB colour (hue of the cover, lightness 50%), or null for none. */
    fun of(bitmap: Bitmap): Int? {
        val small = runCatching { Bitmap.createScaledBitmap(bitmap, SAMPLE, SAMPLE, true) }.getOrNull() ?: return null
        val pixels = IntArray(SAMPLE * SAMPLE)
        small.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)
        if (small !== bitmap) small.recycle()
        return of(pixels)
    }

    /** The same on ARGB pixels (any number of them). */
    fun of(pixels: IntArray): Int? {
        val weight = FloatArray(BINS)
        val sinSum = FloatArray(BINS)
        val cosSum = FloatArray(BINS)
        val satSum = FloatArray(BINS)
        val hsl = FloatArray(3)
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            ColorUtils.colorToHSL(p, hsl)
            if (hsl[2] < MIN_LIGHTNESS || hsl[2] > MAX_LIGHTNESS) continue
            val w = hsl[1] * hsl[1]
            val bin = (hsl[0] / (360f / BINS)).toInt().coerceIn(0, BINS - 1)
            val radians = hsl[0] * PI.toFloat() / 180f
            weight[bin] += w
            sinSum[bin] += w * sin(radians)
            cosSum[bin] += w * cos(radians)
            satSum[bin] += w * hsl[1]
        }

        // Each bin with its neighbours, so a hue on a bin edge isn't split in two.
        var bestWeight = 0f
        var bestSin = 0f
        var bestCos = 0f
        var bestSat = 0f
        for (i in 0 until BINS) {
            var w = 0f
            var s = 0f
            var c = 0f
            var sat = 0f
            for (offset in -1..1) {
                val j = (i + offset + BINS) % BINS
                w += weight[j]
                s += sinSum[j]
                c += cosSum[j]
                sat += satSum[j]
            }
            if (w > bestWeight) {
                bestWeight = w
                bestSin = s
                bestCos = c
                bestSat = sat
            }
        }
        if (bestWeight < MIN_WEIGHT || bestSat / bestWeight < MIN_TINT_SATURATION) return null

        // Hues are angles: average them round the circle (350 and 10 make red, not cyan).
        val hue = ((atan2(bestSin, bestCos) * 180f / PI.toFloat()) + 360f) % 360f
        val saturation = (bestSat / bestWeight).coerceIn(MIN_SATURATION, MAX_SATURATION)
        return ColorUtils.HSLToColor(floatArrayOf(hue, saturation, 0.5f))
    }
}
