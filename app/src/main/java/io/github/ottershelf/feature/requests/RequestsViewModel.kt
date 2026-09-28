package io.github.ottershelf.feature.requests

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.AvailabilityItem
import io.github.ottershelf.core.model.BookRequestAvailability
import io.github.ottershelf.core.model.BookRequestItem
import io.github.ottershelf.core.model.CreateBookRequest
import io.github.ottershelf.core.model.MetadataCandidate
import io.github.ottershelf.core.model.MetadataSource
import io.github.ottershelf.core.model.RequestStatus
import io.github.ottershelf.core.model.WorkKey
import io.github.ottershelf.core.network.ApiException
import io.github.ottershelf.core.session.AuthState
import io.github.ottershelf.feature.book.BookPreview
import io.github.ottershelf.feature.book.TitleHint

enum class RequestsTab { Search, Mine }

/**
 * Book requests opened for one book (Route.RequestBook, from the ISBN scanner): the fields filled
 * in and the search run, with [isbn] too, so each provider answers with that edition first.
 */
data class RequestPrefill(val title: String?, val author: String?, val isbn: String?)

/**
 * One book among the search results: the candidates every provider returned for the same work
 * (title + first author, as the server folds requests), shown by the most complete one ([best]).
 * [requested] is set once this session has requested or joined it (the request's status).
 */
data class RequestWork(
    val key: String,
    val best: MetadataCandidate,
    val members: List<MetadataCandidate>,
    val year: Int?,
    val availability: BookRequestAvailability? = null,
    val busy: Boolean = false,
    val requested: String? = null,
) {
    val cover: String? get() = best.coverUrl ?: members.firstNotNullOfOrNull { it.coverUrl }
    val isbn13: String? get() = best.isbn13 ?: members.firstNotNullOfOrNull { it.isbn13 }
}

/** The line above the search results. */
sealed interface SearchStatus {
    data object Hint : SearchStatus
    data object NeedInput : SearchStatus
    data class Searching(val found: Int) : SearchStatus
    /** 0: no matches. */
    data class Found(val count: Int) : SearchStatus
    data class Failed(val message: String?) : SearchStatus
}

/** Where a request is filed: a library, or (null id) the server's default. */
data class DestinationOption(val libraryId: Long?, val name: String?)

sealed interface RequestsError {
    data object Forbidden : RequestsError
    data class Failed(val message: String?) : RequestsError
}

enum class RequestAction { Cancel, Dismiss, Restore, Leave }

/** One-off messages (the Nexus toasts), shown as snackbars. */
sealed interface RequestsMessage {
    data class Requested(val title: String, val library: String?) : RequestsMessage
    data class Joined(val library: String?) : RequestsMessage
    data object LibraryForbidden : RequestsMessage
    data class RequestFailed(val message: String?) : RequestsMessage
    data class ActionFailed(val action: RequestAction, val title: String, val message: String?) : RequestsMessage
}

data class RequestsUiState(
    val tab: RequestsTab = RequestsTab.Search,
    /** Whether the account may request books at all (`book_request_access`). */
    val allowed: Boolean = true,
    val meId: Long? = null,
    // Request a book
    val title: String = "",
    val author: String = "",
    /** A scanned book's ISBN, searched with the title and author until the user edits either. */
    val isbn: String? = null,
    val searching: Boolean = false,
    val searchStatus: SearchStatus = SearchStatus.Hint,
    val works: List<RequestWork> = emptyList(),
    val providerLabels: Map<String, String> = emptyMap(),
    val destinations: List<DestinationOption> = listOf(DestinationOption(null, null)),
    val serverDefaultName: String? = null,
    val destinationId: Long? = null,
    // My requests
    val requests: List<BookRequestItem> = emptyList(),
    val requestsLoaded: Boolean = false,
    val requestsRefreshing: Boolean = false,
    val requestsError: RequestsError? = null,
    val showDismissed: Boolean = false,
    /** The request whose detail sheet is open. */
    val detailId: Long? = null,
    /** The request whose "Cancel this request?" is being asked. */
    val confirmCancelId: Long? = null,
) {
    val detail: BookRequestItem? get() = detailId?.let { id -> requests.firstOrNull { it.id == id } }
    val confirmCancel: BookRequestItem? get() = confirmCancelId?.let { id -> requests.firstOrNull { it.id == id } }
}

/**
 * The web "Book Requests" page for a normal user, as the Nexus RequestsActivity has it: request a
 * book through the server's metadata search, and follow my requests. Polling stands in for the web
 * app's socket: every 5 s while My requests is showing and something is still moving.
 */
class RequestsViewModel(
    private val container: AppContainer,
    private val saved: SavedStateHandle,
    prefill: RequestPrefill? = null,
) : ViewModel() {

    private val api get() = container.api
    private val session get() = container.session
    private var me: AuthUser? = container.auth.user.value

    private val _state = MutableStateFlow(
        RequestsUiState(
            tab = saved.get<String>(KEY_TAB)?.let { name -> RequestsTab.entries.firstOrNull { it.name == name } } ?: RequestsTab.Search,
            allowed = me?.can(AuthState.BOOK_REQUEST_ACCESS) != false,
            meId = me?.id,
            destinationId = session.requestLibraryId,
        ),
    )
    val state: StateFlow<RequestsUiState> = _state.asStateFlow()

    private val _messages = Channel<RequestsMessage>(Channel.BUFFERED)
    val messages: Flow<RequestsMessage> = _messages.receiveAsFlow()

    private var providerOrder: Map<String, Int> = emptyMap()
    private val candidates = ArrayList<MetadataCandidate>()
    private var searchJob: Job? = null
    private var pollJob: Job? = null
    private var resumed = false

    init {
        viewModelScope.launch {
            me = container.refreshUser()
            _state.update { it.copy(meId = me?.id, allowed = me?.can(AuthState.BOOK_REQUEST_ACCESS) != false) }
            loadDestinations()
            attempt { api.metadataProviders() }?.let { list ->
                providerOrder = list.withIndex().associate { it.value.key to it.index }
                _state.update { s -> s.copy(providerLabels = list.associate { it.key to (it.label ?: it.key) }) }
            }
        }
        if (_state.value.tab == RequestsTab.Mine) loadRequests()
        if (prefill != null) {
            _state.update {
                it.copy(title = prefill.title.orEmpty(), author = prefill.author.orEmpty(), isbn = prefill.isbn?.takeIf(String::isNotBlank))
            }
            search()
        }
    }

    // --- Request destination -------------------------------------------------------------------
    //
    // BookOrbit files every unpicked request into one instance-wide default library. Each user can
    // choose their own here; it's remembered per server+user and sent as targetLibraryId on every
    // request, which the server accepts for any library the requester can access.

    private suspend fun loadDestinations() {
        val default = attempt { api.requestDestinations()[MEDIA_KIND]?.libraryName }
        val libraries = attempt { api.libraries() }
            ?.filter { it.type != "podcasts" }
            ?.sortedBy { it.displayOrder }
        val saved = session.requestLibraryId
        // Library gone or no longer shared (only when the list actually came: offline isn't gone).
        val valid = saved == null || libraries == null || libraries.any { it.id == saved }
        if (!valid) session.requestLibraryId = null
        _state.update { s ->
            s.copy(
                serverDefaultName = default,
                destinations = listOf(DestinationOption(null, null)) +
                    (libraries?.map { DestinationOption(it.id, it.name) } ?: s.destinations.drop(1)),
                destinationId = if (valid) saved else null,
            )
        }
    }

    fun selectDestination(libraryId: Long?) {
        session.requestLibraryId = libraryId
        _state.update { it.copy(destinationId = libraryId) }
    }

    // --- Tabs and polling ------------------------------------------------------------------------

    fun selectTab(tab: RequestsTab) {
        saved[KEY_TAB] = tab.name
        _state.update { it.copy(tab = tab) }
        if (tab == RequestsTab.Mine) loadRequests()
        updatePolling()
    }

    /** The screen is resumed (polling runs only then, as the Nexus onResume/onPause). */
    fun setResumed(value: Boolean) {
        resumed = value
        updatePolling()
    }

    private fun updatePolling() {
        if (!resumed || _state.value.tab != RequestsTab.Mine) {
            pollJob?.cancel()
            pollJob = null
            return
        }
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                delay(POLL_MS)
                if (_state.value.requests.any { RequestStatus.isActive(it.status) }) refreshRequests(showSpinner = false)
            }
        }
    }

    // --- Request a book --------------------------------------------------------------------------

    // Another title or author is another book: the scanned ISBN no longer applies.
    fun setTitle(value: String) = _state.update { it.copy(title = value, isbn = if (value == it.title) it.isbn else null) }

    fun setAuthor(value: String) = _state.update { it.copy(author = value, isbn = if (value == it.author) it.isbn else null) }

    fun search() {
        val title = _state.value.title.trim()
        val author = _state.value.author.trim()
        val isbn = _state.value.isbn
        if (title.isEmpty() && author.isEmpty() && isbn == null) {
            _state.update { it.copy(searchStatus = SearchStatus.NeedInput) }
            return
        }
        searchJob?.cancel()
        candidates.clear()
        _state.update { it.copy(works = emptyList(), searching = true, searchStatus = SearchStatus.Searching(0)) }

        searchJob = viewModelScope.launch {
            var lastRender = 0L
            try {
                api.searchMetadata(title, author, MEDIA_KIND, isbn).collect { candidate ->
                    candidates += candidate
                    // Results stream in per provider; regroup at most ~3 times a second.
                    val now = SystemClock.uptimeMillis()
                    if (now - lastRender > RENDER_MS) {
                        lastRender = now
                        val works = renderWorks()
                        _state.update { it.copy(searchStatus = SearchStatus.Searching(works.size)) }
                    }
                }
                val works = renderWorks()
                _state.update { it.copy(searchStatus = SearchStatus.Found(works.size)) }
                checkAvailability()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                renderWorks()
                _state.update { it.copy(searchStatus = SearchStatus.Failed(e.message)) }
            } finally {
                _state.update { it.copy(searching = false) }
            }
        }
    }

    private fun renderWorks(): List<RequestWork> {
        val works = groupWorks(candidates, providerOrder, _state.value.works.associateBy { it.key })
        _state.update { it.copy(works = works) }
        return works
    }

    private suspend fun checkAvailability() {
        val batch = _state.value.works.take(50)
        if (batch.isEmpty()) return
        val items = batch.map { w ->
            AvailabilityItem(
                title = w.best.shownTitle.orEmpty().take(500),
                mediaKind = MEDIA_KIND,
                author = w.best.authors?.firstOrNull()?.take(255),
                isbn13 = w.isbn13?.take(20),
                providerKey = w.best.provider.take(50),
                providerId = w.best.providerId?.take(255),
            )
        }
        val result = attempt { api.requestAvailability(items) } ?: return
        val byKey = batch.zip(result).associate { (w, a) -> w.key to a }
        _state.update { s -> s.copy(works = s.works.map { w -> byKey[w.key]?.let { w.copy(availability = it) } ?: w }) }
    }

    private fun updateWork(key: String, change: (RequestWork) -> RequestWork) =
        _state.update { s -> s.copy(works = s.works.map { if (it.key == key) change(it) else it }) }

    fun submit(work: RequestWork) {
        updateWork(work.key) { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val best = work.best
                // The user's own destination if they picked one; otherwise the server default, which
                // auto-approved/self-served requests must name explicitly.
                val target = session.requestLibraryId
                    ?: if (me?.can("book_request_auto_approve") == true || me?.can("book_request_self_fulfill") == true) {
                        attempt { api.requestDestinations()[MEDIA_KIND]?.libraryId }
                    } else null
                val isbn13 = work.isbn13
                val body = CreateBookRequest(
                    title = best.shownTitle.orEmpty().take(500),
                    mediaKind = MEDIA_KIND,
                    subtitle = best.subtitle?.take(500),
                    authors = best.authors?.take(50)?.map { it.take(255) },
                    seriesName = best.seriesName?.take(500),
                    seriesIndex = best.seriesIndex?.toInt()?.coerceAtLeast(0),
                    isbn13 = isbn13?.take(20),
                    isbn10 = if (isbn13 == null) best.isbn10?.take(20) else null,
                    publishedYear = work.year,
                    coverUrl = work.cover?.takeIf { it.startsWith("http") && it.length <= 2048 },
                    providerKey = best.provider.take(50),
                    providerId = best.providerId?.take(255),
                    metadataSources = metadataSources(work, _state.value.providerLabels),
                    preferredFormats = listOf("epub"),
                    targetLibraryId = target,
                )
                val result = api.submitRequest(body)
                updateWork(work.key) { it.copy(requested = result.request.status) }
                val library = result.request.targetLibraryName
                // Joining keeps the original request's destination; the server ignores ours.
                _messages.trySend(
                    if (result.subscribed) RequestsMessage.Joined(library) else RequestsMessage.Requested(body.title, library),
                )
                if (_state.value.requestsLoaded) refreshRequests(showSpinner = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.errorCode == "SUBMIT_LIBRARY_FORBIDDEN" && session.requestLibraryId != null) {
                    selectDestination(null)
                    _messages.trySend(RequestsMessage.LibraryForbidden)
                } else {
                    _messages.trySend(RequestsMessage.RequestFailed(e.message))
                }
            } catch (e: Exception) {
                _messages.trySend(RequestsMessage.RequestFailed(e.message))
            } finally {
                updateWork(work.key) { it.copy(busy = false) }
            }
        }
    }

    // --- My requests -----------------------------------------------------------------------------

    fun loadRequests() {
        viewModelScope.launch { refreshRequests(showSpinner = true) }
    }

    /** Pulled to refresh. */
    fun refresh() {
        _state.update { it.copy(requestsRefreshing = true) }
        viewModelScope.launch { refreshRequests(showSpinner = false) }
    }

    fun setShowDismissed(show: Boolean) {
        _state.update { it.copy(showDismissed = show) }
        loadRequests()
    }

    private suspend fun refreshRequests(showSpinner: Boolean) {
        if (showSpinner && _state.value.requests.isEmpty()) _state.update { it.copy(requestsRefreshing = true) }
        try {
            val page = api.myRequests(page = 1, includeDismissed = _state.value.showDismissed)
            _state.update { s ->
                // A request that left the list (dismissed, say) takes its open sheet or question with it.
                fun listed(id: Long?) = id?.takeIf { page.items.any { item -> item.id == it } }
                s.copy(
                    requests = page.items,
                    requestsLoaded = true,
                    requestsError = null,
                    detailId = listed(s.detailId),
                    confirmCancelId = listed(s.confirmCancelId),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_state.value.requests.isEmpty()) {
                val error = if ((e as? ApiException)?.code == 403) RequestsError.Forbidden else RequestsError.Failed(e.message)
                _state.update { it.copy(requestsError = error) }
            }
        } finally {
            _state.update { it.copy(requestsRefreshing = false) }
        }
    }

    fun openDetail(id: Long?) = _state.update { it.copy(detailId = id) }

    /**
     * Before opening the library's copy of a book: its page shows the request's (or the search
     * result's) title and authors at once while it loads. Only as a hint: the provider's title
     * may differ from the library's.
     */
    fun opening(bookId: Long) {
        val state = _state.value
        val hint = state.requests.firstOrNull { it.matchedBookId == bookId }?.let { TitleHint(it.title, it.authors) }
            ?: state.works.firstOrNull { it.availability?.ownedBookId == bookId }
                ?.let { TitleHint(it.best.shownTitle, it.best.authors.orEmpty()) }
            ?: return
        BookPreview.putHint(session.accountKey(), bookId, hint)
    }

    fun askCancel(item: BookRequestItem) = _state.update { it.copy(confirmCancelId = item.id) }

    fun confirmCancel(confirmed: Boolean) {
        val item = _state.value.confirmCancel
        _state.update { it.copy(confirmCancelId = null) }
        if (confirmed && item != null) act(item, RequestAction.Cancel) { api.cancelRequest(item.id) }
    }

    fun dismiss(item: BookRequestItem) {
        if (item.dismissed) act(item, RequestAction.Restore) { api.restoreRequest(item.id) }
        else act(item, RequestAction.Dismiss) { api.dismissRequest(item.id) }
    }

    fun leave(item: BookRequestItem) = act(item, RequestAction.Leave) { api.leaveRequest(item.id) }

    private fun act(item: BookRequestItem, action: RequestAction, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                refreshRequests(showSpinner = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.trySend(RequestsMessage.ActionFailed(action, item.title, e.message))
            }
        }
    }

    companion object {
        /** Only e-books matter on this app. */
        const val MEDIA_KIND = "ebook"
        private const val KEY_TAB = "tab"
        private const val POLL_MS = 5_000L
        private const val RENDER_MS = 300L
    }
}

/**
 * Collapses [candidates] into works the way the server folds requests ([WorkKey]: title + first
 * author), each shown by its most complete candidate (ties go to the provider listed first), and
 * keeps what [previous] knew of each (availability, requested).
 */
internal fun groupWorks(
    candidates: List<MetadataCandidate>,
    providerOrder: Map<String, Int>,
    previous: Map<String, RequestWork>,
): List<RequestWork> {
    val grouped = LinkedHashMap<String, MutableList<MetadataCandidate>>()
    for (c in candidates) {
        val title = c.shownTitle ?: continue
        grouped.getOrPut(WorkKey.of(title, c.authors?.firstOrNull(), RequestsViewModel.MEDIA_KIND)) { ArrayList() } += c
    }
    return grouped.map { (key, members) ->
        val best = members.sortedWith(
            compareByDescending<MetadataCandidate> { completeness(it) }
                .thenBy { providerOrder[it.provider] ?: Int.MAX_VALUE },
        ).first()
        val old = previous[key]
        RequestWork(
            key = key,
            best = best,
            members = members.toList(),
            year = members.mapNotNull { it.publishedYear }.minOrNull(),
            availability = old?.availability,
            busy = old?.busy ?: false,
            requested = old?.requested,
        )
    }
}

/**
 * The candidates behind [work] as a request's `metadataSources`. The server requires a label for
 * each: the provider's name, or its key when the names didn't load (as the web does).
 */
internal fun metadataSources(work: RequestWork, providerLabels: Map<String, String>): List<MetadataSource>? =
    work.members
        .filter { it.providerId != null }
        .distinctBy { it.provider to it.providerId }
        .take(20)
        .map {
            MetadataSource(
                providerKey = it.provider.take(50),
                providerId = it.providerId!!.take(255),
                providerLabel = (providerLabels[it.provider] ?: it.provider).take(100),
                isbn10 = it.isbn10?.take(20),
                isbn13 = it.isbn13?.take(20),
            )
        }
        .ifEmpty { null }

private fun completeness(c: MetadataCandidate): Int =
    listOf(c.coverUrl, c.isbn13 ?: c.isbn10, c.publishedYear, c.subtitle, c.seriesName, c.authors?.firstOrNull())
        .count { it != null }

/** [block]'s result, or null if it failed; unlike runCatching, being cancelled still cancels. */
private inline fun <T> attempt(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
