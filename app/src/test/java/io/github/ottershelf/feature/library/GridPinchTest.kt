package io.github.ottershelf.feature.library

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The pinch on a real book grid (Robolectric's touch injection): spreading the fingers makes the
 * covers bigger and is remembered, pinching makes them smaller, no cover is tapped or long-pressed
 * on the way, one finger still scrolls, and the book under the fingers stays under them.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class GridPinchTest {

    @get:Rule
    val rule = createComposeRule()

    private val view = mutableStateOf(ListView())
    private val opened = mutableListOf<Long>()
    private val pressed = mutableListOf<Long>()
    private lateinit var grid: LazyGridState

    /** The grid's width inside its padding: 411dp less 6dp each side. */
    private val width = 399f

    private fun show() = rule.setContent {
        OttershelfTheme {
            grid = rememberLazyGridState()
            BookGridContent(
                state = PagedState(items = (1L..120L).map { BookCard(it, "Book $it", listOf("Author")) }, total = 120),
                coverOf = { null },
                onOpen = { opened += it.id },
                onQuickView = { pressed += it.id },
                gridState = grid,
                view = view.value,
                onViewChange = { view.value = it },
                modifier = Modifier.testTag(GRID),
            )
        }
    }

    private fun columns() = GridColumns.count(width, view.value.cellDp)

    private fun spread(from: Float, to: Float) = rule.onNodeWithTag(GRID).performTouchInput {
        pinch(
            start0 = center - Offset(from, 0f),
            end0 = center - Offset(to, 0f),
            start1 = center + Offset(from, 0f),
            end1 = center + Offset(to, 0f),
            durationMillis = 600,
        )
    }

    @Test
    fun spreadingTheFingersMakesTheCoversBiggerAndIsRemembered() {
        show()
        rule.runOnIdle { assertEquals(3, grid.layoutInfo.maxSpan) }
        spread(60f, 420f)
        rule.runOnIdle {
            assertEquals(2, columns())
            assertEquals(2, grid.layoutInfo.maxSpan)
        }
        assertEquals(emptyList<Long>(), opened)
        assertEquals(emptyList<Long>(), pressed)
    }

    @Test
    fun pinchingTheFingersTogetherMakesThemSmaller() {
        show()
        spread(480f, 40f)
        rule.runOnIdle {
            assertEquals(6, columns())
            assertEquals(6, grid.layoutInfo.maxSpan)
        }
        assertEquals(emptyList<Long>(), opened)
        assertEquals(emptyList<Long>(), pressed)
    }

    @Test
    fun aSmallPinchChangesNothing() {
        show()
        spread(200f, 215f)
        rule.runOnIdle { assertEquals(ListView(), view.value) }
    }

    @Test
    fun oneFingerStillScrolls() {
        show()
        rule.onNodeWithTag(GRID).performTouchInput { swipeUp() }
        rule.runOnIdle {
            assertTrue(grid.firstVisibleItemIndex > 0)
            assertEquals(ListView(), view.value)
        }
    }

    @Test
    fun theBookUnderTheFingersStaysUnderThem() {
        show()
        rule.runOnIdle { grid.requestScrollToItem(45) }
        rule.waitForIdle()
        var centerY = 0f
        var centerX = 0f
        var book = -1
        rule.onNodeWithTag(GRID).performTouchInput {
            centerX = center.x
            centerY = center.y
        }
        rule.runOnIdle {
            val info = grid.layoutInfo
            val before = info.beforeContentPadding
            book = info.visibleItemsInfo.first { item ->
                val top = item.offset.y + before
                centerY >= top && centerY < top + item.size.height && centerX >= item.offset.x && centerX < item.offset.x + item.size.width
            }.index
        }
        spread(60f, 420f)
        rule.runOnIdle {
            assertEquals(2, grid.layoutInfo.maxSpan)
            val info = grid.layoutInfo
            val item = info.visibleItemsInfo.first { it.index == book }
            val top = item.offset.y + info.beforeContentPadding
            assertTrue("book $book at $top..${top + item.size.height}, fingers at $centerY", centerY >= top && centerY <= top + item.size.height)
        }
    }

    /** The book at [at] on the grid (it isn't zoomed now) and how far down it [at] is, 0..1. */
    private fun bookAt(at: Offset): Pair<Int, Float> = rule.runOnIdle {
        val info = grid.layoutInfo
        val before = info.beforeContentPadding
        val item = info.visibleItemsInfo.first { item ->
            val top = item.offset.y + before
            at.y >= top && at.y < top + item.size.height && at.x >= item.offset.x && at.x < item.offset.x + item.size.width
        }
        item.index to (at.y - item.offset.y - before) / item.size.height
    }

    /** How far down book [index] the height [y] is, 0..1 (outside it: below 0 or above 1). */
    private fun fractionOf(index: Int, y: Float): Float = rule.runOnIdle {
        val info = grid.layoutInfo
        val item = info.visibleItemsInfo.first { it.index == index }
        (y - item.offset.y - info.beforeContentPadding) / item.size.height
    }

    /**
     * Real fingers rarely land together: the first goes down alone, the second a moment later
     * elsewhere (here one below and to the right of the other, 1400px apart). Both then move apart
     * ([spread] > 1) or together about their middle, and lift.
     */
    private fun staggeredPinch(middle: Offset, spread: Float) = rule.onNodeWithTag(GRID).performTouchInput {
        val half = Offset(250f, 700f)
        down(0, middle + half)
        advanceEventTime(120)
        down(1, middle - half)
        val moves = 30
        for (i in 1..moves) {
            val k = 1f + (spread - 1f) * i / moves
            updatePointerTo(0, middle + half * k)
            updatePointerTo(1, middle - half * k)
            move()
        }
        up(0)
        up(1)
    }

    @Test
    fun aSecondFingerLandingLaterKeepsTheSamePointOfTheBookUnderTheFingers() {
        show()
        rule.runOnIdle { grid.requestScrollToItem(45) }
        rule.waitForIdle()
        var middle = Offset.Zero
        rule.onNodeWithTag(GRID).performTouchInput { middle = center }
        val (book, fraction) = bookAt(middle)
        // Apart to 1.4 times: one step, three columns to two (and none more on lifting).
        staggeredPinch(middle, 1.4f)
        rule.runOnIdle { assertEquals(2, grid.layoutInfo.maxSpan) }
        val after = fractionOf(book, middle.y)
        assertEquals("book $book was $fraction of its height under the fingers, now $after", fraction, after, 0.05f)
        assertEquals(emptyList<Long>(), opened)
        assertEquals(emptyList<Long>(), pressed)
    }

    @Test
    fun aSecondFingerLandingLaterKeepsTheBookInPlaceWhenPinchingToo() {
        show()
        rule.runOnIdle { grid.requestScrollToItem(45) }
        rule.waitForIdle()
        var middle = Offset.Zero
        rule.onNodeWithTag(GRID).performTouchInput { middle = center }
        val (book, fraction) = bookAt(middle)
        // Together to 0.78 times: three columns to four.
        staggeredPinch(middle, 0.78f)
        rule.runOnIdle { assertEquals(4, grid.layoutInfo.maxSpan) }
        val after = fractionOf(book, middle.y)
        assertEquals("book $book was $fraction of its height under the fingers, now $after", fraction, after, 0.05f)
    }

    private companion object {
        const val GRID = "grid"
    }
}
