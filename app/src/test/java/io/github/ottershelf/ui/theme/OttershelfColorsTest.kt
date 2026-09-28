package io.github.ottershelf.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.ottershelf.core.theme.Accent
import io.github.ottershelf.core.theme.Radius
import io.github.ottershelf.core.theme.ThemePrefs
import io.github.ottershelf.core.model.ReadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/** The computed tokens against the hex values of the BookOrbit web tokens. */
class OttershelfColorsTest {

    private val blueLight = ottershelfColors(ThemePrefs(), dark = false)
    private val blueDark = ottershelfColors(ThemePrefs(), dark = true) // brightness 35: lift 0.042

    @Test
    fun blueLightNeutralsAndAccent() {
        assertHex("#fafcff", blueLight.background)
        assertHex("#090a0d", blueLight.foreground)
        assertHex("#f5f7fb", blueLight.card)
        assertHex("#f1f3f8", blueLight.sidebar)
        assertHex("#eef0f4", blueLight.muted)
        assertHex("#e6e8ec", blueLight.surface3)
        assertHex("#dfe1e5", blueLight.surface4)
        assertHex("#686969", blueLight.mutedForeground)
        assertHex("#4d4d4e", blueLight.countForeground)
        assertHex("#d5d7db", blueLight.border)
        assertHex("#c2c4c8", blueLight.dotColor)
        assertHex("#0046e9", blueLight.primary)
        assertHex("#fafafa", blueLight.onPrimary)
        assertHex("#cdcfd3", blueLight.volumeUnread)
    }

    @Test
    fun blueDarkAtTheDefaultBrightness() {
        assertHex("#0f131b", blueDark.background)
        assertHex("#fafafb", blueDark.foreground)
        assertHex("#171b23", blueDark.card)
        assertHex("#262b34", blueDark.muted)
        assertHex("#242831", blueDark.surface2)
        assertHex("#323740", blueDark.surface4)
        assertHex("#a6a6a7", blueDark.mutedForeground)
        assertHex("#bdbebe", blueDark.countForeground)
        assertHex("#343942", blueDark.dotColor)
        assertHex("#5f9eff", blueDark.primary)
        assertHex("#0a0a0a", blueDark.onPrimary)
        assertHex("#2d323b", blueDark.volumeUnread) // not lifted
        assertEquals(0.10f, blueDark.border.alpha, 0.01f)
        assertEquals(1f, blueDark.border.red, 0.001f)
    }

    @Test
    fun brightnessLiftsDarkSurfacesOnly() {
        val max = ottershelfColors(ThemePrefs(brightness = 100), dark = true)
        assertHex("#21252e", max.background)
        assertHex("#292e37", max.card)
        val none = ottershelfColors(ThemePrefs(brightness = 0), dark = true)
        assertHex("#070a12", none.background)
        assertHex("#0d1219", none.card)
        assertEquals(blueLight, ottershelfColors(ThemePrefs(brightness = 100), dark = false))
    }

    @Test
    fun neutralAccentsHaveUntintedSurfacesAndFixedSidebars() {
        val whiteLight = ottershelfColors(ThemePrefs(accent = Accent.WHITE), dark = false)
        val whiteDark = ottershelfColors(ThemePrefs(accent = Accent.WHITE), dark = true)
        assertHex("#fcfcfc", whiteLight.background)
        assertHex("#161616", whiteLight.primary)
        assertHex("#fafafa", whiteLight.sidebar)
        assertHex("#131313", whiteDark.background)
        assertHex("#1b1b1b", whiteDark.card)
        assertHex("#fafafa", whiteDark.primary)
        assertHex("#0a0a0a", whiteDark.onPrimary)
        assertHex("#171717", whiteDark.sidebar)
        val grey = ottershelfColors(ThemePrefs(accent = Accent.GREY), dark = false)
        assertHex("#717171", grey.primary)
        assertHex("#0a0a0a", grey.onPrimary) // "K" in the table
        assertHex("#f2f2f2", grey.sidebar)
    }

    @Test
    fun accentPrimariesAndTheirText() {
        assertHex("#3a4af5", ottershelfColors(ThemePrefs(accent = Accent.IRIS), dark = false).primary)
        assertHex("#e26700", ottershelfColors(ThemePrefs(accent = Accent.COPPER), dark = false).primary)
        assertHex("#0a0a0a", ottershelfColors(ThemePrefs(accent = Accent.COPPER), dark = false).onPrimary)
        assertHex("#92b7fe", ottershelfColors(ThemePrefs(accent = Accent.PERIWINKLE), dark = true).primary)
        assertHex("#ffb597", ottershelfColors(ThemePrefs(accent = Accent.PEACH), dark = true).primary)
    }

    @Test
    fun semanticColours() {
        assertHex("#187e36", blueLight.success)
        assertHex("#59be6c", blueDark.success)
        assertHex("#985700", blueLight.warning)
        assertHex("#eeb154", blueDark.warning)
        assertHex("#3d73b6", blueLight.info)
        assertHex("#6fa7ee", blueDark.info)
        assertHex("#e7000b", blueLight.destructive)
        assertHex("#ff6467", blueDark.destructive)
        assertHex("#edc812", blueDark.starHighlight)
        assertHex("#229944", blueLight.volumeRead)
        assertHex("#4ab962", blueDark.volumeRead)
        assertHex("#4675d8", blueLight.formatEbook)
        assertHex("#1c8742", blueLight.formatPill("EPUB"))
        assertHex("#6cc581", blueDark.formatPill("epub"))
        assertHex("#a7aebb", blueDark.formatPill("unknown"))
        assertHex("#1b9247", blueLight.pillSuccess)
        assertHex("#16a34a", OttershelfColors.formatBadge("epub"))
        assertHex("#6b7280", OttershelfColors.formatBadge("xyz"))
        assertHex("#00bc7d", blueDark.readStatus(ReadStatus.READ))
        assertEquals(blueDark.mutedForeground, blueDark.readStatus(ReadStatus.UNREAD))
    }

    @Test
    fun shellAndCardDerivations() {
        assertEquals(0.92f, blueDark.shellSurface.alpha, 0.005f)
        assertEquals(0.80f, ottershelfColors(ThemePrefs(surfaceOpacity = 80), dark = true).shellSurface.alpha, 0.005f)
        assertEquals(0.30f, blueDark.shellBorder.alpha, 0.005f)
        assertEquals(0.12f, blueDark.accentTint.alpha, 0.005f)
        assertEquals(0.40f, blueDark.dashCardBorder.alpha, 0.005f)
        assertHex("#11151d", blueDark.dashCard) // bg-card/30 flattened (the Nexus app used #131823)
        assertTrue(blueDark.dashCard.alpha == 1f)
    }

    @Test
    fun radiusScaleFollowsTheWebsRules() {
        val sharp = OttershelfRadii.of(Radius.SHARP)
        assertEquals(listOf(0.dp, 0.dp, 0.dp, 4.dp, 8.dp, 12.dp, 0.dp), listOf(sharp.sm, sharp.md, sharp.lg, sharp.xl, sharp.xl2, sharp.xl3, sharp.shell))
        val default = OttershelfRadii.of(Radius.DEFAULT)
        assertEquals(listOf(6.dp, 8.dp, 10.dp, 14.dp, 18.dp, 22.dp, 10.dp), listOf(default.sm, default.md, default.lg, default.xl, default.xl2, default.xl3, default.shell))
        val pill = OttershelfRadii.of(Radius.PILL)
        assertEquals(listOf(36.dp, 38.dp, 40.dp, 20.dp), listOf(pill.sm, pill.md, pill.lg, pill.shell))
    }

    private fun assertHex(expected: String, actual: Color, tolerance: Int = 1) {
        val e = expected.removePrefix("#").chunked(2).map { it.toInt(16) }
        val a = listOf(actual.red, actual.green, actual.blue).map { (it * 255f).roundToInt() }
        val ok = e.zip(a).all { (x, y) -> abs(x - y) <= tolerance }
        assertTrue("expected $expected, got #${a.joinToString("") { "%02x".format(it) }}", ok)
    }
}
