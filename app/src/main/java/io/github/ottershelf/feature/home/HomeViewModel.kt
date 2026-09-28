package io.github.ottershelf.feature.home

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.download.DownloadedBook
import io.github.ottershelf.core.format.BookFormats
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.CurrentlyReadingBook
import io.github.ottershelf.core.model.Library
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.model.ReadingStreak
import io.github.ottershelf.core.model.SmartScope
import io.github.ottershelf.core.model.UserSettings
import io.github.ottershelf.core.settings.AppSettings
import io.github.ottershelf.core.sync.ReadingChanges
import io.github.ottershelf.core.sync.edited
import io.github.ottershelf.core.sync.newEdits
import io.github.ottershelf.core.sync.withDetails
import io.github.ottershelf.feature.book.BookPreview
import io.github.ottershelf.feature.book.TitleHint
import io.github.ottershelf.feature.home.model.CustomiseDraft
import io.github.ottershelf.feature.home.model.DEFAULT_SHELVES
import io.github.ottershelf.feature.home.model.DEFAULT_WIDGETS
import io.github.ottershelf.feature.home.model.DashboardLayout
import io.github.ottershelf.feature.home.model.HighlightData
import io.github.ottershelf.feature.home.model.LongWaitData
import io.github.ottershelf.feature.home.model.NeglectedGemsData
import io.github.ottershelf.feature.home.model.ShelfConfig
import io.github.ottershelf.feature.home.model.ShelfType
import io.github.ottershelf.feature.home.model.WidgetData
import io.github.ottershelf.feature.home.model.WidgetType
import io.github.ottershelf.feature.home.model.arranged
import io.github.ottershelf.feature.home.model.cardOrder
import io.github.ottershelf.feature.home.model.reorderWidgets
import io.github.ottershelf.feature.home.model.shelfKey
import io.github.ottershelf.feature.home.model.widgetKey
import io.github.ottershelf.feature.home.model.widgetOfKey
import io.github.ottershelf.feature.home.model.dashboardConfigWith
import io.github.ottershelf.feature.home.model.normalizeShelves
import io.github.ottershelf.feature.home.model.pruneScopeShelves
import io.github.ottershelf.feature.home.model.toRequest

/** A Currently Reading row and the cover to load for it (a [DailyCover], or a test's fake model). */
data class ReadingRow(val book: CurrentlyReadingBook, val cover: Any?)

/** A shelf cover and the model to load for it. */
data class ShelfItem(val book: BookCard, val cover: Any?)

/**
 * A cover with no version to go by (the widgets only say whether there is one): cached for a day
 * under [key], as the Nexus app did.
 */
data class DailyCover(val url: String, val key: String)

/** A shelf as shown: its books (null until they first arrive) and whether it failed with none showing. */
data class ShelfUi(val config: ShelfConfig, val books: List<ShelfItem>?, val failed: Boolean)

/** A card on the Dashboard: a widget, or a shelf with its books. [key] is its key in the saved order. */
sealed interface HomeCard {
    val key: String

    data class Widget(val type: WidgetType) : HomeCard {
        override val key: String get() = widgetKey(type)
    }

    data class Shelf(val shelf: ShelfUi) : HomeCard {
        override val key: String get() = shelfKey(shelf.config.id)
    }
}

/**
 * The Customise sheet: the draft, the libraries and Smart Scopes to choose from (null while loading,
 * or when they couldn't be read: [librariesFailed]).
 */
data class CustomiseState(
    val draft: CustomiseDraft,
    /** Every library the user can see, podcasts too (a scope saved on the web may hold one); sorted. */
    val libraries: List<Library>? = null,
    val smartScopes: List<SmartScope>? = null,
    val saving: Boolean = false,
    /** The server's refusal (or "" for no reason given) of the last Save. */
    val error: String? = null,
    val librariesFailed: Boolean = false,
    /** The layout the draft started from: Save writes only the parts that differ from it. */
    val baseline: DashboardLayout = DashboardLayout(draft.widgets, draft.libraryIds),
) {
    /** The libraries offered as checkboxes (the app shows no podcasts). */
    val bookLibraries: List<Library>? get() = libraries?.filter { it.type != "podcasts" }

    val widgetsChanged: Boolean get() = draft.widgets != baseline.widgets

    /** Compared as sets: ticking a library off and on again only reorders the list. */
    val scopeChanged: Boolean get() = draft.libraryIds?.toSet() != baseline.libraryIds?.toSet()

    /** The server's [layout] (read after the sheet opened) taken into each part not changed here yet. */
    fun rebasedOn(layout: DashboardLayout): CustomiseState {
        var draft = draft
        var base = baseline
        if (!widgetsChanged) {
            draft = draft.copy(widgets = layout.widgets)
            base = base.copy(widgets = layout.widgets)
        }
        if (!scopeChanged) {
            draft = draft.copy(libraryIds = layout.libraryIds)
            base = base.copy(libraryIds = layout.libraryIds)
        }
        return copy(draft = draft, baseline = base)
    }
}

/**
 * The Dashboard. Each part is null (or missing from its map) until it first arrives and then stays
 * until something newer does; a part in `failed...` failed with nothing of it showing (its card
 * then offers to retry). [refreshing] drives the pull-to-refresh spinner.
 */
data class HomeUiState(
    /** Which widgets, in which order, and the library scope (`dashboardConfig`, on the server). */
    val layout: DashboardLayout = DashboardLayout(DEFAULT_WIDGETS, null),
    val reading: List<ReadingRow>? = null,
    val streak: ReadingStreak? = null,
    /** The other ten widgets' answers. */
    val widgetData: Map<WidgetType, WidgetData> = emptyMap(),
    val failedWidgets: Set<WidgetType> = emptySet(),
    /** The shelves (kept on the device); empty until read. */
    val shelfConfigs: List<ShelfConfig> = emptyList(),
    /** Books by [ShelfConfig.key]. */
    val shelfBooks: Map<String, List<ShelfItem>> = emptyMap(),
    val failedShelves: Set<String> = emptySet(),
    /** Neglected Gems queued (want to read) from here. */
    val queued: Set<Long> = emptySet(),
    /** Neglected Gems whose status "Add to queue" must not change (the user is reading one, or has read it). */
    val unqueueable: Set<Long> = emptySet(),
    val refreshing: Boolean = false,
    /** The cards' order as saved in the app settings key (card keys); empty until the user arranges them. */
    val order: List<String> = emptyList(),
    /** Arranging the cards (a long press on a card's title; Done ends it). */
    val editing: Boolean = false,
) {
    val widgets: List<WidgetType> get() = layout.enabledWidgets

    /** Every card's key, hidden ones too, in the order they sit (model/DashboardOrder.kt). */
    val cardOrder: List<String>
        get() = cardOrder(order, layout.widgets.map { widgetKey(it.type) }, shelfConfigs.map { shelfKey(it.id) })

    /** The cards on show, in order. */
    val cards: List<HomeCard>
        get() {
            val shown = layout.widgets.distinctBy { it.type }.filter { it.enabled }.associateBy { widgetKey(it.type) }
            val shelves = shelves.associateBy { shelfKey(it.config.id) }
            return cardOrder.mapNotNull { key -> shown[key]?.let { HomeCard.Widget(it.type) } ?: shelves[key]?.let { HomeCard.Shelf(it) } }
        }

    /** Every shelf, hidden ones too, in the order the Dashboard has them. */
    val orderedShelves: List<ShelfConfig>
        get() {
            val byKey = shelfConfigs.associateBy { shelfKey(it.id) }
            return cardOrder.mapNotNull { byKey[it] }
        }
    val readingFailed: Boolean get() = reading == null && WidgetType.CURRENTLY_READING in failedWidgets
    val streakFailed: Boolean get() = streak == null && WidgetType.READING_STREAK in failedWidgets

    val shelves: List<ShelfUi>
        get() = shelfConfigs.filter { it.enabled }.map { shelf ->
            ShelfUi(shelf, shelfBooks[shelf.key] ?: emptyList<ShelfItem>().takeIf { !shelf.requestable }, shelf.key in failedShelves)
        }

    /** Whether Neglected Gems may offer "Add to queue" (not for a book on Currently Reading). */
    fun canQueue(bookId: Long): Boolean = bookId !in unqueueable && reading?.none { it.book.bookId == bookId } != false

    fun hasData(type: WidgetType): Boolean = when (type) {
        WidgetType.CURRENTLY_READING -> reading != null
        WidgetType.READING_STREAK -> streak != null
        else -> type in widgetData
    }

    val hasAny: Boolean get() = reading != null || streak != null || widgetData.isNotEmpty() || shelfBooks.isNotEmpty()
}

/**
 * The Nexus DashboardFragment's loading and freshness rules, moved out of the screen, for the web
 * dashboard's widgets and shelves:
 *
 * - Two requests fill it (the web's two batches): every enabled widget in one
 *   `dashboard/widgets/batch`, and every enabled shelf in one `dashboard/scrollers/batch`. Each
 *   shows as it arrives; neither waits for, or is cancelled by a failure of, the other. A widget or
 *   shelf the server failed shows its own retry; what's showing stays until something newer arrives.
 * - It asks again when this device changed what it shows ([ReadingChanges.changes]: a position or
 *   session sent, a status set), after waiting [SETTLE_MS] for the pair that usually comes
 *   together, and when the screen comes back into view more than [STALE_MS] after the last
 *   complete load (five minutes: what this device changes reloads at once through
 *   [ReadingChanges.changes] anyway, so this is for changes made elsewhere, on the web or the Kobo).
 * - The server keeps its widgets for 2 minutes even after a position arrives, so they are dropped
 *   (`POST dashboard/refresh`) first, but only when something changed here since they last were
 *   (or on pull to refresh).
 * - A load that has already sent its requests is never thrown away (it may be the first thing to
 *   show); a change or a pull during it loads once more afterwards.
 *
 * Which widgets show is the account's `dashboardConfig` (followed from [user], and read again from
 * `auth/me` on a pull or a return after [STALE_MS], so a change made on the web shows); the shelves
 * are kept on the device ([shelfPrefs]). The Customise sheet ([customise]) writes both.
 *
 * The screen reports [setVisible] (the Nexus collector ran while STARTED) and collects [loads] for
 * the shell's `AppNavigator.dashboardLoaded`.
 */
class HomeViewModel(
    private val remote: DashboardRemote,
    private val changes: ReadingChanges,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val day: () -> Long = { System.currentTimeMillis() / DAY_MS },
    private val account: () -> String = { "" },
    private val shelfPrefs: ShelfPrefs = ShelfPrefs.InMemory(),
    private val user: StateFlow<AuthUser?> = MutableStateFlow(null),
    private val updateUser: (AuthUser) -> Unit = {},
    /** A status set from here (Neglected Gems): announced as the book page's picker announces it. */
    private val onStatusSet: (bookId: Long, status: String) -> Unit = { _, _ -> },
    /** The copy of a book downloaded on this phone (core.download.Downloads.get; reads the disk). */
    private val downloaded: (bookId: Long) -> DownloadedBook? = { null },
    /** The app's settings key (core.settings.AppSettingsRepository): the cards' order is kept there. */
    private val appSettings: StateFlow<AppSettings> = MutableStateFlow(AppSettings()),
    private val updateAppSettings: ((AppSettings) -> AppSettings) -> Unit = {},
    /** Where the arranged widgets are written, so leaving the Dashboard doesn't cancel it (the app's scope). */
    private val writeScope: CoroutineScope? = null,
) : ViewModel() {

    constructor(container: AppContainer) : this(
        remote = ApiDashboardRemote(container.api),
        changes = container.readingChanges,
        account = container.session::accountKey,
        shelfPrefs = DataStoreShelfPrefs(container.settings, container.session::accountKey),
        user = container.auth.user,
        updateUser = container.auth::setUser,
        onStatusSet = { bookId, status ->
            container.readingChanges.overrideStatus(bookId, status)
            container.tracking.changedElsewhere(bookId)
        },
        downloaded = container.downloads::get,
        appSettings = container.appSettings.settings,
        updateAppSettings = container.appSettings::update,
        writeScope = container.appScope,
    )

    private val _state = MutableStateFlow(
        HomeUiState(layout = DashboardLayout.of(user.value?.settings?.dashboardConfig), order = appSettings.value.dashboardOrder),
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    // Its own flow, so a tap in the sheet doesn't recompose the dashboard behind it.
    private val _customise = MutableStateFlow<CustomiseState?>(null)
    /** The Customise sheet, while it is open. */
    val customise: StateFlow<CustomiseState?> = _customise.asStateFlow()
    /** Customise saves written to [user]: an `auth/me` sent before one mustn't undo it. */
    private var userWrites = 0

    private val _loads = Channel<Boolean>(Channel.UNLIMITED)
    /** Each finished load: whether anything from the server is showing (the Nexus hasContent). */
    val loads: Flow<Boolean> = _loads.receiveAsFlow()

    private var watchJob: Job? = null
    private var loadJob: Job? = null
    /** Whether the load under way has sent its requests (rather than still waiting to). */
    private var sending = false
    /** [ReadingChanges.changes] as of the load under way. */
    private var askedChanges = 0
    /** ... and as of the last load that brought everything, fresh; -1 before one. */
    private var loadedChanges = -1
    /** When the last load that brought everything finished ([clock]); 0 before one. */
    private var loadedAt = 0L
    /** Pulled to refresh while a load was already sending: once more, clearing, when it's done. */
    private var refreshQueued = false
    /** What's to show changed while a load was already sending: once more when it's done. */
    private var reloadQueued = false
    private var shelvesRead = false
    private var scopesChecked = false
    /** Neglected Gems whose status is being read before queueing (a second tap waits for it). */
    private val queueing = mutableSetOf<Long>()

    init {
        // The web (or this app's Customise) changed which widgets show: new ones need their data,
        // and another library scope other data. Before the first load, that load takes it; during
        // one, reload() asks once more after it (it read the widgets when it sent).
        viewModelScope.launch {
            user.map { it?.settings?.dashboardConfig }.distinctUntilChanged().collect { config ->
                val layout = DashboardLayout.of(config)
                val before = _state.value.layout
                if (layout == before) return@collect
                _state.update { it.copy(layout = layout) }
                val scopeChanged = layout.libraryIds?.toSet() != before.libraryIds?.toSet()
                if (loadJob != null && (scopeChanged || layout.enabledWidgets.any { !_state.value.hasData(it) })) reload()
            }
        }
        // The cards' order, as the account has it (another phone may have arranged them).
        viewModelScope.launch {
            appSettings.map { it.dashboardOrder }.distinctUntilChanged().collect { order ->
                _state.update { it.copy(order = order) }
            }
        }
        // A book edited on this phone (feature.bookedit) shows as edited on the shelves and in
        // Currently Reading at once; the reload the edit starts (changes) brings the server's word.
        viewModelScope.launch {
            changes.newEdits().collect { edits -> _state.update { it.withEdited(edits) } }
        }
    }

    private fun HomeUiState.withEdited(byId: Map<Long, BookDetail>): HomeUiState {
        return copy(
            shelfBooks = shelfBooks.mapValues { (_, items) ->
                items.map { item ->
                    val card = byId[item.book.id]?.let { item.book.withDetails(it) } ?: item.book
                    if (card == item.book) item else ShelfItem(card, remote.shelfCover(card))
                }
            },
            reading = reading?.map { row ->
                val book = byId[row.book.bookId]?.let { row.book.withDetails(it) } ?: row.book
                if (book == row.book) row else ReadingRow(book, remote.readingCover(book, day()))
            },
        )
    }

    /** The screen is showing (true) or not: while it is, every change is followed. */
    fun setVisible(visible: Boolean) {
        // Leaving while arranging keeps what the user arranged.
        if (!visible) finishEditing()
        watchJob?.cancel()
        // A StateFlow emits straight away on every return to the screen, then on every change.
        watchJob = if (visible) viewModelScope.launch { changes.changes.collect { onChanges(it) } } else null
    }

    /**
     * The file (id and format) a play button opens for the server's pick [fileId] in [format]: that
     * one, or the CBZ downloaded for a CBR or CB7 comic (BookFormats.readsKeptCopyInstead), as the
     * book page's Read does, so both keep the user's place in the same file and it opens offline.
     */
    suspend fun fileToRead(bookId: Long, fileId: Long, format: String?): Pair<Long, String?> {
        if (BookFormats.canKeepOffline(format) || !BookFormats.isOpenable(format)) return fileId to format
        val copy = withContext(Dispatchers.IO) { runCatching { downloaded(bookId) }.getOrNull() }
        return if (copy != null && copy.fileId != fileId && BookFormats.readsKeptCopyInstead(format, copy.format)) copy.fileId to copy.format
        else fileId to format
    }

    /** A widget's cover (highlight, gem, long wait), cached for a day. */
    fun cover(bookId: Long, hasCover: Boolean): Any? = remote.bookCover(bookId, hasCover, day())

    /**
     * Before opening a book: its page shows the shelf card (title, authors, the cached thumbnail),
     * or else the title (and authors) a widget had, at once while it loads.
     */
    fun opening(bookId: Long) {
        val state = _state.value
        val card = state.shelfBooks.values.firstNotNullOfOrNull { books -> books.firstOrNull { it.book.id == bookId }?.book }
        if (card != null) {
            BookPreview.put(account(), card)
            return
        }
        val row = state.reading?.firstOrNull { it.book.bookId == bookId }?.book
        val hint = when {
            row != null -> TitleHint(row.title, row.authors)
            else -> state.widgetData.values.firstNotNullOfOrNull { data ->
                when (data) {
                    is LongWaitData -> data.title.takeIf { data.bookId == bookId }
                    is HighlightData -> data.bookTitle.takeIf { data.bookId == bookId }
                    is NeglectedGemsData -> data.gems.firstOrNull { it.bookId == bookId }?.title
                    else -> null
                }
            }?.let { TitleHint(it, emptyList()) }
        } ?: return
        BookPreview.putHint(account(), bookId, hint)
    }

    /**
     * Neglected Gems' "Add to queue": want to read, but only from unread, no status or abandoned.
     * The gem doesn't say its status (the server leaves out only read and skimmed books), and
     * want to read would end a reading under way as abandoned, so it is read first.
     */
    fun queue(bookId: Long) {
        val s = _state.value
        if (bookId in s.queued || !s.canQueue(bookId) || bookId in queueing) return
        queueing += bookId
        viewModelScope.launch {
            try {
                val status = attempt { remote.readStatus(bookId) ?: ReadStatus.UNREAD.value } ?: return@launch
                when (ReadStatus.of(status)) {
                    ReadStatus.WANT_TO_READ -> _state.update { it.copy(queued = it.queued + bookId) }
                    null, ReadStatus.UNREAD, ReadStatus.ABANDONED -> {
                        val info = attempt { remote.setStatus(bookId, ReadStatus.WANT_TO_READ.value) } ?: return@launch
                        _state.update { it.copy(queued = it.queued + bookId) }
                        onStatusSet(bookId, info.status ?: ReadStatus.WANT_TO_READ.value)
                    }
                    else -> _state.update { it.copy(unqueueable = it.unqueueable + bookId) }
                }
            } finally {
                queueing -= bookId
            }
        }
    }

    /** Pulled to refresh: drop the server's copy of the widgets even if nothing changed here. */
    fun refresh() {
        refreshUser()
        _state.update { it.copy(refreshing = true) }
        if (loadJob?.isActive == true && sending) {
            refreshQueued = true
            return
        }
        load(changes.changes.value, settle = false, clear = true)
    }

    /** From a card's "couldn't load": the spinner instead of the messages meanwhile. */
    fun retry() {
        _state.update { it.copy(failedWidgets = emptySet(), failedShelves = emptySet(), refreshing = true) }
        // One already sending is the retry; it shows the messages again if it fails too.
        if (loadJob?.isActive == true && sending) return
        load(changes.changes.value, settle = false)
    }

    // --- arranging the cards ---------------------------------------------------------------------

    /** The saved order and the widgets' order when arranging began: put back if the account refuses. */
    private var arrangeStart: Pair<List<String>, List<WidgetType>>? = null

    private val _orderFailures = Channel<Unit>(Channel.CONFLATED)

    /** The account refused the widgets' new order (or couldn't be reached): the order was put back. */
    val orderFailures: Flow<Unit> = _orderFailures.receiveAsFlow()

    /** A long press on a card's title: the cards can be dragged into another order until [finishEditing]. */
    fun startEditing() {
        val s = _state.value
        if (s.editing || _customise.value != null) return
        arrangeStart = s.order to s.layout.widgets.map { it.type }
        _state.update { it.copy(editing = true) }
    }

    /**
     * The user moved cards: [shown] is the cards on show, in their new order (hidden ones keep their
     * places). The whole order goes to the app settings key at once (it is kept on the device and
     * sent shortly); the widgets' order among themselves goes to the account when the user is done.
     */
    fun arrange(shown: List<String>) {
        val s = _state.value
        val full = s.cardOrder
        val next = arranged(full, shown.filter { it in full })
        if (next == full) return
        val widgets = reorderWidgets(s.layout.widgets, next.mapNotNull(::widgetOfKey))
        _state.update { it.copy(order = next, layout = it.layout.copy(widgets = widgets)) }
        updateAppSettings { it.copy(dashboardOrder = next) }
    }

    /**
     * Done (the toolbar, Back, or leaving the Dashboard): if the widgets' order changed, the
     * account's whole `dashboardConfig` is read again and written back with only that order changed
     * (the web shows its widgets in it). If that fails, the arrangement is put back as it was.
     */
    fun finishEditing() {
        if (!_state.value.editing) return
        _state.update { it.copy(editing = false) }
        val (startOrder, startWidgets) = arrangeStart ?: return
        arrangeStart = null
        val widgets = _state.value.layout.widgets.map { it.type }
        if (widgets == startWidgets) return
        (writeScope ?: viewModelScope).launch {
            try {
                saveWidgetOrder(widgets)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(order = startOrder, layout = it.layout.copy(widgets = reorderWidgets(it.layout.widgets, startWidgets))) }
                updateAppSettings { it.copy(dashboardOrder = startOrder) }
                _orderFailures.trySend(Unit)
            }
        }
    }

    /**
     * The account's widgets put in [order] (their flags, ids and the web's own widgets as the server
     * has them now), in the whole `dashboardConfig` read from `auth/me`; nothing is sent if the
     * server already has that order.
     */
    private suspend fun saveWidgetOrder(order: List<WidgetType>) {
        val fresh = remote.me()
        val config = fresh.settings?.dashboardConfig
        val current = DashboardLayout.of(config).widgets
        val widgets = reorderWidgets(current, order)
        val stored = if (widgets == current) {
            config
        } else {
            val next = dashboardConfigWith(config, widgets = widgets, libraryIds = null, setLibraries = false)
            val saved = remote.patchSettings(buildJsonObject { put("dashboardConfig", next) })
            saved?.get("dashboardConfig") as? JsonObject ?: next
        }
        _state.update { it.copy(layout = DashboardLayout.of(stored)) }
        userWrites++
        updateUser(fresh.copy(settings = (fresh.settings ?: UserSettings()).copy(dashboardConfig = stored)))
    }

    // --- Customise ----------------------------------------------------------------------------

    /**
     * Opens the sheet on what the Dashboard shows (the shelves in the Dashboard's order), then reads
     * the account again (`auth/me`), so a change made on the web since replaces each part not yet
     * changed here.
     */
    fun openCustomise() {
        finishEditing()
        val s = _state.value
        val draft = CustomiseDraft(s.layout.widgets, s.orderedShelves.ifEmpty { DEFAULT_SHELVES }, s.layout.libraryIds)
        _customise.value = CustomiseState(draft)
        viewModelScope.launch {
            coroutineScope {
                launch { loadLibraries() }
                launch {
                    val scopes = attempt { remote.smartScopes() }?.filter { it.mediaType != "podcasts" }?.sortedBy { it.displayOrder }
                    _customise.update { it?.copy(smartScopes = scopes.orEmpty()) }
                }
                launch {
                    val fresh = freshUser() ?: return@launch
                    val layout = DashboardLayout.of(fresh.settings?.dashboardConfig)
                    _customise.update { c -> if (c == null || c.saving) c else c.rebasedOn(layout) }
                }
            }
        }
    }

    /** From the scope's "couldn't load": ask for the libraries again. */
    fun retryLibraries() {
        if (_customise.value?.librariesFailed != true) return
        _customise.update { it?.copy(librariesFailed = false) }
        viewModelScope.launch { loadLibraries() }
    }

    /**
     * All of them, podcasts too: a scope saved on the web may hold one, and it must still count
     * (and be kept). Failing leaves the list unknown (null), so the scope is kept as it is rather
     * than read as "none of these".
     */
    private suspend fun loadLibraries() {
        val libraries = attempt { remote.libraries() }?.sortedBy { it.displayOrder }
        _customise.update { it?.copy(libraries = libraries, librariesFailed = libraries == null) }
    }

    fun editCustomise(transform: (CustomiseDraft) -> CustomiseDraft) {
        _customise.update { c -> if (c == null || c.saving) c else c.copy(draft = transform(c.draft), error = null) }
    }

    fun closeCustomise() {
        if (_customise.value?.saving == true) return
        _customise.value = null
    }

    /**
     * Save: the shelves on the device; the widgets and the library scope, each only if the user changed
     * it in the sheet, into the account's `dashboardConfig`: read whole from `auth/me` first and
     * written whole, with everything else in it (readingGoal, and a part the user left alone, which
     * keeps whatever the web set) as the server has it. The cards' whole order (the app settings
     * key) keeps where the widgets and shelves sit among each other, each list in the sheet's new
     * order; a shelf added goes at the end. Then the Dashboard loads again.
     */
    fun saveCustomise() {
        val c = _customise.value ?: return
        if (c.saving) return
        if (!c.draft.validLibraries(c.libraries)) {
            editCustomise { it.copy(libraryScopeOpen = true) }
            return
        }
        _customise.value = c.copy(saving = true, error = null)
        viewModelScope.launch {
            val shelves = normalizeShelves(c.draft.shelves)
            val before = _state.value.cardOrder
            try {
                if (c.widgetsChanged || c.scopeChanged) {
                    val fresh = remote.me()
                    val next = dashboardConfigWith(
                        fresh.settings?.dashboardConfig,
                        widgets = c.draft.widgets.takeIf { c.widgetsChanged },
                        libraryIds = c.draft.selectedLibraries(c.libraries),
                        setLibraries = c.scopeChanged,
                    )
                    val saved = remote.patchSettings(buildJsonObject { put("dashboardConfig", next) })
                    val stored = saved?.get("dashboardConfig") as? JsonObject ?: next
                    _state.update { it.copy(layout = DashboardLayout.of(stored)) }
                    userWrites++
                    updateUser(fresh.copy(settings = (fresh.settings ?: UserSettings()).copy(dashboardConfig = stored)))
                }
                shelfPrefs.save(shelves)
                shelvesRead = true
                _state.update { it.copy(shelfConfigs = shelves) }
                val widgetKeys = _state.value.layout.widgets.map { widgetKey(it.type) }
                val after = cardOrder(before, widgetKeys, shelves.map { shelfKey(it.id) }, shelvesFollowList = true)
                if (after != before) {
                    _state.update { it.copy(order = after) }
                    updateAppSettings { it.copy(dashboardOrder = after) }
                }
                _customise.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _customise.update { it?.copy(saving = false, error = e.message.orEmpty()) }
                return@launch
            }
            reload()
        }
    }

    // --- the account ----------------------------------------------------------------------------

    /**
     * The account as the server has it now (`auth/me`), passed on to the app (the layout follows
     * it); null if it couldn't be read, or a Customise save wrote the account meanwhile (this may
     * have been read before it).
     */
    private suspend fun freshUser(): AuthUser? {
        val writes = userWrites
        val fresh = attempt { remote.me() } ?: return null
        if (writes != userWrites) return null
        updateUser(fresh)
        return fresh
    }

    /** Reads the account again in the background: the web may have changed the dashboard. */
    private fun refreshUser() {
        viewModelScope.launch { freshUser() }
    }

    // --- loading ------------------------------------------------------------------------------

    /** What's to show changed (not the data): load once more, after one under way. */
    private fun reload() {
        if (loadJob?.isActive == true && sending) {
            reloadQueued = true
            return
        }
        load(changes.changes.value, settle = false)
    }

    private fun onChanges(now: Int) {
        if (loadJob?.isActive == true) {
            // Still waiting to send: wait again, for this one too. Already sent: it isn't thrown
            // away (it may be the first thing to show); it looks again when it's done.
            if (!sending && now != askedChanges) load(now, settle = true)
            return
        }
        // Changed while this was showing (or what's showing may predate a change: the server's copy
        // couldn't be dropped); or the first time, or back after a while.
        val changed = loadedAt != 0L && now != loadedChanges
        val stale = loadedAt == 0L || clock() - loadedAt > STALE_MS
        // Back after a while: the layout too may have changed on the web (the shell read the
        // account at start, so not the first time).
        if (stale && loadJob != null) refreshUser()
        if (changed || stale) load(now, settle = changed)
    }

    /** The shelves as saved on the device, read once. */
    private suspend fun shelfConfigs(): List<ShelfConfig> {
        if (!shelvesRead) {
            val saved = shelfPrefs.load()
            shelvesRead = true
            _state.update { it.copy(shelfConfigs = saved) }
        }
        return _state.value.shelfConfigs
    }

    /**
     * Once, when there are Smart Scope shelves: those of deleted scopes go, and the others take
     * their scope's current name (the web's `pruneDeletedSmartScopeScrollers`).
     */
    private suspend fun checkScopeShelves(shelves: List<ShelfConfig>) {
        if (scopesChecked || shelves.none { it.shelfType == ShelfType.SMART_SCOPE }) return
        val scopes = attempt { remote.smartScopes() } ?: return
        scopesChecked = true
        val pruned = pruneScopeShelves(shelves, scopes.associate { it.id to it.name })
        if (pruned == shelves) return
        shelfPrefs.save(pruned)
        _state.update { it.copy(shelfConfigs = pruned) }
    }

    /**
     * Asks for everything. [asked]: [ReadingChanges.changes] as the caller saw it. [settle]: a
     * change just came, and they come in pairs (the position, then the session): wait a moment to
     * take both. [clear]: drop the server's copy of the widgets even if nothing changed here.
     */
    private fun load(asked: Int, settle: Boolean, clear: Boolean = false) {
        loadJob?.cancel()
        askedChanges = asked
        sending = false
        if (!_state.value.hasAny) _state.update { it.copy(refreshing = true) }
        loadJob = viewModelScope.launch {
            if (settle) delay(SETTLE_MS)
            sending = true
            reloadQueued = false
            // The server keeps its widgets for 2 minutes, even after a position arrives: drop them
            // if this device has changed something since they last were, or it wouldn't show.
            val clearCache = clear || asked > changes.dashboardClearedFor
            var cleared = !clearCache
            var shelvesDone = false
            var widgetsDone = false
            val shelves = shelfConfigs()
            val widgets = _state.value.widgets
            coroutineScope {
                // Each on its own, so neither waits for (or is cancelled by a failure of) the other.
                launch {
                    shelvesDone = loadShelves(shelves)
                    checkScopeShelves(shelves)
                }
                launch {
                    val results = attempt {
                        if (clearCache) {
                            cleared = attempt { remote.refreshDashboard() } != null
                            if (cleared) changes.dashboardCleared(asked)
                        }
                        if (widgets.isEmpty()) emptyMap() else remote.widgets(widgets)
                    }
                    val today = day()
                    _state.update { it.withWidgets(widgets, results, today) }
                    widgetsDone = results != null && widgets.all { results[it] is WidgetResult.Loaded }
                }
            }

            if (shelvesDone && widgetsDone) {
                loadedAt = clock()
                // Not if the server's copy couldn't be dropped: what came may be from before the change.
                if (cleared) loadedChanges = asked
            }
            _state.update { it.copy(refreshing = refreshQueued) }
            _loads.trySend(_state.value.hasAny)

            // Pulled meanwhile, or changed while this was under way: once more.
            val now = changes.changes.value
            when {
                refreshQueued -> {
                    refreshQueued = false
                    load(now, settle = false, clear = true)
                }
                now != asked -> load(now, settle = true)
                reloadQueued -> load(now, settle = false)
            }
        }
    }

    /** The enabled shelves in one batch; whether every one came. */
    private suspend fun loadShelves(shelves: List<ShelfConfig>): Boolean {
        val requested = shelves.filter { it.enabled && it.requestable }
        if (requested.isEmpty()) return true
        val editMark = changes.editMark
        val result = attempt { remote.shelves(requested.map { it.toRequest() }) }
        // Edited here while the answer was on its way: it may be from before. (Edits made before
        // asking are in it, or someone changed the book again since: the server's word either way.)
        val edits = changes.editedSince(editMark)
        _state.update { s ->
            val books = s.shelfBooks.toMutableMap()
            val failed = s.failedShelves.toMutableSet()
            for (shelf in requested) {
                val got = result?.get(shelf.id)
                if (got != null) {
                    books[shelf.key] = got.map { it.edited(edits) }.map { ShelfItem(it, remote.shelfCover(it)) }
                    failed -= shelf.key
                } else if (shelf.key !in books) {
                    failed += shelf.key
                }
            }
            s.copy(shelfBooks = books, failedShelves = failed)
        }
        return result != null && requested.all { result[it.id] != null }
    }

    private fun HomeUiState.withWidgets(types: List<WidgetType>, results: Map<WidgetType, WidgetResult>?, today: Long): HomeUiState {
        var reading = reading
        var streak = streak
        val data = widgetData.toMutableMap()
        val failed = failedWidgets.toMutableSet()
        for (type in types) {
            val result = results?.get(type)
            if (result is WidgetResult.Loaded) {
                failed -= type
                when (val d = result.data) {
                    is WidgetData.Reading -> reading = d.value.books.map { ReadingRow(it, remote.readingCover(it, today)) }
                    is WidgetData.Streak -> streak = d.value
                    else -> data[type] = d
                }
            } else if (!hasData(type)) {
                failed += type
            }
        }
        return copy(reading = reading, streak = streak, widgetData = data, failedWidgets = failed)
    }

    companion object {
        /**
         * A return to the Dashboard reloads (and reads `auth/me`) only after this long. The server
         * keeps its quick-changing widgets for two minutes and the others for five, so returns
         * closer together would mostly get the same answers back.
         */
        const val STALE_MS = 5 * 60_000L
        const val SETTLE_MS = 300L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

/** [block]'s result, or null if it failed; unlike runCatching, being cancelled still cancels. */
private inline fun <T> attempt(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
