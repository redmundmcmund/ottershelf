package io.github.ottershelf.ui.nav

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.ottershelf.AppContainer
import io.github.ottershelf.R
import io.github.ottershelf.core.download.DownloadEvent
import io.github.ottershelf.core.model.BookSource
import io.github.ottershelf.core.session.AuthState
import io.github.ottershelf.core.theme.ThemeSyncEvent
import io.github.ottershelf.core.tracking.TrackingEvent

/**
 * The drawer: who's signed in where, its rows, and whether loading them failed.
 * [failed] is the Nexus `navFailed` (false again while a new load runs); [failureMessage] is what
 * the header says about the last failure, until a load succeeds.
 */
@Immutable
data class DrawerUiState(
    val username: String = "",
    val server: String = "",
    val entries: List<DrawerEntry> = emptyList(),
    val failed: Boolean = false,
    val failureMessage: String? = null,
)

/**
 * The signed-in shell's own state: the drawer's lists (the Nexus MainActivity.loadNavigation),
 * download results and theme-save failures to announce, and signing out.
 */
class ShellViewModel(private val container: AppContainer) : ViewModel() {

    private val drawer = MutableStateFlow(
        DrawerUiState(
            username = container.session.username.orEmpty(),
            server = container.session.serverUrl.orEmpty(),
            entries = listOf(dashboardEntry(), downloadedEntry()),
        ),
    )

    /** The drawer, with Downloaded's count from Downloads' cache (never a folder scan here). */
    val state: StateFlow<DrawerUiState> = combine(drawer, container.downloads.count) { d, count -> d.withDownloaded(count) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, drawer.value.withDownloaded(container.downloads.count.value))

    val downloadEvents: SharedFlow<DownloadEvent> get() = container.downloads.events

    /** A download result's snackbar has been shown (else it becomes a notification when the app leaves). */
    fun downloadShown(event: DownloadEvent) = container.downloads.shown(event)

    val themeEvents: SharedFlow<ThemeSyncEvent> get() = container.theme.events

    /** Queued reading sessions the server refused (the shell announces them). */
    val trackingEvents: SharedFlow<TrackingEvent> get() = container.tracking.events

    private var loading: Job? = null

    init {
        container.downloads.recount() // this account's (it may just have signed in)
        load()
        // Back online after the lists failed: try again.
        viewModelScope.launch { container.online.collect { online -> if (online && drawer.value.failed) load() } }
    }

    /** Loads the drawer's lists (also the Retry row). A load already under way is left to finish. */
    fun load() {
        if (loading?.isActive == true) return
        drawer.update { it.copy(failed = false) }
        loading = viewModelScope.launch {
            val api = container.api
            try {
                // Every call is wrapped: a failed async child would cancel the rest.
                val libraries = async { runCatching { api.libraries() } }
                val scopes = async { runCatching { api.smartScopes() }.getOrDefault(emptyList()) }
                val collections = async { runCatching { api.collections() }.getOrDefault(emptyList()) }
                val me = async { runCatching { api.me() }.getOrNull() }
                val counts = async { runCatching { api.browseCounts() }.getOrNull() }
                val libs = libraries.await().getOrThrow()
                val user = me.await()?.also { container.auth.setUser(it) } ?: container.auth.user.value

                val entries = mutableListOf<DrawerEntry>()
                entries += dashboardEntry()
                entries += DrawerEntry.Item("all", nameRes = R.string.nav_all_books, icon = "LibraryBig", fallbackIcon = "LibraryBig")
                entries += downloadedEntry()
                if (user?.can(AuthState.BOOK_REQUEST_ACCESS) == true) {
                    val open = runCatching { api.requestSummary().mine }.getOrNull()
                    entries += DrawerEntry.Action(DrawerAction.Requests, open)
                }

                // The reading tracker (ARCHITECTURE.md, "Tracking").
                entries += trackingEntries()

                // Browse, as in the web sidebar.
                val browse = counts.await()
                entries += DrawerEntry.Separator
                entries += DrawerEntry.Item(BookSource.AUTHORS, nameRes = R.string.nav_authors, count = browse?.authors, icon = "Users", fallbackIcon = "Users")
                entries += DrawerEntry.Item(BookSource.SERIES, nameRes = R.string.nav_series, count = browse?.series, icon = "Library", fallbackIcon = "Library")

                // Each keeps the icon it was given in the web app; podcasts aren't for this app.
                val bookLibraries = libs.filter { it.type != "podcasts" }.sortedBy { it.displayOrder }
                if (bookLibraries.isNotEmpty()) {
                    entries += DrawerEntry.Header(R.string.nav_libraries)
                    bookLibraries.forEach {
                        entries += DrawerEntry.Item("library:${it.id}", name = it.name, count = it.bookCount, icon = it.icon, fallbackIcon = "BookCopy")
                    }
                }
                val bookScopes = scopes.await().filter { it.mediaType != "podcasts" }.sortedBy { it.displayOrder }
                if (bookScopes.isNotEmpty()) {
                    entries += DrawerEntry.Header(R.string.nav_smart_scopes)
                    bookScopes.forEach {
                        entries += DrawerEntry.Item("scope:${it.id}", name = it.name, count = it.bookCount, icon = it.icon, fallbackIcon = "Aperture")
                    }
                }
                val bookCollections = collections.await().filter { it.mediaType != "podcasts" }.sortedBy { it.displayOrder }
                if (bookCollections.isNotEmpty()) {
                    entries += DrawerEntry.Header(R.string.nav_collections)
                    bookCollections.forEach {
                        entries += DrawerEntry.Item("collection:${it.id}", name = it.name, count = it.bookCount, icon = it.icon, fallbackIcon = "FolderOpen")
                    }
                }

                drawer.value = DrawerUiState(
                    username = container.session.username.orEmpty(),
                    server = container.session.serverUrl.orEmpty(),
                    entries = entries,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Signed out meanwhile (the refresh token was rejected): the shell goes to Login.
                if (container.auth.status.value is AuthState.Status.SignedOut) return@launch
                drawer.update {
                    it.copy(
                        // The calendar keeps the months it has seen, so it is offered offline too.
                        entries = listOf(dashboardEntry(), downloadedEntry()) + trackingEntries() + DrawerEntry.Action(DrawerAction.Retry),
                        failed = true,
                        failureMessage = e.message ?: e.javaClass.simpleName,
                    )
                }
            }
        }
    }

    /**
     * Book requests closed: the drawer's count of open requests may have changed. A failure keeps
     * the count it had; a drawer without the Requests row (no permission, or lists failed) is left
     * alone.
     */
    fun refreshRequestCount() {
        if (drawer.value.entries.none { it.isRequests() }) return
        viewModelScope.launch {
            val open = runCatching { container.api.requestSummary().mine }.getOrNull() ?: return@launch
            drawer.update { d ->
                d.copy(entries = d.entries.map { if (it is DrawerEntry.Action && it.isRequests()) it.copy(count = open) else it })
            }
        }
    }

    private fun DrawerEntry.isRequests() = this is DrawerEntry.Action && action == DrawerAction.Requests

    /** Signs out here and on the server (best effort); the shell goes to Login. */
    fun signOut() {
        container.appScope.launch { container.signOut() }
    }

    private fun dashboardEntry() =
        DrawerEntry.Item(BookSource.DASHBOARD, nameRes = R.string.nav_dashboard, icon = "LayoutDashboard", fallbackIcon = "LayoutDashboard")

    /** The reading tracker (ARCHITECTURE.md, "Tracking"). */
    private fun trackingEntries(): List<DrawerEntry> = listOf(
        DrawerEntry.Header(R.string.nav_tracking),
        DrawerEntry.Item(CALENDAR_KEY, nameRes = R.string.nav_calendar, icon = "CalendarDays", fallbackIcon = "CalendarDays"),
        DrawerEntry.Item(HISTORY_KEY, nameRes = R.string.history_title, icon = "RotateCcwClock", fallbackIcon = "CalendarRange"),
        DrawerEntry.Item(STATISTICS_KEY, nameRes = R.string.stats_title, icon = "ChartColumn", fallbackIcon = "ChartColumn"),
        DrawerEntry.Item(ACHIEVEMENTS_KEY, nameRes = R.string.achievements_title, icon = "Trophy", fallbackIcon = "Trophy"),
        DrawerEntry.Item(NOTES_KEY, nameRes = R.string.notes_title, icon = "NotebookPen", fallbackIcon = "NotebookPen"),
    )

    private fun downloadedEntry() =
        DrawerEntry.Item(BookSource.DOWNLOADED, nameRes = R.string.nav_downloaded, icon = "HardDriveDownload", fallbackIcon = "HardDriveDownload")

    private fun DrawerUiState.withDownloaded(count: Int?) = copy(
        entries = entries.map { if (it is DrawerEntry.Item && it.key == BookSource.DOWNLOADED) it.copy(count = count) else it },
    )
}
