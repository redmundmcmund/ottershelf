package io.github.ottershelf.feature.reader.annotations

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.R
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The highlight popup's buttons with Look up added: Note, Look up, Copy, Share and Delete beside
 * the styles where the page is wide enough (411dp or 448dp on common phones); on a narrower one
 * (a larger Display size, split screen) they get a row of their own, so none of them, Delete last,
 * is squeezed or pushed off the card.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w448dp-h998dp-xxhdpi")
class SelectionPopupLayoutTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val highlight = SelectionPopupState(
        text = "You have been in Afghanistan",
        cfi = "epubcfi(/6/14!/4/2,/1:0,/1:28)",
        chapter = "Chapter 3",
        rect = PageRect(40f, 330f, 280f, 382f),
        annotationId = 12,
        color = "#4ADE80",
        style = STYLE_HIGHLIGHT,
        fromTap = true,
    )

    private val labels = listOf(R.string.reader_note_add, R.string.reader_lookup_action, R.string.reader_copy, R.string.reader_share, R.string.reader_highlight_delete)
        .map(context::getString)

    private var pageWidth by mutableStateOf(411.dp)

    private fun show() = rule.setContent {
        OttershelfTheme {
            Box(Modifier.size(pageWidth, 700.dp).testTag(PAGE)) {
                SelectionPopupHost(highlight, SelectionPopupActions(onLookUp = {}))
            }
        }
    }

    /** Each button (its clickable 36dp box, found by its click label), in dp. */
    private fun buttons(): List<DpRect> = labels.map { label ->
        rule.onNode(SemanticsMatcher("clicks to \"$label\"") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }).getBoundsInRoot()
    }

    /** Every button whole, on the page, in one row left to right. */
    private fun assertOneWholeRowOnThePage() {
        val page = rule.onNodeWithTag(PAGE).getBoundsInRoot()
        val buttons = buttons()
        buttons.zip(labels).forEach { (b, label) ->
            assertEquals("$label is squeezed at $pageWidth", 36f, b.width.value, 0.5f)
            // The card keeps its 8dp margin from the page's edges, and the buttons its 8dp padding.
            assertTrue("$label is off the $pageWidth page: $b", b.left >= page.left + 16.dp - 0.5.dp && b.right <= page.right - 16.dp + 0.5.dp)
            assertEquals("$label is out of the row at $pageWidth", buttons.first().top.value, b.top.value, 0.5f)
        }
        buttons.zipWithNext().forEach { (a, b) -> assertTrue("overlap at $pageWidth", a.right <= b.left + 0.5.dp) }
    }

    @Test
    fun atA411dpWidthAllFiveButtonsFit() {
        show()
        assertOneWholeRowOnThePage()
    }

    @Test
    fun onNarrowerPagesDeleteIsNeverSqueezedOff() {
        show()
        for (width in listOf(360.dp, 340.dp, 320.dp, 300.dp)) {
            rule.runOnIdle { pageWidth = width }
            rule.waitForIdle()
            assertOneWholeRowOnThePage()
        }
    }

    private companion object {
        const val PAGE = "page"
    }
}
