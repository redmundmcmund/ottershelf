package io.github.ottershelf.ui.components

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.ui.theme.OttershelfFonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The placeholder cover's widest word, measured in one layout (a word a line, unwrapped), is the
 * width the old one-layout-per-word measure gave, to the pixel: the type size it picks, and so the
 * cover drawn, can't change. Real text layout (native graphics), the placeholder's title style.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class PlaceholderMeasureTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val measurer = TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(context),
        defaultDensity = Density(context),
        defaultLayoutDirection = LayoutDirection.Ltr,
        cacheSize = 0,
    )

    /** BookCoverPlaceholder's title style at [px] (its sizes are px of the cover's width). */
    private fun style(px: Float) = with(Density(context)) {
        TextStyle(
            fontFamily = OttershelfFonts.Sans,
            fontWeight = FontWeight.ExtraBold,
            fontSize = px.toSp(),
            lineHeight = (px * 1.25f).toSp(),
            textAlign = TextAlign.Center,
        )
    }

    /** What the placeholder measured before: each word its own layout, the widest of them. */
    private fun perWord(text: String, style: TextStyle): Int =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }.maxOfOrNull { word ->
            measurer.measure(word, style, softWrap = false, maxLines = 1).size.width
        } ?: 0

    @Test
    fun oneLayoutGivesTheWidestWordOfTheOnePerWordLayouts() {
        // Cover widths from a small grid cell to a big one (u = width / 200), at each title size.
        val units = listOf(0.72f, 1.13f, 1.5f, 2.37f, 3.1f)
        val bases = listOf(40f, 32f, 24f, 17f, 13f)
        val misses = mutableListOf<String>()
        for (title in TITLES) for (u in units) for (base in bases) {
            val style = style(base * u)
            val expected = perWord(title, style)
            val actual = widestWord(measurer, title, style)
            if (actual != expected) misses += "\"$title\" at ${base * u} px: $actual, was $expected"
        }
        assertEquals(emptyList<String>(), misses)
    }

    @Test
    fun theWidestWordIsWiderThanTheOthersAndNoWordsIsZero() {
        val style = style(24f * 1.5f)
        val wide = widestWord(measurer, "a Honorificabilitudinitatibus b", style)
        assertTrue(wide > widestWord(measurer, "a b", style))
        assertEquals(widestWord(measurer, "Honorificabilitudinitatibus", style), wide)
        assertEquals(0, widestWord(measurer, "", style))
        assertEquals(0, widestWord(measurer, " \t\n ", style))
    }

    private companion object {
        val TITLES = listOf(
            "The Lost World",
            "Dracula",
            "A Study in Scarlet",
            "Mmmmmmmmmmmmmmmmmmmmmmmmmm iiii",
            "  Leading and   trailing\tspaces\n",
            "Alice's Adventures in Wonderland (Vol. 1): \"Off with her head!\"",
            "Die Leiden des jungen Werthers: Donaudampfschifffahrtsgesellschaftskapitän",
            "Doña Perfecta — Benito Pérez Galdós",
            "אהבת ציון",
            "ألف ليلة وليلة",
            "红楼梦 石头记",
            "Война и мир",
            "Stars 🌟 and 🚀 rockets",
            "WWWWW iiiii WWWWW",
            "fi fl ffi office affluent",
        )
    }
}
