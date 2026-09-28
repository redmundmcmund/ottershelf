package io.github.ottershelf.feature.stats

import io.github.ottershelf.core.theme.Accent
import io.github.ottershelf.core.theme.ThemePrefs
import io.github.ottershelf.ui.theme.ottershelfColors
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The dataviz validator's checks (scripts/validate_palette.js, ported in [ChartPalette]) on the
 * colours the charts actually draw, for every accent in light and dark, against the card surface:
 * - the two-series scatter (accent + [ChartPalette.secondSeries]) on the all-pairs list: normal-vision
 *   Delta E >= 15 (hard gate) and protan/deutan Delta E >= 6 (the floor; the legend and the readout
 *   are the secondary encoding for the 6-8 band);
 * - the funnel's ordinal ramp: monotone lightness, adjacent Delta L >= 0.06, pale end >= 2:1, one hue.
 */
class ChartPaletteTest {

    private fun mode(dark: Boolean) = if (dark) "dark" else "light"

    @Test
    fun weekendColourStaysApartFromEveryAccent() {
        val failures = mutableListOf<String>()
        var warnBand = 0
        for (accent in Accent.entries) for (dark in listOf(false, true)) {
            val c = ottershelfColors(ThemePrefs(accent = accent), dark)
            val second = ChartPalette.secondSeries(c.primary, dark)
            val cvd = ChartPalette.cvdDeltaE(c.primary, second)
            val normal = ChartPalette.deltaE(c.primary, second)
            if (normal < ChartPalette.NORMAL_FLOOR || cvd < 6.0) failures += "${accent.id} ${mode(dark)}: normal %.1f cvd %.1f".format(normal, cvd)
            if (cvd < ChartPalette.CVD_TARGET) warnBand++
        }
        println("secondSeries: ${Accent.entries.size * 2} accent/mode pairs, $warnBand in the 6-8 CVD band (legend + readout carry identity)")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun funnelRampReadsAsARamp() {
        val failures = mutableListOf<String>()
        var flat = 0
        for (accent in Accent.entries) for (dark in listOf(false, true)) {
            val c = ottershelfColors(ThemePrefs(accent = accent), dark)
            val ramp = ChartPalette.ordinalRamp(c.primary, c.card, 5)
            if (ramp.distinct().size == 1) {
                flat++
                continue
            }
            val ls = ramp.map { ChartPalette.oklabL(it) }
            val monotone = ls.zipWithNext().all { (a, b) -> a <= b } || ls.zipWithNext().all { (a, b) -> a >= b }
            val gaps = ls.zipWithNext().all { (a, b) -> abs(a - b) >= ChartPalette.ORDINAL_MIN_DL }
            val palest = if (dark) ramp.minBy { ChartPalette.oklabL(it) } else ramp.maxBy { ChartPalette.oklabL(it) }
            val lightEnd = ChartPalette.contrast(palest, c.card) >= ChartPalette.ORDINAL_LIGHT_FLOOR - 1e-6
            val chromatic = ramp.all { ChartPalette.oklabChroma(it) >= 0.03 }
            val hues = ramp.map { ChartPalette.oklabHue(it) }
            var spread = hues.max() - hues.min()
            if (spread > 180) spread = 360 - spread
            val oneHue = !chromatic || spread <= 40
            if (!(monotone && gaps && lightEnd && oneHue)) {
                failures += "${accent.id} ${mode(dark)}: monotone=$monotone gaps=$gaps lightEnd=$lightEnd hue=%.0f".format(spread)
            }
        }
        println("ordinalRamp: $flat of ${Accent.entries.size * 2} fall back to one colour (accent too close to the card)")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun comparisonStaysApartFromTheSeries() {
        // The previous period's marks sit over the accent columns: they must not melt into them.
        val failures = mutableListOf<String>()
        for (accent in Accent.entries) for (dark in listOf(false, true)) {
            val c = ottershelfColors(ThemePrefs(accent = accent), dark)
            val comparison = ChartPalette.comparison(c.primary, c.mutedForeground, c.foreground, c.card)
            val apart = ChartPalette.deltaE(c.primary, comparison)
            if (apart < ChartPalette.NORMAL_FLOOR) failures += "${accent.id} ${mode(dark)}: %.1f".format(apart)
            if (comparison != c.mutedForeground && ChartPalette.contrast(comparison, c.card) < ChartPalette.ORDINAL_LIGHT_FLOOR) {
                failures += "${accent.id} ${mode(dark)}: derived grey below 2:1"
            }
        }
        for (dark in listOf(false, true)) {
            val grey = ottershelfColors(ThemePrefs(accent = Accent.GREY), dark)
            assertTrue(ChartPalette.comparison(grey.primary, grey.mutedForeground, grey.foreground, grey.card) != grey.mutedForeground)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun accentMarksAgainstTheCard() {
        // Report only: sub-3:1 accents are relieved by the readout and the direct labels.
        val low = Accent.entries.flatMap { a ->
            listOf(false, true).mapNotNull { dark ->
                val c = ottershelfColors(ThemePrefs(accent = a), dark)
                val ratio = ChartPalette.contrast(c.primary, c.card)
                if (ratio < 3.0) "${a.id} ${mode(dark)} %.2f".format(ratio) else null
            }
        }
        println("accent below 3:1 on the card (relief: readout + labels): ${low.size} ${low.take(12)}")
        val blue = ottershelfColors(ThemePrefs(), dark = true)
        assertTrue(ChartPalette.contrast(blue.primary, blue.card) >= 3.0)
    }
}
