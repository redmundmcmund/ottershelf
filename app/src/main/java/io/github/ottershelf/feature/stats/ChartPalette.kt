package io.github.ottershelf.feature.stats

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow

/**
 * The charts' colours, from the theme (the dataviz method with BookOrbit's tokens as its
 * parameters):
 * - one series (almost every chart) is the accent, `colors.primary`;
 * - a comparison (the previous period, the goal line) is the de-emphasis grey, `mutedForeground`,
 *   or, beside a grey accent, a fainter grey clearly apart from it ([comparison]);
 * - magnitude (the heatmap) is one hue: the accent stepped over the card ([heatRamp]);
 * - ordered stages (the funnel) are an ordinal ramp of the accent whose pale end still clears 2:1
 *   ([ordinalRamp]);
 * - a second identity (weekend sessions) is the first reference slot that stays apart from the
 *   accent under protan/deutan simulation and for normal vision ([secondSeries]).
 *
 * The math is the dataviz validator's (OKLab x100 Delta E, Machado 2009 at severity 1, WCAG
 * contrast), shared with ChartPaletteTest, which runs the validator's checks on every accent.
 */
object ChartPalette {

    /** Dataviz reference slots 2, 1, 3, 5, 7 (orange, blue, aqua, magenta, violet), light and dark steps. */
    private val candidatesLight = listOf(0xFFEB6834, 0xFF2A78D6, 0xFF1BAF7A, 0xFFE87BA4, 0xFF4A3AA7).map { Color(it) }
    private val candidatesDark = listOf(0xFFD95926, 0xFF3987E5, 0xFF199E70, 0xFFD55181, 0xFF9085E9).map { Color(it) }

    const val CVD_TARGET = 8.0
    const val NORMAL_FLOOR = 15.0
    const val ORDINAL_MIN_DL = 0.06
    const val ORDINAL_LIGHT_FLOOR = 2.0

    /** The weekend colour beside the accent: the first candidate that passes both gates (all pairs). */
    fun secondSeries(accent: Color, dark: Boolean): Color {
        val candidates = if (dark) candidatesDark else candidatesLight
        return candidates.firstOrNull { cvdDeltaE(accent, it) >= CVD_TARGET && deltaE(accent, it) >= NORMAL_FLOOR }
            ?: candidates.maxBy { minOf(cvdDeltaE(accent, it), deltaE(accent, it)) }
    }

    /**
     * [steps] colours from the full accent down to the palest accent-over-[surface] that still has
     * 2:1 contrast, evenly spaced in alpha (so lightness is monotone). When the accent itself is
     * too close to the surface for visible steps, every stage takes the accent.
     */
    fun ordinalRamp(accent: Color, surface: Color, steps: Int): List<Color> {
        val solid = accent.copy(alpha = 1f)
        var minAlpha = 1f
        var a = 1f
        while (a >= 0.2f) {
            if (contrast(solid.copy(alpha = a).compositeOver(surface), surface) >= ORDINAL_LIGHT_FLOOR) minAlpha = a else break
            a -= 0.02f
        }
        val ramp = (0 until steps).map { i ->
            val alpha = 1f - (1f - minAlpha) * i / (steps - 1).coerceAtLeast(1)
            solid.copy(alpha = alpha).compositeOver(surface)
        }
        val gapsOk = ramp.zipWithNext().all { (x, y) -> kotlin.math.abs(oklabL(x) - oklabL(y)) >= ORDINAL_MIN_DL }
        return if (gapsOk) ramp else List(steps) { solid }
    }

    /**
     * The comparison grey (the previous period, the goal line): `mutedForeground`, unless the accent
     * is itself a grey that close (the Grey accent), where the previous period's marks would vanish
     * into the columns. Then it is the palest foreground-over-[surface] that still has 2:1 against
     * the surface and stands [NORMAL_FLOOR] apart from the accent.
     */
    fun comparison(accent: Color, muted: Color, foreground: Color, surface: Color): Color {
        if (deltaE(accent, muted) >= NORMAL_FLOOR) return muted
        var a = 0.2f
        while (a <= 1f) {
            val c = foreground.copy(alpha = a).compositeOver(surface)
            if (contrast(c, surface) >= ORDINAL_LIGHT_FLOOR && deltaE(c, accent) >= NORMAL_FLOOR) return c
            a += 0.02f
        }
        return muted
    }

    /** The heatmap's four reading levels (level 0 is `colors.muted`): the accent at rising strength. */
    fun heatRamp(accent: Color, surface: Color): List<Color> =
        listOf(0.28f, 0.5f, 0.75f, 1f).map { accent.copy(alpha = it).compositeOver(surface) }

    // --- the validator's math -----------------------------------------------------------------

    private val machadoProtan = arrayOf(
        doubleArrayOf(0.152286, 1.052583, -0.204868),
        doubleArrayOf(0.114503, 0.786281, 0.099216),
        doubleArrayOf(-0.003882, -0.048116, 1.051998),
    )
    private val machadoDeutan = arrayOf(
        doubleArrayOf(0.367322, 0.860646, -0.227968),
        doubleArrayOf(0.280085, 0.672501, 0.047413),
        doubleArrayOf(-0.011820, 0.042940, 0.968881),
    )

    private fun s2lin(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    /** 8-bit sRGB (as a hex would carry it), then linear. */
    private fun lin(c: Color): DoubleArray {
        fun q(v: Float) = (Math.round(v.coerceIn(0f, 1f) * 255) / 255.0)
        return doubleArrayOf(s2lin(q(c.red)), s2lin(q(c.green)), s2lin(q(c.blue)))
    }

    private fun oklab(rgb: DoubleArray): DoubleArray {
        val (r, g, b) = Triple(rgb[0], rgb[1], rgb[2])
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    private fun simulate(c: Color, m: Array<DoubleArray>): DoubleArray {
        val v = lin(c)
        return DoubleArray(3) { i -> (m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2]).coerceIn(0.0, 1.0) }
    }

    private fun distance(a: DoubleArray, b: DoubleArray) = 100 * hypot(hypot(a[0] - b[0], a[1] - b[1]), a[2] - b[2])

    /** Unsimulated OKLab Delta E x100. */
    fun deltaE(a: Color, b: Color): Double = distance(oklab(lin(a)), oklab(lin(b)))

    /** The worse of protan and deutan Delta E x100. */
    fun cvdDeltaE(a: Color, b: Color): Double = minOf(
        distance(oklab(simulate(a, machadoProtan)), oklab(simulate(b, machadoProtan))),
        distance(oklab(simulate(a, machadoDeutan)), oklab(simulate(b, machadoDeutan))),
    )

    fun oklabL(c: Color): Double = oklab(lin(c))[0]

    fun oklabChroma(c: Color): Double = oklab(lin(c)).let { hypot(it[1], it[2]) }

    fun oklabHue(c: Color): Double = oklab(lin(c)).let { ((Math.toDegrees(atan2(it[2], it[1])) % 360) + 360) % 360 }

    private fun luminance(c: Color): Double = lin(c).let { 0.2126 * it[0] + 0.7152 * it[1] + 0.0722 * it[2] }

    /** WCAG contrast ratio. */
    fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
