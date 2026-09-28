package io.github.ottershelf.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/** oklch() against the sRGB hex values of the BookOrbit web tokens. */
class OklchTest {

    @Test
    fun matchesTheWebsHexValues() {
        assertHex("#fafcff", oklch(0.99f, 0.006f, 263f)) // light background, blue
        assertHex("#fcfcfc", oklch(0.99f, 0f, 0f)) // light background, neutral
        assertHex("#0f131b", oklch(0.145f + 0.042f, 0.0175f, 263f)) // dark background at the default lift
        assertHex("#171b23", oklch(0.18f + 0.042f, 0.0175f, 263f)) // dark card at the default lift
        assertHex("#070a12", oklch(0.145f, 0.0175f, 263f)) // dark background, no lift
        assertHex("#5f9eff", oklch(0.72f, 0.2f, 263f)) // blue primary, dark (clipped)
        assertHex("#0046e9", oklch(0.487f, 0.25f, 263f)) // blue primary, light (clipped)
        assertHex("#3a4af5", oklch(0.52f, 0.25f, 270f)) // iris, light
        assertHex("#686969", oklch(0.52f, 0.001f, 263f)) // muted foreground, light
        assertHex("#a6a6a7", oklch(0.725f, 0.001f, 263f)) // muted foreground, dark
        assertHex("#187e36", oklch(0.52f, 0.14f, 148f)) // success, light
        assertHex("#985700", oklch(0.52f, 0.13f, 68f)) // warning, light (clipped)
        assertHex("#eeb154", oklch(0.8f, 0.13f, 75f)) // warning, dark
        assertHex("#d5d7db", oklch(0.878f, 0.006f, 263f)) // border, light
        assertHex("#e7000b", oklch(0.577f, 0.245f, 27.325f)) // destructive, light (clipped)
    }

    private fun assertHex(expected: String, actual: Color) {
        val e = expected.removePrefix("#").chunked(2).map { it.toInt(16) }
        val a = listOf(actual.red, actual.green, actual.blue).map { (it * 255f).roundToInt() }
        val ok = e.zip(a).all { (x, y) -> abs(x - y) <= 1 }
        assertTrue("expected $expected, got ${a.joinToString("") { "%02x".format(it) }}", ok)
    }
}
