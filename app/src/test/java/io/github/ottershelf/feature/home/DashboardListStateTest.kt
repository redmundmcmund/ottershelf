package io.github.ottershelf.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The dashboard's lists and shelves show their first item after a load that puts new items in
 * front (the cached dashboard, then the fresh load), unless the user has scrolled them themselves.
 */
@RunWith(AndroidJUnit4::class)
class DashboardListStateTest {

    @get:Rule
    val rule = createComposeRule()

    private val items = mutableStateOf(listOf(3, 4, 5, 6, 7))
    private lateinit var state: LazyListState

    /** Items 100dp each in a 250dp list, keyed as the Currently Reading rows are. */
    private fun show(horizontal: Boolean = false, plain: Boolean = false) = rule.setContent {
        val ids = items.value
        state = if (plain) rememberLazyListState() else rememberDashboardListState(ids)
        val modifier = Modifier.testTag(LIST).then(if (horizontal) Modifier.size(250.dp, 100.dp) else Modifier.size(100.dp, 250.dp))
        if (horizontal) {
            LazyRow(modifier, state = state) { items(ids, key = { it }) { Box(Modifier.size(100.dp)) } }
        } else {
            LazyColumn(modifier, state = state) { items(ids, key = { it }) { Box(Modifier.size(100.dp)) } }
        }
    }

    private fun assertAtFirst() = rule.runOnIdle {
        assertEquals(0, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
    }

    @Test
    fun aPlainListKeepsTheOldFirstItemSoTheNewOnesAreHidden() {
        // What the phone showed: the lazy list anchors on the item that was first.
        show(plain = true)
        rule.runOnIdle { items.value = listOf(1, 2) + items.value }
        rule.runOnIdle { assertEquals(3, state.layoutInfo.visibleItemsInfo.first().key) }
    }

    @Test
    fun newItemsInFrontShowFromTheFirst() {
        show()
        rule.runOnIdle { items.value = listOf(1, 2) + items.value }
        assertAtFirst()
        rule.runOnIdle { items.value = listOf(0) + items.value.reversed() }
        assertAtFirst()
    }

    @Test
    fun aShelfToo() {
        show(horizontal = true)
        rule.runOnIdle { items.value = listOf(1, 2) + items.value }
        assertAtFirst()
    }

    @Test
    fun theUsersOwnScrollIsKept() {
        show()
        rule.onNodeWithTag(LIST).performTouchInput { swipeUp() }
        val first = rule.runOnIdle {
            assertNotEquals(0, state.firstVisibleItemIndex)
            state.layoutInfo.visibleItemsInfo.first().key
        }
        rule.runOnIdle { items.value = listOf(1, 2) + items.value }
        rule.runOnIdle { assertEquals(first, state.layoutInfo.visibleItemsInfo.first().key) }
    }

    @Test
    fun theUsersShelfScrollIsKept() {
        show(horizontal = true)
        rule.onNodeWithTag(LIST).performTouchInput { swipeLeft() }
        val first = rule.runOnIdle { state.layoutInfo.visibleItemsInfo.first().key }
        rule.runOnIdle { items.value = listOf(1, 2) + items.value }
        rule.runOnIdle { assertEquals(first, state.layoutInfo.visibleItemsInfo.first().key) }
    }

    @Test
    fun scrolledBackToTheStartByTheUserItFollowsTheFirstAgain() {
        show()
        rule.onNodeWithTag(LIST).performTouchInput { swipeUp() }
        rule.onNodeWithTag(LIST).performTouchInput { swipeDown() }
        assertAtFirst()
        rule.runOnIdle { items.value = listOf(1, 2) + items.value }
        assertAtFirst()
    }

    private companion object {
        const val LIST = "list"
    }
}
