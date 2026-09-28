package io.github.ottershelf.feature.home

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * The scroll state of a dashboard list (Currently Reading) or shelf, which shows its first item
 * after every load or refresh.
 *
 * A lazy list keeps its place on the item that was first. So when fresher data puts new items in
 * front of it (the dashboard shows what it had, then the fresh load), the list would open part way
 * along, the first row or cover cut off. Here, when [content] (the list's ids) changes, the list
 * goes back to its first item, unless the user has dragged it themselves and left it away from there:
 * then the user's place is kept. Whether the user has is saved with the position, so a return from a book page
 * keeps it too.
 */
@Composable
internal fun rememberDashboardListState(content: Any?): LazyListState {
    val state = rememberLazyListState()
    var dragged by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { if (it is DragInteraction.Start) dragged = true }
    }
    val shown = remember { ShownContent(content) }
    // After the composition that brought the new items and before the list measures them, so it
    // never shows the old first item's place.
    SideEffect {
        if (content != shown.value) {
            shown.value = content
            val atStart = state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
            if ((!dragged || atStart) && !state.isScrollInProgress) state.requestScrollToItem(0)
        }
    }
    return state
}

/** What the list showed last (not state: nothing needs to recompose when it changes). */
private class ShownContent(var value: Any?)
