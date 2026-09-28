package io.github.ottershelf.feature.comics

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Constraints
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.core.readerprefs.CbxReaderSettings
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The strips lay out any page, however tall: a merged webtoon chapter at fit width used to crash the layout. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ComicsStripTest {

    @get:Rule
    val rule = createComposeRule()

    private val pages = object : ComicSource {
        override val pageCount = 3
        override fun model(index: Int): Any = fakeCover(index)
    }

    private fun showStrip(scrollMode: String, fitMode: String) {
        rule.setContent {
            OttershelfTheme {
                WithFakeCovers {
                    ComicsContent(
                        ComicsUiState(
                            title = "Webtoon",
                            loading = false,
                            source = pages,
                            pageCount = 3,
                            // 720 x 600,000 px: over a million px tall at the phone's 1233 px width.
                            ratios = mapOf(0 to 720f / 600_000f, 1 to 0.66f),
                            settings = CbxReaderSettings(fitMode = fitMode, scrollMode = scrollMode),
                            stripPlaced = true,
                        ),
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().assertExists()
    }

    @Test
    fun aVeryTallPageAtFitWidthInTheLongStrip() = showStrip(ComicsUiState.SCROLL_STRIP, Fit.WIDTH)

    @Test
    fun aVeryTallPageAtActualSizeInTheInfiniteStrip() = showStrip(ComicsUiState.SCROLL_INFINITE, Fit.ACTUAL)

    /** A column 8,400 px wide, as a landscape strip zoomed 3x: Compose's limit there is 65,534 px. */
    @Test
    @Config(qualifiers = "w2800dp-h400dp-xxhdpi")
    fun aVeryTallPageInAVeryWideColumn() = showStrip(ComicsUiState.SCROLL_STRIP, Fit.WIDTH)

    @Test
    fun onlyAPageTooTallForALayoutIsCapped() {
        assertEquals(1868f, stripPageHeight(1233f, 0.66f), 1f)
        // A 720 x 20,000 webtoon page zoomed 3x in portrait keeps the column's width.
        assertEquals(102_750f, stripPageHeight(3699f, 720f / 20_000f), 1f)
        assertEquals(MAX_STRIP_PAGE_HEIGHT_PX, stripPageHeight(1233f, 720f / 600_000f))
        // A zoomed landscape strip (2992 px x 3).
        assertEquals(MAX_WIDE_STRIP_PAGE_HEIGHT_PX, stripPageHeight(8976f, 0.1f))
    }

    /** The caps sit under what Compose can measure: 262,142 px next to 8,190 px, 65,534 px next to 32,766 px. */
    @Test
    fun theCapsFitComposesLimits() {
        Constraints.fixed(WIDE_STRIP_COLUMN_PX.toInt() - 1, MAX_STRIP_PAGE_HEIGHT_PX.toInt())
        Constraints.fixed(32_766, MAX_WIDE_STRIP_PAGE_HEIGHT_PX.toInt())
        assertThrows(IllegalArgumentException::class.java) { Constraints.fixed(WIDE_STRIP_COLUMN_PX.toInt(), MAX_STRIP_PAGE_HEIGHT_PX.toInt()) }
    }
}
