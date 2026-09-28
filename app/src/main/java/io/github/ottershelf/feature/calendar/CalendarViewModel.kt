package io.github.ottershelf.feature.calendar

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.tracking.ActivityCalendar
import io.github.ottershelf.core.tracking.ActivityDayDetail
import java.io.File
import io.github.ottershelf.feature.history.ApiHistoryRemote
import io.github.ottershelf.feature.history.CachedBook
import io.github.ottershelf.feature.history.HistoryCache
import io.github.ottershelf.feature.history.HistoryLoader
import io.github.ottershelf.feature.history.ReadingLog
import io.github.ottershelf.feature.history.SavedReading
import io.github.ottershelf.feature.history.historyCacheFile
import java.time.LocalDate
import java.time.YearMonth

data class CalendarUiState(
    val month: YearMonth,
    val today: LocalDate,
    /** [month]'s data: cached at first, then refreshed; null before anything is known. */
    val data: CalendarMonth? = null,
    val loading: Boolean = false,
    /** Pull to refresh in progress. */
    val refreshing: Boolean = false,
    /** The last refresh failed (what shows is the cached copy, if any). */
    val failed: Boolean = false,
    /** The earliest month with reading (January of the first year the server has), when known. */
    val firstMonth: YearMonth? = null,
    /**
     * Every day's marks (`YYYY-MM-DD`): books started, finished or given up that day, from their
     * readings (History's data), whether or not there was a session.
     */
    val marks: Map<String, List<CalendarMark>> = emptyMap(),
) {
    val canGoBack: Boolean get() = firstMonth == null || month > firstMonth
    val canGoForward: Boolean get() = month < YearMonth.from(today)
}

/**
 * The Book Calendar: a month at a time. The year's `activity-calendar` says which days have
 * reading; only those days' `activity-days` are fetched (in parallel, six at a time), and only
 * when new or changed since the month was cached on the device ([CalendarCache]), or today. A
 * month seen before shows at once from the cache.
 *
 * The marks (a reading started, finished or given up on a day) come from every book's readings
 * ([ReadingLog]: History's device copy, and its loads, which page through every book with a
 * status and fetch the readings only of books whose status changed); a reading saved here
 * ([readingSaved]) shows at once.
 *
 * Everything is loaded again ([reload]): at once after a tracking write on this device
 * ([trackingVersion]); on a pull; and when the screen shows again ([onResume]) only if the readers
 * sent something meanwhile ([readingChanges]: their sessions skip the tracker), the day changed,
 * the last load failed, or it is [RETURN_RELOAD_MS] old (sessions from the Kobo or the web show by
 * then, or on a pull). Back from a book page with nothing changed, nothing is asked.
 */
class CalendarViewModel internal constructor(
    private val activityCalendar: suspend (year: Int) -> ActivityCalendar,
    private val activityDay: suspend (day: LocalDate) -> ActivityDayDetail,
    trackingVersion: Flow<Int>,
    readingChanges: Flow<Int>,
    private val cache: CalendarCache,
    private val log: ReadingLog,
    /** Today where the server splits days (users.settings.timezone, else UTC). */
    private val todayNow: () -> LocalDate,
    private val coverOf: (CalendarBook) -> Any?,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    constructor(container: AppContainer, cacheDir: File) : this(
        activityCalendar = container.tracking::activityCalendar,
        activityDay = container.tracking::activityDay,
        trackingVersion = container.tracking.version,
        readingChanges = container.readingChanges.changes,
        cache = CalendarCache(File(cacheDir, "calendar/${container.session.accountKey()}")),
        log = ReadingLog(
            HistoryLoader(ApiHistoryRemote(container.api, container.tracking)),
            HistoryCache(historyCacheFile(cacheDir, container.session.accountKey())),
        ),
        todayNow = { LocalDate.now(serverZone(container.auth.user.value)) },
        coverOf = { coverModel(container.api, it) },
    )

    private val months = mutableMapOf<YearMonth, CalendarMonth>()
    private val years = mutableMapOf<Int, Pair<Long, ActivityCalendar>>()
    private var job: Job? = null
    private var marksJob: Job? = null
    private var marksAgain = false
    private var writeJob: Job? = null
    /** When the last [reload] (or the first load) started ([clock]). */
    private var loadedAt = clock()
    /** [readingChanges] moves seen, and how many of them the last [reload] covers. */
    private var changes = 0
    private var loadedChanges = 0

    private val _state = MutableStateFlow(today().let { CalendarUiState(month = YearMonth.from(it), today = it) })
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    init {
        show(_state.value.month)
        viewModelScope.launch {
            publish(log.current())
            refreshMarks()
        }
        viewModelScope.launch {
            // A tracking write here (a session logged, a status, a reading's dates): at once. It
            // moves ReadingChanges too, so the reload waits a moment to cover that as well.
            trackingVersion.drop(1).collect {
                writeJob?.cancel()
                writeJob = viewModelScope.launch {
                    delay(CHANGE_SETTLE_MS)
                    writeJob = null
                    reload(clearYears = true)
                }
            }
        }
        viewModelScope.launch {
            // What the readers sent (their sessions), a note synced, a book edited: at the next return.
            readingChanges.drop(1).collect { changes++ }
        }
    }

    /** The dates sheet saved [reading]: its marks show at once (the reload the write started confirms them). */
    fun readingSaved(reading: SavedReading) {
        viewModelScope.launch { publish(log.add(reading)) }
    }

    /** Loads the readings again; asked while a load runs, it runs once more after it (a first load can be long). */
    private fun refreshMarks() {
        if (marksJob?.isActive == true) {
            marksAgain = true
            return
        }
        marksJob = viewModelScope.launch {
            do {
                marksAgain = false
                try {
                    publish(log.refresh())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Offline or a server error: the saved marks stay; the next refresh tries again.
                }
            } while (marksAgain)
        }
    }

    private fun publish(books: Map<Long, CachedBook>) {
        val marks = CalendarMath.marksOf(books.values)
        _state.update { it.copy(marks = marks) }
    }

    fun previous() {
        if (_state.value.canGoBack) show(_state.value.month.minusMonths(1))
    }

    fun next() {
        if (_state.value.canGoForward) show(_state.value.month.plusMonths(1))
    }

    fun thisMonth() = show(YearMonth.from(today()))

    /** Pull to refresh: everything, the year's calendar too. */
    fun refresh() = reload(clearYears = true, pulled = true)

    /**
     * The screen showed again (back from a book or the reader, or the app resumed). Loads again
     * only if the readers sent something since the last load (a session on any day: the year's
     * calendar is asked again too), the day changed (likewise), the last load failed, or it is
     * [RETURN_RELOAD_MS] old (the year's calendar, kept [YEAR_TTL_MS], is asked again then anyway).
     */
    fun onResume() {
        val sent = changes != loadedChanges
        val newDay = today() != _state.value.today
        if (!sent && !newDay && !_state.value.failed && clock() - loadedAt < RETURN_RELOAD_MS) return
        reload(clearYears = sent || newDay)
    }

    /** The thumbnail for a calendar cover (versioned by the server's metadata time), or null for none. */
    fun cover(book: CalendarBook): Any? = coverOf(book)

    /** Loads the month shown and the marks again; [clearYears]: the year's calendar too. */
    private fun reload(clearYears: Boolean, pulled: Boolean = false) {
        // This covers a tracking write's reload still waiting to start.
        writeJob?.cancel()
        writeJob = null
        loadedAt = clock()
        loadedChanges = changes
        if (clearYears) years.clear()
        show(_state.value.month, pulled)
        refreshMarks()
    }

    private fun show(month: YearMonth, pulled: Boolean = false) {
        val today = today()
        _state.update {
            it.copy(month = month, today = today, data = months[month] ?: it.data?.takeIf { d -> d.month == month.toString() },
                loading = true, refreshing = pulled, failed = false)
        }
        job?.cancel()
        job = viewModelScope.launch {
            val cached = months[month] ?: cache.read(month)?.also { months[month] = it }
            if (cached != null) {
                _state.update {
                    if (it.month != month) it
                    else it.copy(data = cached, firstMonth = it.firstMonth ?: cached.availableYears.minOrNull()?.let { y -> YearMonth.of(y, 1) })
                }
            }
            try {
                val fresh = fetch(month, cached, today)
                months[month] = fresh.month
                cache.write(fresh.month)
                _state.update {
                    if (it.month != month) it
                    else it.copy(
                        data = fresh.month,
                        loading = false,
                        refreshing = false,
                        failed = !fresh.complete,
                        firstMonth = fresh.month.availableYears.minOrNull()?.let { y -> YearMonth.of(y, 1) } ?: it.firstMonth,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { if (it.month == month) it.copy(loading = false, refreshing = false, failed = true) else it }
            }
        }
    }

    private class Fetched(val month: CalendarMonth, val complete: Boolean)

    private suspend fun fetch(month: YearMonth, cached: CalendarMonth?, today: LocalDate): Fetched = coroutineScope {
        val calendar = yearCalendar(month.year)
        val active = CalendarMath.activeDays(calendar, month)
        val wanted = CalendarMath.daysToFetch(active, cached, today)
        val limit = Semaphore(PARALLEL_DAYS)
        val fetched = wanted.map { day ->
            async {
                limit.withPermit {
                    try {
                        CalendarMath.dayOf(activityDay(LocalDate.parse(day)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull().associateBy { it.day }
        if (wanted.isNotEmpty() && fetched.isEmpty()) error("No day could be loaded")
        val merged = CalendarMath.merge(month, active, cached, fetched, calendar.availableYears, clock())
        Fetched(merged, complete = fetched.size == wanted.size)
    }

    /** The year's calendar, reused for [YEAR_TTL_MS] (moving between months of one year). */
    private suspend fun yearCalendar(year: Int): ActivityCalendar {
        val now = clock()
        years[year]?.let { (at, calendar) -> if (now - at < YEAR_TTL_MS) return calendar }
        return activityCalendar(year).also { years[year] = now to it }
    }

    private fun today(): LocalDate = todayNow()

    private companion object {
        const val PARALLEL_DAYS = 6
        /** A year's `activity-calendar`, kept while the user moves between its months. */
        const val YEAR_TTL_MS = 5 * 60_000L
        /** A return loads everything again once the last load is this old (sessions from elsewhere). */
        const val RETURN_RELOAD_MS = 10 * 60_000L
        /** A tracking write moves the tracker's version and ReadingChanges together: one reload for both. */
        const val CHANGE_SETTLE_MS = 300L
    }
}

/** Day and Reading goals, shown again, refresh if their last load is older than this (or the day changed). */
internal const val RESUME_REFRESH_MS = 30_000L

/** A calendar cover's thumbnail: versioned when the server says when its metadata changed. */
internal fun coverModel(api: Api, book: CalendarBook): Any? = when {
    !book.hasCover -> null
    book.coverVersion != null -> api.thumbnailUrl(book.bookId, book.coverVersion)
    else -> api.unversionedThumbnailUrl(book.bookId)
}

/** Months of the calendar on the device (one JSON file per month, per account, in the cache dir). */
internal class CalendarCache(private val dir: File, private val io: CoroutineDispatcher = Dispatchers.IO) {

    private fun file(month: YearMonth) = File(dir, "$month.json")

    suspend fun read(month: YearMonth): CalendarMonth? = withContext(io) {
        try {
            file(month).takeIf { it.isFile }?.readText()?.let { ApiJson.decodeFromString(CalendarMonth.serializer(), it) }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun write(month: CalendarMonth) = withContext(io) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "${month.month}.tmp")
            tmp.writeText(ApiJson.encodeToString(CalendarMonth.serializer(), month))
            if (!tmp.renameTo(File(dir, "${month.month}.json"))) tmp.delete()
        } catch (_: Exception) {
            // A cache: the next visit fetches again.
        }
    }
}
