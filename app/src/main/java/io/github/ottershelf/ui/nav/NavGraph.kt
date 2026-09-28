package io.github.ottershelf.ui.nav

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.res.stringResource
import io.github.ottershelf.R
import io.github.ottershelf.feature.achievements.AchievementsScreen
import io.github.ottershelf.feature.achievements.RewindScreen
import io.github.ottershelf.feature.book.BookDetailScreen
import io.github.ottershelf.feature.bookedit.BookEditScreen
import io.github.ottershelf.feature.calendar.CalendarScreen
import io.github.ottershelf.feature.comics.ComicsScreen
import io.github.ottershelf.feature.calendar.DayScreen
import io.github.ottershelf.feature.calendar.ReadingGoalsScreen
import io.github.ottershelf.feature.downloads.DownloadsScreen
import io.github.ottershelf.feature.history.HistoryScreen
import io.github.ottershelf.feature.home.HomeScreen
import io.github.ottershelf.feature.library.AuthorsScreen
import io.github.ottershelf.feature.library.BookListScreen
import io.github.ottershelf.feature.library.SeriesScreen
import io.github.ottershelf.feature.login.LoginScreen
import io.github.ottershelf.feature.notes.BookHighlightsScreen
import io.github.ottershelf.feature.notes.MemorizeScreen
import io.github.ottershelf.feature.notes.NotesScreen
import io.github.ottershelf.feature.pdf.PdfScreen
import io.github.ottershelf.feature.quotes.AddQuoteScreen
import io.github.ottershelf.feature.reader.ReaderScreen
import io.github.ottershelf.feature.requests.RequestPrefill
import io.github.ottershelf.feature.requests.RequestsScreen
import io.github.ottershelf.feature.scan.ScanScreen
import io.github.ottershelf.feature.settings.AboutScreen
import io.github.ottershelf.feature.settings.AppearanceScreen
import io.github.ottershelf.feature.settings.SettingsScreen
import io.github.ottershelf.feature.stats.StatsScreen
import io.github.ottershelf.feature.timer.TimerResultScreen
import io.github.ottershelf.feature.timer.TimerScreen

/** What the signed-in shell gives root lists' frames: the navigation state and the drawer. */
@Stable
internal class RootChrome(
    val navigator: AppNavigatorState,
    val openDrawer: () -> Unit,
    val drawerOpen: () -> Boolean,
)

/**
 * Route -> screen. The `when` is exhaustive, so a new [Route] doesn't compile until it has a
 * screen. Each screen's signature is fixed here: a feature changes its own screen's internals,
 * not this file (see ARCHITECTURE.md, "Shared files"). Root lists get the shell's [RootFrame]
 * (toolbar with the drawer toggle and search) and its content padding; pushed screens draw their
 * own top bar.
 */
@Composable
internal fun RouteContent(route: Route, navigator: AppNavigator, chrome: RootChrome?) {
    when (route) {
        Route.Login -> LoginScreen()
        Route.Home -> Root(route, chrome) { HomeScreen(navigator, it) }
        is Route.BookList ->
            if (route.chrome == Chrome.Root) {
                Root(route, chrome) { BookListScreen(route, navigator, it) }
            } else {
                BookListScreen(route, navigator, PaddingValues())
            }
        is Route.Authors -> Root(route, chrome) { AuthorsScreen(route, navigator, it) }
        is Route.Series -> Root(route, chrome) { SeriesScreen(route, navigator, it) }
        is Route.Downloads -> Root(route, chrome) { DownloadsScreen(route, navigator, it) }
        is Route.BookDetail -> BookDetailScreen(route, navigator)
        is Route.BookEdit -> BookEditScreen(route, navigator)
        is Route.Reader -> ReaderScreen(route, navigator)
        is Route.Comics -> ComicsScreen(route, navigator)
        is Route.Pdf -> PdfScreen(route, navigator)
        Route.Requests -> RequestsScreen(navigator)
        is Route.RequestBook -> RequestsScreen(navigator, RequestPrefill(route.title, route.author, route.isbn))
        is Route.Scan -> ScanScreen(route, navigator)
        Route.Settings -> SettingsScreen(navigator)
        Route.Appearance -> AppearanceScreen(navigator)
        Route.About -> AboutScreen(navigator)
        Route.Calendar -> Root(route, chrome) { CalendarScreen(navigator, it) }
        Route.History -> Root(route, chrome) { HistoryScreen(navigator, it) }
        is Route.Day -> DayScreen(route, navigator)
        Route.ReadingGoals -> ReadingGoalsScreen(navigator)
        is Route.Timer -> TimerScreen(route, navigator)
        is Route.TimerResult -> TimerResultScreen(route, navigator)
        Route.Statistics -> Root(route, chrome) { StatsScreen(navigator, it) }
        Route.Achievements -> Root(route, chrome) { AchievementsScreen(navigator, it) }
        is Route.Rewind -> RewindScreen(route, navigator)
        is Route.Notes -> Root(route, chrome) { NotesScreen(route, navigator, it) }
        is Route.BookHighlights -> BookHighlightsScreen(route, navigator)
        is Route.Memorize -> MemorizeScreen(route, navigator)
        is Route.AddQuote -> AddQuoteScreen(route, navigator)
    }
}

/** A root list's title in the toolbar: the drawer entry's name. */
@Composable
internal fun rootTitle(route: Route): String = when (route) {
    Route.Home -> stringResource(R.string.nav_dashboard)
    is Route.Downloads -> stringResource(R.string.nav_downloaded)
    is Route.Authors -> stringResource(R.string.nav_authors)
    is Route.Series -> stringResource(R.string.nav_series)
    is Route.BookList -> route.title
    Route.Calendar -> stringResource(R.string.nav_calendar)
    Route.History -> stringResource(R.string.history_title)
    Route.Statistics -> stringResource(R.string.stats_title)
    Route.Achievements -> stringResource(R.string.achievements_title)
    is Route.Notes -> stringResource(R.string.notes_title)
    else -> ""
}

@Composable
private fun Root(route: Route, chrome: RootChrome?, content: @Composable (PaddingValues) -> Unit) {
    if (chrome == null) return content(PaddingValues())
    val nav = chrome.navigator
    val allBooks = stringResource(R.string.nav_all_books)
    RootFrame(
        title = rootTitle(route),
        search = nav.searchBar,
        onOpenDrawer = chrome.openDrawer,
        onOpenSearch = nav::openSearch,
        onSubmitSearch = { nav.search(it, Route.BookList("all", allBooks)) },
        onCloseSearch = nav::closeSearch,
        backClosesSearch = nav.current == route && !chrome.drawerOpen(),
        // Scan a book's barcode (feature.scan) beside Search, over the Dashboard and the book grids.
        onScan = if (route == Route.Home || route is Route.BookList) ({ nav.navigate(Route.Scan()) }) else null,
        content = content,
    )
}
