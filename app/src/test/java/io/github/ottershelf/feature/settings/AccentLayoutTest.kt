package io.github.ottershelf.feature.settings

import io.github.ottershelf.core.theme.Accent
import io.github.ottershelf.core.theme.AccentTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentLayoutTest {

    /** The picker's inner width: the screen less the page's and the card's 16dp gutters. */
    private fun inner(screenDp: Float) = minOf(screenDp, 640f) - 4 * 16f

    @Test
    fun `a phone in portrait gets rows of 8 that fit, landscape the web's rows of 16`() {
        val portrait = inner(411f)
        assertEquals(8, accentColumns(portrait))
        val swatch = accentSwatchDp(portrait, 8)
        assertTrue("swatch $swatch", swatch >= 28f)
        assertTrue(8 * swatch + 7 * swatch * 0.3f <= portrait)

        val landscape = inner(891f)
        assertEquals(16, accentColumns(landscape))
        val small = accentSwatchDp(landscape, 16)
        assertTrue("swatch $small", small >= 28f)
        assertTrue(16 * small + 15 * small * 0.3f <= landscape)

        assertEquals(4, accentColumns(200f))
        assertEquals(40f, accentSwatchDp(600f, 8), 0f) // capped
    }

    @Test
    fun `rows keep every vivid swatch above its pastel partner, in order`() {
        assertEquals(Accent.rows, accentRows(16))
        val rows = accentRows(8)
        assertEquals(8, rows.size)
        assertTrue(rows.all { it.size == 8 })
        assertEquals(Accent.entries.toList(), rows.chunked(2).flatMap { (vivid, pastel) -> vivid.zip(pastel).flatMap { listOf(it.first, it.second) } })
        rows.forEachIndexed { i, row ->
            val tone = if (i % 2 == 0) AccentTone.Vivid else AccentTone.Pastel
            assertTrue(row.all { it.tone == tone })
        }
    }
}
