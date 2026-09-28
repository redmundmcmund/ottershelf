package io.github.ottershelf.ui.nav

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigatorTest {

    private val allBooks = Route.BookList("all", "All books")

    private val navigator = AppNavigatorState(
        backStack = NavBackStack(Route.Home),
        beforeSearch = mutableStateOf(null),
        searchBar = SearchBarState(mutableStateOf(false), mutableStateOf("")),
    )

    private fun shown() = navigator.backStack.toList()

    @Test
    fun pushesDetailScreensOnTopOfTheRootList() {
        navigator.navigate(Route.BookDetail(5))
        assertEquals(listOf(Route.Home, Route.BookDetail(5)), shown())
        assertTrue(navigator.back())
        assertEquals(listOf<Route>(Route.Home), shown())
        assertFalse(navigator.back()) // nothing left: the system handles Back (the app closes)
    }

    @Test
    fun aDrawerPickReplacesTheRootInsteadOfStacking() {
        navigator.pick(Route.BookList("library:3", "Fiction"))
        navigator.pick(Route.Authors())
        assertEquals(listOf<Route>(Route.Authors()), shown())
        assertFalse(navigator.back())
    }

    @Test
    fun anAuthorsBooksArePushedButARootListOpenedFromAScreenReplaces() {
        navigator.pick(Route.Authors())
        navigator.navigate(Route.BookList("author:5", "Bram Stoker"))
        assertEquals(listOf(Route.Authors(), Route.BookList("author:5", "Bram Stoker")), shown())
        navigator.navigate(Route.BookDetail(9))
        navigator.navigate(Route.BookList("all", "All books"))
        assertEquals(listOf<Route>(allBooks), shown())
    }

    @Test
    fun pickingTheListAlreadyShowingKeepsIt() {
        navigator.navigate(Route.Settings)
        navigator.pick(Route.Home)
        assertEquals(listOf<Route>(Route.Home), shown())
    }

    @Test
    fun aRouteAlreadyOnTheStackIsReturnedTo() {
        navigator.navigate(Route.BookDetail(5))
        navigator.navigate(Route.BookList("series:3", "Dracula"))
        navigator.navigate(Route.BookDetail(5))
        assertEquals(listOf(Route.Home, Route.BookDetail(5)), shown())
    }

    @Test
    fun searchingFromTheDashboardSearchesAllBooksAndClosingReturns() {
        navigator.openSearch()
        assertTrue(navigator.searchBar.open)
        navigator.search("  dracula ", allBooks)
        assertEquals(listOf<Route>(allBooks.copy(query = "dracula")), shown())
        assertEquals(Route.Home, navigator.beforeSearch)

        // A second search still searches what showed before the first.
        navigator.search("messiah", allBooks)
        assertEquals(listOf<Route>(allBooks.copy(query = "messiah")), shown())

        navigator.closeSearch()
        assertEquals(listOf<Route>(Route.Home), shown())
        assertNull(navigator.beforeSearch)
        assertFalse(navigator.searchBar.open)
    }

    @Test
    fun searchingAListSearchesThatList() {
        navigator.pick(Route.Series())
        navigator.search("foundation", allBooks)
        assertEquals(listOf<Route>(Route.Series("foundation")), shown())
        navigator.search("  ", allBooks) // ignored, like SearchView
        assertEquals(listOf<Route>(Route.Series("foundation")), shown())
    }

    @Test
    fun aPickWhileSearchingDropsTheSearchEvenForTheSameList() {
        navigator.pick(allBooks)
        navigator.openSearch()
        navigator.search("dracula", allBooks)
        navigator.pick(allBooks)
        assertEquals(listOf<Route>(allBooks), shown())
        assertNull(navigator.beforeSearch)
        assertFalse(navigator.searchBar.open)
        navigator.closeSearch() // nothing to go back to
        assertEquals(listOf<Route>(allBooks), shown())
    }

    @Test
    fun theDashboardFallsBackToDownloadedWhenItAndTheDrawerBothFailed() {
        navigator.dashboardLoaded(hasContent = false)
        assertEquals(listOf<Route>(Route.Home), shown()) // the drawer's lists are fine so far
        navigator.drawerLoadFailed(true)
        assertEquals(listOf<Route>(Route.Downloads()), shown())
    }

    @Test
    fun theFallbackWaitsForTheDashboardAndKeepsAPushedScreen() {
        navigator.drawerLoadFailed(true)
        assertEquals(listOf<Route>(Route.Home), shown()) // still loading
        navigator.navigate(Route.BookDetail(5))
        navigator.dashboardLoaded(hasContent = false)
        assertEquals(listOf(Route.Downloads(), Route.BookDetail(5)), shown())
    }

    @Test
    fun noFallbackWhenTheDashboardHadSomething() {
        navigator.dashboardLoaded(hasContent = true)
        navigator.dashboardLoaded(hasContent = false) // a later empty refresh doesn't count
        navigator.drawerLoadFailed(true)
        assertEquals(listOf<Route>(Route.Home), shown())
    }

    @Test
    fun replaceSwapsTheTopScreen() {
        navigator.navigate(Route.BookDetail(5))
        navigator.replace(Route.BookDetail(6))
        assertEquals(listOf(Route.Home, Route.BookDetail(6)), shown())
    }

    @Test
    fun routesSurviveTheirSavedForm() {
        val json = kotlinx.serialization.json.Json
        val routes = listOf(
            Route.Home,
            Route.BookList("library:3", "Fiction", "dracula"),
            Route.Downloads("x"),
            Route.Reader(1, 2, "Dracula"),
            Route.Appearance,
            Route.About,
        )
        for (route in routes) {
            assertEquals(route, json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), route)))
        }
    }

    @Test
    fun countsAreCompactedLikeTheWeb() {
        assertEquals("999", compactCount(999))
        assertEquals("1.2K", compactCount(1234))
        assertEquals("2K", compactCount(2000))
        assertEquals("12K", compactCount(12_345))
        assertEquals("1.5M", compactCount(1_500_000))
    }
}
