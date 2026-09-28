package io.github.ottershelf.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.tracking.ActivityDayDetail
import io.github.ottershelf.core.tracking.PageMath
import io.github.ottershelf.core.util.IsoTime
import io.github.ottershelf.feature.book.BookPreview
import io.github.ottershelf.feature.book.TitleHint
import io.github.ottershelf.feature.history.ApiHistoryRemote
import io.github.ottershelf.feature.history.CachedBook
import io.github.ottershelf.feature.history.HistoryCache
import io.github.ottershelf.feature.history.HistoryLoader
import io.github.ottershelf.feature.history.ReadingLog
import io.github.ottershelf.feature.history.SavedReading
import io.github.ottershelf.feature.history.historyCacheFile
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** A session on the Day screen. */
data class DaySessionRow(
    val id: Long,
    val bookId: Long,
    val title: String?,
    val cover: Any?,
    val startMs: Long?,
    val endMs: Long?,
    /** The part of the session on this day. */
    val seconds: Long,
    /** Percentage points gained; null when unknown. */
    val percent: Double?,
    /** Pages read, from [percent] and the page total of the user's edition; null without either. */
    val pages: Int? = null,
    val listening: Boolean = false,
)

/** A book whose reading started, was finished or given up on the Day screen's day. */
data class DayReadingRow(val bookId: Long, val title: String?, val cover: Any?, val kind: MarkKind)

data class DayUiState(
    val date: LocalDate,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val totalSeconds: Long = 0,
    val sessions: List<DaySessionRow> = emptyList(),
    /** The zone the server split the day in (times are shown in it). */
    val zone: ZoneId = ZoneId.systemDefault(),
    /** The daily goal in minutes (the app's settings key); null for none. */
    val goalMinutes: Int? = null,
    /** Readings started, finished or given up that day (from the books' readings, as the calendar marks them). */
    val readings: List<DayReadingRow> = emptyList(),
) {
    val books: Int get() = sessions.map { it.bookId }.distinct().size
}

/**
 * Day(date): `activity-days/:day` (every session that day, split at the server's midnight), with
 * pages read worked out from each book's page total (the user's edition's total, else the server's page count,
 * fetched once per book); and the readings that started or ended that day, from History's device
 * copy ([ReadingLog]: the Calendar keeps it loaded), with the ones saved here on top.
 */
class DayViewModel(private val container: AppContainer, private val date: LocalDate, cacheDir: File? = null) : ViewModel() {

    private val tracking = container.tracking
    private val api = container.api
    private val pageTotals = mutableMapOf<Long, Int?>()
    private val log = cacheDir?.let {
        ReadingLog(
            HistoryLoader(ApiHistoryRemote(container.api, container.tracking)),
            HistoryCache(historyCacheFile(it, container.session.accountKey())),
        )
    }
    private var job: Job? = null
    private var loadedAt = 0L

    private val _state = MutableStateFlow(DayUiState(date, goalMinutes = container.appSettings.settings.value.dailyGoalMinutes))
    val state: StateFlow<DayUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            container.appSettings.settings.collect { s -> _state.update { it.copy(goalMinutes = s.dailyGoalMinutes?.takeIf { m -> m > 0 }) } }
        }
        viewModelScope.launch { tracking.version.drop(1).collect { load() } }
        log?.let { l -> viewModelScope.launch { publish(l.current()) } }
    }

    fun retry() = load()

    /** The screen showed again (back from a book or the reader): refreshes unless it just did. */
    fun onResume() {
        if (System.currentTimeMillis() - loadedAt >= RESUME_REFRESH_MS) {
            load()
            log?.let { l -> viewModelScope.launch { publish(l.reread()) } }
        }
    }

    /** Remembers the title for the book page (it shows while the page loads). */
    fun opening(row: DaySessionRow) {
        runCatching { BookPreview.putHint(container.session.accountKey(), row.bookId, TitleHint(row.title)) }
    }

    fun opening(row: DayReadingRow) {
        runCatching { BookPreview.putHint(container.session.accountKey(), row.bookId, TitleHint(row.title)) }
    }

    /** The dates sheet saved [reading]: this day's readings show it at once. */
    fun readingSaved(reading: SavedReading) {
        val l = log ?: return
        viewModelScope.launch { publish(l.add(reading)) }
    }

    private fun publish(books: Map<Long, CachedBook>) {
        val marks = CalendarMath.marksOf(books.values)[date.toString()].orEmpty()
        _state.update { s -> s.copy(readings = marks.map { DayReadingRow(it.book.bookId, it.book.title, coverModel(api, it.book), it.kind) }) }
    }

    private fun load() {
        loadedAt = System.currentTimeMillis()
        job?.cancel()
        _state.update { it.copy(loading = it.sessions.isEmpty(), failed = false) }
        job = viewModelScope.launch {
            try {
                val detail = tracking.activityDay(date)
                val rows = rowsOf(detail)
                _state.update {
                    it.copy(
                        loading = false,
                        totalSeconds = detail.totals?.totalSeconds ?: rows.sumOf { r -> r.seconds },
                        sessions = rows.withPages(),
                        zone = detail.timezone?.let { z -> runCatching { ZoneId.of(z) }.getOrNull() } ?: serverZone(container.auth.user.value),
                    )
                }
                val totals = totalsFor(rows.map { it.bookId }.distinct())
                if (totals) _state.update { it.copy(sessions = it.sessions.withPages()) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    private fun rowsOf(detail: ActivityDayDetail): List<DaySessionRow> = detail.sessions.map { s ->
        DaySessionRow(
            id = s.id,
            bookId = s.bookId,
            title = s.bookTitle,
            cover = coverModel(api, CalendarBook(s.bookId, s.bookTitle, s.hasCover, s.coverVersion)),
            startMs = IsoTime.parse(s.startedAt),
            endMs = IsoTime.parse(s.endedAt),
            seconds = s.durationOnDaySeconds,
            percent = s.progressDelta,
            listening = s.mediaBucket == "listening",
        )
    }

    private fun List<DaySessionRow>.withPages(): List<DaySessionRow> {
        val settings = container.appSettings.settings.value
        return map { r -> r.copy(pages = PageMath.pagesOf(r.percent, settings.pageTotal(r.bookId, pageTotals[r.bookId]))) }
    }

    /** Fetches the server's page count of the books not asked about yet; true if any arrived. */
    private suspend fun totalsFor(bookIds: List<Long>): Boolean = coroutineScope {
        val settings = container.appSettings.settings.value
        val missing = bookIds.filter { it !in pageTotals && settings.pageTotals[it] == null }
        if (missing.isEmpty()) return@coroutineScope false
        val limit = Semaphore(4)
        missing.map { id ->
            async {
                limit.withPermit {
                    id to try {
                        api.book(id).pageCount
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }.awaitAll().forEach { (id, count) -> pageTotals[id] = count }
        true
    }
}
