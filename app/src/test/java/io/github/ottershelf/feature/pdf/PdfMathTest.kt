package io.github.ottershelf.feature.pdf

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.github.ottershelf.core.readerprefs.PdfReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfMathTest {

    @Test
    fun fitWidthAndFitPage() {
        // A4 portrait in a 1233 x 2673 px phone: both fits are the width.
        assertEquals(1233 to 1745, PdfMath.fitSize(595f, 842f, 1233f, 2673f, PdfFit.Width))
        assertEquals(1233 to 1745, PdfMath.fitSize(595f, 842f, 1233f, 2673f, PdfFit.Page))
        // The same page on a landscape screen: fit page keeps it whole, fit width overflows the height.
        assertEquals(2673 to 3783, PdfMath.fitSize(595f, 842f, 2673f, 1233f, PdfFit.Width))
        assertEquals(871 to 1233, PdfMath.fitSize(595f, 842f, 2673f, 1233f, PdfFit.Page))
        assertEquals(1 to 1, PdfMath.fitSize(0f, 842f, 100f, 100f, PdfFit.Page))
    }

    @Test
    fun currentPageIsTheMostVisible() {
        val items = listOf(Triple(4, -300, 1000), Triple(5, 700, 1000), Triple(6, 1700, 1000))
        assertEquals(5, PdfMath.currentIndex(items, 0, 2000, atTop = false, atEnd = false))
        assertEquals(4, PdfMath.currentIndex(items, 0, 1200, atTop = false, atEnd = false))
        // A tall page scrolled more than halfway off: the next one shows more.
        assertEquals(8, PdfMath.currentIndex(listOf(Triple(7, -900, 1745), Triple(8, 845, 1745)), 0, 2673, atTop = false, atEnd = false))
        // Pages shorter than half the screen, one put at the top by the opening or a jump: that one,
        // not the one under the middle. Landscape Letter (856 px with the gap) and 16:9 slides (629 px)
        // on a 2290 px phone.
        val letter = listOf(Triple(39, 0, 856), Triple(40, 856, 856), Triple(41, 1712, 856))
        assertEquals(39, PdfMath.currentIndex(letter, 0, 2290, atTop = false, atEnd = false))
        val slides = listOf(Triple(39, 0, 629), Triple(40, 629, 629), Triple(41, 1258, 629), Triple(42, 1887, 629))
        assertEquals(39, PdfMath.currentIndex(slides, 0, 2290, atTop = false, atEnd = false))
        // Scrolled on a little, the page cut off at the top gives way to the next, whole one.
        assertEquals(40, PdfMath.currentIndex(letter.map { (i, o, s) -> Triple(i, o - 100, s) }, 0, 2290, atTop = false, atEnd = false))
        // At the ends: the first and the last page count even when short.
        assertEquals(4, PdfMath.currentIndex(items, 0, 2000, atTop = true, atEnd = false))
        assertEquals(6, PdfMath.currentIndex(items, 0, 2000, atTop = false, atEnd = true))
        assertEquals(null, PdfMath.currentIndex(emptyList(), 0, 2000, atTop = false, atEnd = false))
    }

    @Test
    fun outlineEntryForAPage() {
        val outline = listOf(
            OutlineEntry("Front", 0, 0),
            OutlineEntry("One", 2, 0),
            OutlineEntry("One.1", 2, 1),
            OutlineEntry("Two", 9, 0),
        )
        assertEquals(0, PdfMath.outlineIndex(outline, 1))
        assertEquals(2, PdfMath.outlineIndex(outline, 2))
        assertEquals(2, PdfMath.outlineIndex(outline, 8))
        assertEquals(3, PdfMath.outlineIndex(outline, 40))
        assertEquals(-1, PdfMath.outlineIndex(listOf(OutlineEntry("Late", 5, 0)), 1))
    }

    @Test
    fun snippetAroundAMatch() {
        val text = "The quick brown fox\r\njumps over   the lazy dog and keeps running far away from the farm."
        val s = PdfMath.snippet(text, text.indexOf("lazy"), 4, before = 12, after = 12)
        assertEquals("lazy", s.text.substring(s.matchStart, s.matchStart + s.matchLength))
        assertTrue(s.text.startsWith("…"))
        assertTrue(s.text.endsWith("…"))
        assertTrue("  " !in s.text && "\n" !in s.text)
        assertEquals(Snippet("", 0, 0), PdfMath.snippet("", 0, 3))
        assertEquals(Snippet("", 0, 0), PdfMath.snippet("abc", 7, 3))
    }

    @Test
    fun settingsMapToTheView() {
        // Nothing stored: the phone's default is the continuous strip, at the web's fit page.
        val defaults = PdfSettings.Spec.effective(null, null)
        assertEquals(PdfView(PdfScroll.Continuous, PdfFit.Page, night = false), PdfSettings.view(defaults, night = false))
        // The web's own values still apply when stored.
        val stored = PdfSettings.Spec.effective(JsonObject(mapOf("scrollMode" to JsonPrimitive("page"))), JsonObject(mapOf("zoomMode" to JsonPrimitive("fit-width"))))
        assertEquals(PdfView(PdfScroll.Paged, PdfFit.Width, night = true), PdfSettings.view(stored, night = true))
        // horizontal shows single pages; automatic and custom open as fit page.
        assertEquals(PdfScroll.Paged, PdfSettings.view(PdfReaderSettings(scrollMode = "horizontal"), false).scroll)
        assertEquals(PdfFit.Page, PdfSettings.view(PdfReaderSettings(zoomMode = "automatic"), false).fit)
        // A change sends only its key.
        val changed = PdfSettings.withScroll(defaults, PdfScroll.Paged)
        assertEquals(JsonObject(mapOf("scrollMode" to JsonPrimitive("page"))), PdfSettings.Spec.changes(defaults, changed))
        assertEquals("fit-width", PdfSettings.withFit(defaults, PdfFit.Width).zoomMode)
    }

    @Test
    fun pageSizesUpdateOnlyWhenChanged() {
        val sizes = PdfPageSizes.uniform(4, 612f, 792f)
        assertSame(sizes, sizes.with(1, listOf(612f to 792f, 612f to 792f)))
        val wide = sizes.with(2, listOf(792f to 612f, 612f to 792f, 1f to 1f))
        assertEquals(792f, wide.width(2))
        assertEquals(612f, wide.height(2))
        assertEquals(612f, sizes.width(2))
        assertEquals(4, wide.count)
    }
}
