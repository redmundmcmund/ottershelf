package io.github.ottershelf.feature.pdf

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The continuous strip lays out a page of any shape: an extreme MediaBox at fit width used to crash the layout. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PdfStripTest {

    @get:Rule
    val rule = createComposeRule()

    private object NoBitmaps : PdfPageSource {
        override fun cached(index: Int, width: Int): ImageBitmap? = null
        override suspend fun render(index: Int, width: Int, height: Int): ImageBitmap? = null
        override suspend fun renderRegion(index: Int, fullWidth: Int, fullHeight: Int, region: IntRect): ImageBitmap? = null
        override fun linkAt(index: Int, x: Float, y: Float): PdfLink? = null
    }

    private fun showStrip() {
        rule.setContent {
            ContinuousPages(
                source = NoBitmaps,
                // 3 x 14400 pt, the largest page the PDF format allows: millions of px at fit width.
                sizes = PdfPageSizes.uniform(3, 3f, 14_400f),
                fit = PdfFit.Width,
                night = false,
                initialPage = 0,
                jumps = emptyFlow(),
                marks = PdfMarks.None,
                events = PdfViewerEvents(),
            )
        }
        rule.waitForIdle()
        rule.onRoot().assertExists()
    }

    @Test
    fun anExtremelyTallPageAtFitWidth() = showStrip()

    /** A screen 8,400 px wide: Compose's limit there is 65,534 px. */
    @Test
    @Config(qualifiers = "w2800dp-h400dp-xxhdpi")
    fun anExtremelyTallPageOnAVeryWideScreen() = showStrip()

    @Test
    fun onlyAPageTooTallForALayoutIsScaledDown() {
        assertEquals(IntSize(1233, 1745), stripPageSize(1233, 1745, 1233f))
        // A long receipt keeps the screen's width.
        assertEquals(IntSize(1233, 200_000), stripPageSize(1233, 200_000, 1233f))
        // Kept its shape: 1233 x 5,918,400 becomes 52 x 250,000.
        assertEquals(IntSize(52, MAX_STRIP_PAGE_HEIGHT), stripPageSize(1233, 5_918_400, 1233f))
        assertEquals(IntSize(1, MAX_STRIP_PAGE_HEIGHT), stripPageSize(1, 9_000_000, 1233f))
        assertEquals(IntSize(5040, MAX_WIDE_STRIP_PAGE_HEIGHT), stripPageSize(8400, 100_000, 8400f))
    }

    /** The caps and a page's gap fit what Compose can measure: 262,142 px next to 8,190 px, 65,534 px next to 32,766 px. */
    @Test
    fun theCapsFitComposesLimits() {
        Constraints.fixed(WIDE_STRIP_PX.toInt() - 1, MAX_STRIP_PAGE_HEIGHT + 1000)
        Constraints.fixed(32_766, MAX_WIDE_STRIP_PAGE_HEIGHT + 1000)
        assertThrows(IllegalArgumentException::class.java) { Constraints.fixed(WIDE_STRIP_PX.toInt(), MAX_STRIP_PAGE_HEIGHT) }
    }
}
