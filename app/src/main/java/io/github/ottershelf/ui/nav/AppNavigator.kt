package io.github.ottershelf.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.json.Json

/**
 * How screens navigate. Screens get it as a parameter (from NavGraph.kt) and never touch the back
 * stack themselves; tests pass [AppNavigator.None] or a fake.
 */
interface AppNavigator {
    /**
     * Opens [route]. A root list (`Chrome.Root`: the Dashboard, a book list from the drawer,
     * Authors, Series, Downloaded) REPLACES what the content area shows, as a drawer pick does
     * (the Nexus fragment replace), closing any pushed screens. Anything else is pushed on top;
     * a route already on the stack is returned to (the screens above it close) rather than
     * opened twice.
     */
    fun navigate(route: Route)

    /** Replaces the current screen, e.g. one book's page with another's. */
    fun replace(route: Route)

    /** Like system Back. Returns false when there is nothing left to go back to. */
    fun back(): Boolean

    /**
     * The Dashboard reports each load's outcome: [hasContent] false when it got nothing from the
     * server. The first report counts (the Nexus `onDashboardFailed`): if it got nothing and the
     * drawer's lists failed too, the shell shows Downloaded instead, the books that need no server.
     * Later reports only matter once something came.
     */
    fun dashboardLoaded(hasContent: Boolean)

    /** For previews and screenshot tests. */
    object None : AppNavigator {
        override fun navigate(route: Route) {}
        override fun replace(route: Route) {}
        override fun back() = false
        override fun dashboardLoaded(hasContent: Boolean) {}
    }
}

/**
 * The toolbar's search field: whether it's expanded and what's typed. [focusRequested] is set when
 * the user opens it, so the field takes focus (and the keyboard) once, not every time a new root
 * list composes it again.
 */
@Stable
class SearchBarState(open: MutableState<Boolean>, text: MutableState<String>) {
    var open: Boolean by open
        internal set
    var text: String by text
    var focusRequested: Boolean by mutableStateOf(false)
        internal set

    fun focusHandled() {
        focusRequested = false
    }
}

/**
 * The signed-in navigation state: one back stack whose bottom entry is the list picked in the
 * drawer (the [root]) and whose other entries are pushed screens; plus the toolbar's search, as
 * the Nexus MainActivity ran it (`select`, `beforeSearch`). Everything is saved across process
 * death.
 */
@Stable
class AppNavigatorState internal constructor(
    internal val backStack: NavBackStack<Route>,
    beforeSearch: MutableState<Route?>,
    val searchBar: SearchBarState,
) : AppNavigator {

    /** What was showing before search results replaced it; closing the search goes back there. */
    var beforeSearch: Route? by beforeSearch
        private set

    /** The route on screen. */
    val current: Route get() = backStack.last()

    /** The list the content area shows (under any pushed screens): the drawer's selection. */
    val root: Route get() = backStack.first()

    /** The drawer's lists failed to load (set by the shell); see [dashboardLoaded]. */
    private var drawerFailed = false

    /** The Dashboard's first load: null until it reports, then whether it brought anything. */
    private var dashboardHasContent: Boolean? = null

    override fun navigate(route: Route) {
        if (route.chrome == Chrome.Root) return pick(route)
        val existing = backStack.indexOf(route)
        if (existing >= 0) popAbove(existing) else backStack.add(route)
    }

    override fun replace(route: Route) {
        if (route.chrome == Chrome.Root || backStack.size <= 1 || route in backStack) {
            navigate(route)
        } else {
            backStack[backStack.lastIndex] = route
        }
    }

    override fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    /**
     * A drawer pick (or a screen opening a root list). Picked while search results show: go there
     * without the search, even if it's the same list, and close the search field.
     */
    fun pick(route: Route) {
        val searching = beforeSearch != null
        beforeSearch = null
        searchBar.open = false
        searchBar.text = ""
        searchBar.focusRequested = false
        select(route, force = searching)
    }

    fun openSearch() {
        searchBar.open = true
        searchBar.focusRequested = true
    }

    /**
     * Search submitted: search the list that was showing before any search. The Dashboard and
     * the Calendar have nothing of their own to search, so [allBooks] (the All books list) is
     * searched instead.
     */
    fun search(query: String, allBooks: Route.BookList) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val here = beforeSearch ?: root
        beforeSearch = here
        searchBar.focusRequested = false
        val searchable = here.withQuery(trimmed).takeIf { it != here }
        select(searchable ?: allBooks.withQuery(trimmed), force = true)
    }

    /** Leaving search (its arrow, or Back): back to what was showing, without a query. */
    fun closeSearch() {
        searchBar.open = false
        searchBar.text = ""
        searchBar.focusRequested = false
        val back = beforeSearch ?: return
        beforeSearch = null
        select(back, force = true)
    }

    /** The shell reports whether the drawer's lists failed (a new load resets it). */
    fun drawerLoadFailed(failed: Boolean) {
        drawerFailed = failed
        // If the dashboard got nothing either, show what's on the device (if it's still loading,
        // it says when it's done: dashboardLoaded).
        if (failed && dashboardHasContent == false) showDownloadedInstead()
    }

    override fun dashboardLoaded(hasContent: Boolean) {
        if (dashboardHasContent == null) {
            dashboardHasContent = hasContent
            if (!hasContent && drawerFailed) showDownloadedInstead()
        } else if (hasContent) {
            dashboardHasContent = true
        }
    }

    /**
     * Not picked by the user: swaps the Dashboard underneath for Downloaded, leaving any pushed
     * screen (a book opened meanwhile) where it is.
     */
    private fun showDownloadedInstead() {
        if (root == Route.Home) backStack[0] = Route.Downloads()
    }

    /** MainActivity.select: shows [route] as the root list unless it's already showing. */
    private fun select(route: Route, force: Boolean) {
        val same = route == root ||
            (!force && route.query == null && root.query == null && route.sourceKey == root.sourceKey)
        if (!same) {
            if (route == Route.Home) dashboardHasContent = null
            backStack[0] = route
        }
        popAbove(0)
    }

    private fun popAbove(index: Int) {
        while (backStack.size > index + 1) backStack.removeAt(backStack.lastIndex)
    }
}

/** [this] root list searched for [query]. */
private fun Route.withQuery(query: String): Route = when (this) {
    is Route.BookList -> copy(query = query)
    is Route.Authors -> copy(query = query)
    is Route.Series -> copy(query = query)
    is Route.Downloads -> copy(query = query)
    is Route.Notes -> copy(query = query)
    else -> this
}

private val routeJson = Json

/** `beforeSearch` survives process death as the route's JSON. */
private val RouteStateSaver = Saver<MutableState<Route?>, String>(
    save = { state -> state.value?.let { routeJson.encodeToString(Route.serializer(), it) } ?: "" },
    restore = { saved -> mutableStateOf(saved.takeIf { it.isNotEmpty() }?.let { routeJson.decodeFromString(Route.serializer(), it) }) },
)

/**
 * The signed-in navigation state, saved across configuration changes and process death. [start]
 * is the first root list: the Dashboard, or Downloaded when the device is offline.
 */
@Composable
fun rememberAppNavigatorState(start: Route): AppNavigatorState {
    val backStack = rememberSerializable(serializer = NavBackStackSerializer(Route.serializer())) {
        NavBackStack(start)
    }
    val beforeSearch = rememberSaveable(saver = RouteStateSaver) { mutableStateOf(null) }
    val searchOpen = rememberSaveable { mutableStateOf(false) }
    val searchText = rememberSaveable { mutableStateOf("") }
    return remember { AppNavigatorState(backStack, beforeSearch, SearchBarState(searchOpen, searchText)) }
}

/**
 * The entries NavDisplay shows, decorated with saveable state and a ViewModel store per entry: a
 * pushed screen's ViewModels are cleared when it's popped, a root list's when a pick replaces it.
 * Root lists cross-fade into each other; pushed screens use NavDisplay's default transitions.
 */
@Composable
internal fun AppNavigatorState.rememberEntries(content: @Composable (Route) -> Unit): List<NavEntry<Route>> {
    // Entries are only rebuilt when the stack changes; they always call the latest content.
    val latestContent by rememberUpdatedState(content)
    return rememberDecoratedNavEntries(
        backStack = backStack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = { route ->
            val metadata = if (route.chrome == Chrome.Root) RootTransition else emptyMap()
            NavEntry(route, metadata = metadata) { latestContent(it) }
        },
    )
}

private val RootTransition = NavDisplay.transitionSpec {
    fadeIn(tween(ROOT_FADE_MS)) togetherWith fadeOut(tween(ROOT_FADE_MS))
}

private const val ROOT_FADE_MS = 180
