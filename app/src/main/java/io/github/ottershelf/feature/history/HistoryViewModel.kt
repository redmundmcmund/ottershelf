package io.github.ottershelf.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.tracking.ReadingAttempt
import io.github.ottershelf.feature.book.BookPreview
import io.github.ottershelf.feature.book.TitleHint
import io.github.ottershelf.feature.calendar.serverZone
import java.io.File
import java.time.LocalDate

data class HistoryUiState(
    val today: LocalDate,
    /** Every reading known (cached at first, then fetched); null before anything is known. */
    val entries: List<HistoryEntry>? = null,
    val filter: HistoryFilter = HistoryFilter.ALL,
    /** [entries] through [filter], as cards. */
    val groups: List<HistoryGroup> = emptyList(),
    val loading: Boolean = false,
    /** Pull to refresh in progress. */
    val refreshing: Boolean = false,
    /** The last load failed (what shows is the saved copy, if any). */
    val failed: Boolean = false,
    /** Books whose readings couldn't be fetched in the last load (left out, or shown as saved). */
    val failedBooks: Int = 0,
)

/**
 * The reading history: every reading of every book, a peek beside the Calendar.
 *
 * BookOrbit has no list of reading attempts across books (only `books/:id/reading-attempts`), so:
 * the books with a reading status other than unread and want to read come from `books/query`
 * (200 a page), then each book's readings, six at a time ([HistoryLoader]). Unread and want to read
 * are left out on purpose: setting a book back to one of them says the user hasn't read it (the server
 * then closes an open reading as abandoned, so a book opened by mistake would otherwise show as
 * given up). The books and their readings are kept on the device (`cacheDir/history/<account>.json`,
 * [HistoryCache]), so the list shows at once and offline; later loads fetch the readings only of
 * books whose status row changed since ([HistoryCard.fingerprint]), and a pull fetches them all.
 *
 * Reloads after any tracking write here ([trackingVersion]) and anything the readers sent
 * ([readingChanges]): a moment later while on screen (one reload for a write, which moves both),
 * else when shown again. Shown again with nothing changed here, it reloads only on a new day, after
 * a failed load, or once the last load is [RESUME_REFRESH_MS] old: readings from the Kobo or the
 * web show by then, or on a pull. Back from a book page, nothing is asked.
 */
class HistoryViewModel internal constructor(
    remote: HistoryRemote,
    private val cache: HistoryCache,
    private val account: String,
    trackingVersion: Flow<Int>,
    readingChanges: Flow<Int>,
    /** Today where the server splits days (users.settings.timezone, else UTC). */
    private val todayNow: () -> LocalDate,
    private val thumbnail: (bookId: Long, version: String?) -> Any?,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    constructor(container: AppContainer, cacheDir: File) : this(
        remote = ApiHistoryRemote(container.api, container.tracking),
        cache = HistoryCache(historyCacheFile(cacheDir, container.session.accountKey())),
        account = container.session.accountKey(),
        trackingVersion = container.tracking.version,
        readingChanges = container.readingChanges.changes,
        todayNow = { LocalDate.now(serverZone(container.auth.user.value)) },
        thumbnail = { id, version -> container.api.thumbnailUrl(id, version) },
    )

    private val loader = HistoryLoader(remote)
    private var books: Map<Long, CachedBook> = emptyMap()
    // Readings saved here (the dates sheet): shown at once, over any load that started before them.
    private val saved = SavedReadings()
    private var job: Job? = null
    private var again = false
    private var stale = false
    private var settleJob: Job? = null
    private var resumed = false
    private var loadedAt = 0L
    // The saved copy has been read and the first load started (a resume before that waits for it).
    private var started = false

    private val _state = MutableStateFlow(HistoryUiState(today = today(), loading = true))
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // The saved copy first: it shows at once, and only books changed since are fetched.
            cache.read()?.let { saved -> show(saved.books.associateBy { it.card.id }) }
            started = true
            load(force = false)
        }
        viewModelScope.launch {
            merge(trackingVersion.drop(1), readingChanges.drop(1)).collect {
                stale = true
                if (resumed && started) loadSoon()
            }
        }
    }

    fun setFilter(filter: HistoryFilter) {
        if (filter == _state.value.filter) return
        _state.update { it.copy(filter = filter, groups = HistoryMath.groups(it.entries.orEmpty(), filter)) }
    }

    /** Pull to refresh: every book's readings are fetched again. */
    fun refresh() = load(force = true, pulled = true)

    fun retry() = load(force = false)

    /** Shown again (back from a book or the reader, or the app resumed). */
    fun onResume() {
        resumed = true
        if (!started) return
        val newDay = today() != _state.value.today
        if (stale || newDay || _state.value.failed || clock() - loadedAt > RESUME_REFRESH_MS) load(force = false)
    }

    fun onPause() {
        resumed = false
    }

    /** The thumbnail for a row (versioned by the card's time), or null for the placeholder. */
    fun cover(entry: HistoryEntry): Any? = if (entry.hasCover) thumbnail(entry.bookId, entry.coverVersion) else null

    /** Before opening a book: its page shows the title at once. */
    fun opening(entry: HistoryEntry) {
        runCatching { BookPreview.putHint(account, entry.bookId, TitleHint(entry.title, entry.authors)) }
    }

    /** The dates sheet saved [reading]: it shows at once (the reload the write started confirms it). */
    fun readingSaved(reading: SavedReading) {
        saved.add(reading)
        show(saved.over(books))
    }

    /**
     * A change while on screen: loaded after [CHANGE_SETTLE_MS], so the tracker's version and
     * ReadingChanges, which a write moves together, make one load.
     */
    private fun loadSoon() {
        settleJob?.cancel()
        settleJob = viewModelScope.launch {
            delay(CHANGE_SETTLE_MS)
            if (resumed && stale) load(force = false)
        }
    }

    private fun load(force: Boolean, pulled: Boolean = false) {
        if (job?.isActive == true && !force) {
            again = true
            return
        }
        job?.cancel()
        _state.update { it.copy(loading = true, refreshing = pulled, today = today()) }
        job = viewModelScope.launch {
            var forced = force
            do {
                again = false
                // What changed until now is in this pass (a change during it asks for another).
                stale = false
                try {
                    val since = saved.generation()
                    val result = loader.load(books, forced)
                    loadedAt = clock()
                    saved.loaded(since)
                    show(saved.over(result.books.associateBy { it.card.id }), failedBooks = result.failed)
                    cache.write(HistoryCacheFile(result.books))
                    _state.update { it.copy(failed = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _state.update { it.copy(failed = true) }
                }
                forced = false
            } while (again)
            _state.update { it.copy(loading = false, refreshing = false) }
        }
    }

    private fun show(byId: Map<Long, CachedBook>, failedBooks: Int = _state.value.failedBooks) {
        books = byId
        val today = today()
        val entries = byId.values.flatMap { HistoryMath.entries(it.card, it.attempts, today) }
        _state.update {
            it.copy(today = today, entries = entries, groups = HistoryMath.groups(entries, it.filter), failedBooks = failedBooks)
        }
    }

    private fun today(): LocalDate = todayNow()

    private companion object {
        /** Shown again with nothing changed here, it loads again once its last load is this old. */
        const val RESUME_REFRESH_MS = 10 * 60_000L
        /** A tracking write moves the tracker's version and ReadingChanges together: one load for both. */
        const val CHANGE_SETTLE_MS = 300L
    }
}

/**
 * Fetches the books with a reading status and their readings: the book pages one after another
 * (at most [MAX_PAGES]), then the readings of the books [HistoryMath.toFetch] picks, [parallel] at
 * a time. A book whose readings fail keeps the ones saved (under its old fingerprint, so the next
 * load tries again), or is left out; either way it counts in [Result.failed]. A failed book page
 * fails the load.
 */
internal class HistoryLoader(private val remote: HistoryRemote, private val parallel: Int = 6) {

    class Result(val books: List<CachedBook>, val failed: Int)

    suspend fun load(saved: Map<Long, CachedBook>, force: Boolean): Result = coroutineScope {
        val cards = LinkedHashMap<Long, HistoryCard>()
        var page = 0
        while (page < MAX_PAGES) {
            val result = remote.books(page)
            result.items.forEach { cards.putIfAbsent(it.id, it) }
            if (result.items.isEmpty() || (page + 1) * HistoryRemote.PAGE_SIZE >= result.total) break
            page++
        }
        val gate = Semaphore(parallel)
        val fetched: Map<Long, List<ReadingAttempt>?> = HistoryMath.toFetch(cards.values.toList(), saved, force).map { card ->
            async {
                card.id to gate.withPermit {
                    try {
                        remote.attempts(card.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }.awaitAll().toMap()
        var failed = 0
        val books = cards.values.mapNotNull { card ->
            if (card.id !in fetched) return@mapNotNull saved[card.id]?.copy(card = card)
            val attempts = fetched[card.id]
            if (attempts != null) return@mapNotNull CachedBook(card, card.fingerprint, attempts)
            failed++
            saved[card.id]?.copy(card = card)
        }
        if (fetched.isNotEmpty() && fetched.values.all { it == null }) error("No book's readings could be loaded")
        Result(books, failed)
    }

    companion object {
        /** 2,000 books: well past any reading history, and a bound on a runaway total. */
        const val MAX_PAGES = 10
    }
}

/** The history's device copy for [account]: `cacheDir/history/<account>.json`. */
internal fun historyCacheFile(cacheDir: File, account: String): File =
    File(cacheDir, "history/${account.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

/** The history on the device: one JSON file per account in the cache dir. */
internal class HistoryCache(private val file: File, private val io: CoroutineDispatcher = Dispatchers.IO) {

    suspend fun read(): HistoryCacheFile? = withContext(io) {
        try {
            file.takeIf { it.isFile }?.readText()?.let { ApiJson.decodeFromString(HistoryCacheFile.serializer(), it) }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun write(content: HistoryCacheFile) = withContext(io) {
        try {
            file.parentFile?.mkdirs()
            // A name of its own: the Calendar and Day screens write this file too (ReadingLog).
            val tmp = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
            tmp.writeText(ApiJson.encodeToString(HistoryCacheFile.serializer(), content))
            try {
                // Over the old copy in one step (File.renameTo won't replace a file everywhere).
                java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        } catch (_: Exception) {
            // A cache: the next visit fetches again.
        }
    }
}
