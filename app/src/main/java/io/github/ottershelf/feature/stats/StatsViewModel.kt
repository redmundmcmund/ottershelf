package io.github.ottershelf.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.feature.stats.model.ArchetypePoint
import io.github.ottershelf.feature.stats.model.CompletionLatency
import io.github.ottershelf.feature.stats.model.DecadeCount
import io.github.ottershelf.feature.stats.model.FormatCount
import io.github.ottershelf.feature.stats.model.FormatStorage
import io.github.ottershelf.feature.stats.model.GenreCount
import io.github.ottershelf.feature.stats.model.GenreReadingTime
import io.github.ottershelf.feature.stats.model.LanguageCount
import io.github.ottershelf.feature.stats.model.LargestBook
import io.github.ottershelf.feature.stats.model.LibrarySummary
import io.github.ottershelf.feature.stats.model.MonthCount
import io.github.ottershelf.feature.stats.model.NameCount
import io.github.ottershelf.feature.stats.model.Overview
import io.github.ottershelf.feature.stats.model.PacePoint
import io.github.ottershelf.feature.stats.model.PeakHourStat
import io.github.ottershelf.feature.stats.model.ProgressFunnelComparison
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

/** One chart's data: loading, failed (tap to retry) or ready ([stale] while a new window loads). */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val message: String?) : Load<Nothing>
    data class Ready<T>(val data: T, val stale: Boolean = false) : Load<T>
}

enum class StatsTab { READING, LIBRARY }

data class LibraryOption(val id: Long, val name: String)

/** Reading per weekday over the window (the user's local days, Monday first): average minutes a day, totals, sessions. */
data class WeekdayData(val averageMinutes: List<Double>, val totalSeconds: List<Double>, val sessions: List<Int>, val events: Int)

data class ReadingCharts(
    val overview: Load<Overview> = Load.Loading,
    val daily: Load<DailySeries> = Load.Loading,
    val heatmap: Load<List<HeatDay>> = Load.Loading,
    val peakHours: Load<List<PeakHourStat>> = Load.Loading,
    val weekdays: Load<WeekdayData> = Load.Loading,
    val completions: Load<List<MonthCount>> = Load.Loading,
    val trajectory: Load<Trajectory> = Load.Loading,
    val genres: Load<List<GenreReadingTime>> = Load.Loading,
    val pace: Load<List<PacePoint>> = Load.Loading,
    val archetypes: Load<List<ArchetypePoint>> = Load.Loading,
    val funnel: Load<ProgressFunnelComparison> = Load.Loading,
    val latency: Load<CompletionLatency> = Load.Loading,
)

data class LibraryCharts(
    val summary: Load<LibrarySummary> = Load.Loading,
    val formats: Load<List<FormatCount>> = Load.Loading,
    val storage: Load<List<FormatStorage>> = Load.Loading,
    val added: Load<List<MonthCount>> = Load.Loading,
    val authors: Load<List<NameCount>> = Load.Loading,
    val series: Load<List<NameCount>> = Load.Loading,
    val genres: Load<List<GenreCount>> = Load.Loading,
    val languages: Load<List<LanguageCount>> = Load.Loading,
    val decades: Load<List<DecadeCount>> = Load.Loading,
    val largest: Load<List<LargestBook>> = Load.Loading,
)

data class StatsUiState(
    val today: LocalDate = LocalDate.now(),
    val tab: StatsTab = StatsTab.READING,
    val period: StatsPeriod = StatsPeriod.D90,
    val libraries: List<LibraryOption> = emptyList(),
    val libraryId: Long? = null,
    val refreshing: Boolean = false,
    val reading: ReadingCharts = ReadingCharts(),
    val library: LibraryCharts = LibraryCharts(),
) {
    val days: Int get() = period.days(today)
}

/** Which chart a retry is for. */
enum class StatsChart {
    OVERVIEW, DAILY, HEATMAP, PEAK, WEEKDAYS, COMPLETIONS, TRAJECTORY, GENRES, PACE, ARCHETYPES, FUNNEL, LATENCY,
    LIB_SUMMARY, LIB_FORMATS, LIB_STORAGE, LIB_ADDED, LIB_AUTHORS, LIB_SERIES, LIB_GENRES, LIB_LANGUAGES, LIB_DECADES, LIB_LARGEST,
}

/**
 * The Statistics screen: every chart loads on its own (in parallel), so one slow or failing
 * endpoint never holds the others. The period row reloads the windowed charts, the library
 * filter everything; a reload keeps the old chart (faded) until the new one arrives. The Library
 * tab loads the first time it is opened. A logged or changed session (`tracking.version`)
 * refreshes the reading charts. Every load first checks the date in the user's zone: after midnight (the
 * screen kept while another shows, or the app left overnight) every chart that counts days reloads
 * with the new today, and so does showing the screen again ([onResume]).
 */
class StatsViewModel(private val container: AppContainer, private val remote: StatsRemote = ApiStatsRemote(container.api)) : ViewModel() {

    private val _state = MutableStateFlow(StatsUiState(today = today()))
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    private val jobs = HashMap<StatsChart, Job>()
    private var libraryLoaded = false

    init {
        viewModelScope.launch {
            runCatching { remote.libraries() }.onSuccess { libs ->
                _state.update { s -> s.copy(libraries = libs.filter { it.type != "podcasts" }.map { LibraryOption(it.id, it.name) }) }
            }
        }
        READING.forEach(::load)
        viewModelScope.launch { container.tracking.version.drop(1).collect { reload(READING) } }
    }

    fun selectTab(tab: StatsTab) {
        _state.update { it.copy(tab = tab) }
        if (tab == StatsTab.LIBRARY && !libraryLoaded) {
            libraryLoaded = true
            reload(LIBRARY)
        } else {
            reload(emptyList())
        }
    }

    fun selectPeriod(period: StatsPeriod) {
        if (period == _state.value.period) return
        _state.update { it.copy(period = period) }
        reload(WINDOWED)
    }

    fun selectLibrary(id: Long?) {
        if (id == _state.value.libraryId) return
        _state.update { it.copy(libraryId = id) }
        reload(if (libraryLoaded) READING + LIBRARY else READING)
    }

    /** The screen shows again: a new day since the last load reloads the charts that count days. */
    fun onResume() = reload(emptyList())

    /** Loads [charts], plus every chart that counts days when the date has moved on since the last load. */
    private fun reload(charts: List<StatsChart>) {
        val dated = if (rollDate()) READING + (if (libraryLoaded) listOf(StatsChart.LIB_ADDED) else emptyList()) else emptyList()
        (charts + dated).distinct().forEach(::load)
    }

    /** Moves `today` on when the user's date has changed; true if it did. */
    private fun rollDate(): Boolean {
        val now = today()
        if (now == _state.value.today) return false
        _state.update { it.copy(today = now) }
        return true
    }

    fun refresh() {
        _state.update { it.copy(today = today(), refreshing = true) }
        val charts = if (libraryLoaded) READING + LIBRARY else READING
        charts.forEach(::load)
        viewModelScope.launch {
            charts.mapNotNull { jobs[it] }.forEach { it.join() }
            _state.update { it.copy(refreshing = false) }
        }
    }

    fun retry(chart: StatsChart) = reload(listOf(chart))

    private fun load(chart: StatsChart) {
        val s = _state.value
        val lib = s.libraryId
        val days = s.days
        val today = s.today
        when (chart) {
            StatsChart.OVERVIEW -> reading(chart, { overview }, { copy(overview = it) }) {
                // The year's finished books from the completion timeline, as the charts count them
                // (see StatsMath.withFinishedBooks); the same request as COMPLETIONS, so the
                // server's cache answers one of the two.
                coroutineScope {
                    val months = async {
                        try {
                            remote.completionTimeline(COMPLETION_DAYS, lib)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                    StatsMath.withFinishedBooks(remote.overview(lib), months.await(), today)
                }
            }
            StatsChart.DAILY -> reading(chart, { daily }, { copy(daily = it) }) {
                // daily-reading counts `days` back from today in UTC: one more covers a zone behind it.
                StatsMath.dailySeries(remote.dailyReading((days * 2 + 1).coerceAtMost(3650), lib), today, days)
            }
            StatsChart.HEATMAP -> reading(chart, { heatmap }, { copy(heatmap = it) }) {
                StatsMath.heatmapYear(remote.dailyReading(today.dayOfYear + 1, lib), today.year, today)
            }
            StatsChart.PEAK -> reading(chart, { peakHours }, { copy(peakHours = it) }) { remote.peakHours(days, lib) }
            StatsChart.WEEKDAYS -> reading(chart, { weekdays }, { copy(weekdays = it) }) {
                val rows = StatsMath.favoriteDays(remote.dailyReading(days + 1, lib), days, today)
                val totals = DoubleArray(7)
                val sessions = IntArray(7)
                rows.forEach { r ->
                    if (r.dayOfWeek in 0..6) {
                        totals[StatsMath.mondayFirst(r.dayOfWeek)] += r.readingSeconds
                        sessions[StatsMath.mondayFirst(r.dayOfWeek)] += r.eventsCount.toInt()
                    }
                }
                WeekdayData(StatsMath.weekdayAverages(rows, days, today), totals.toList(), sessions.toList(), sessions.sum())
            }
            StatsChart.COMPLETIONS -> reading(chart, { completions }, { copy(completions = it) }) {
                StatsMath.completionMonths(remote.completionTimeline(COMPLETION_DAYS, lib), today)
            }
            StatsChart.TRAJECTORY -> reading(chart, { trajectory }, { copy(trajectory = it) }) {
                val window = StatsMath.trajectoryDays(LocalDate.now(ZoneOffset.UTC))
                // The server needs a goal to answer; without the user's goal the target line isn't drawn.
                val goal = container.tracking.yearlyGoal()?.takeIf { it > 0 }
                Trajectory(StatsMath.lastTwelveMonths(remote.goalTrajectory(goal ?: 12, window, lib)).points, goal)
            }
            StatsChart.GENRES -> reading(chart, { genres }, { copy(genres = it) }) { remote.genreReadingTime(days, lib) }
            StatsChart.PACE -> reading(chart, { pace }, { copy(pace = it) }) { StatsMath.pacePoints(remote.readingPace(1825, lib)) }
            StatsChart.ARCHETYPES -> reading(chart, { archetypes }, { copy(archetypes = it) }) {
                // The server takes the hour and weekday in UTC; move them into the user's zone.
                val offset = zoneOf(container.auth.user.value).rules.getOffset(Instant.now()).totalSeconds / 3600.0
                StatsMath.localArchetypes(remote.sessionArchetypes(days, lib), offset)
            }
            StatsChart.FUNNEL -> reading(chart, { funnel }, { copy(funnel = it) }) { remote.progressFunnel(days, lib) }
            StatsChart.LATENCY -> reading(chart, { latency }, { copy(latency = it) }) { remote.completionLatency(1825, lib) }

            StatsChart.LIB_SUMMARY -> library(chart, { summary }, { copy(summary = it) }) { remote.librarySummary(lib) }
            StatsChart.LIB_FORMATS -> library(chart, { formats }, { copy(formats = it) }) { remote.formats(lib) }
            StatsChart.LIB_STORAGE -> library(chart, { storage }, { copy(storage = it) }) { remote.storage(lib) }
            StatsChart.LIB_ADDED -> library(chart, { added }, { copy(added = it) }) {
                val rows = remote.booksAdded(lib)
                val now = YearMonth.from(today)
                StatsMath.months(rows, now.minusMonths(59), now)
            }
            StatsChart.LIB_AUTHORS -> library(chart, { authors }, { copy(authors = it) }) { remote.topAuthors(lib) }
            StatsChart.LIB_SERIES -> library(chart, { series }, { copy(series = it) }) { remote.topSeries(lib) }
            StatsChart.LIB_GENRES -> library(chart, { genres }, { copy(genres = it) }) { remote.genres(lib) }
            StatsChart.LIB_LANGUAGES -> library(chart, { languages }, { copy(languages = it) }) { remote.languages(lib) }
            StatsChart.LIB_DECADES -> library(chart, { decades }, { copy(decades = it) }) { remote.decades(lib) }
            StatsChart.LIB_LARGEST -> library(chart, { largest }, { copy(largest = it) }) { remote.largestBooks(lib) }
        }
    }

    private fun <T> reading(chart: StatsChart, get: ReadingCharts.() -> Load<T>, put: ReadingCharts.(Load<T>) -> ReadingCharts, fetch: suspend () -> T) =
        run(chart, { it.reading.get() }, { s, v -> s.copy(reading = s.reading.put(v)) }, fetch)

    private fun <T> library(chart: StatsChart, get: LibraryCharts.() -> Load<T>, put: LibraryCharts.(Load<T>) -> LibraryCharts, fetch: suspend () -> T) =
        run(chart, { it.library.get() }, { s, v -> s.copy(library = s.library.put(v)) }, fetch)

    private fun <T> run(chart: StatsChart, get: (StatsUiState) -> Load<T>, put: (StatsUiState, Load<T>) -> StatsUiState, fetch: suspend () -> T) {
        jobs[chart]?.cancel()
        _state.update { s ->
            val current = get(s)
            put(s, if (current is Load.Ready) current.copy(stale = true) else Load.Loading)
        }
        jobs[chart] = viewModelScope.launch {
            val result: Load<T> = try {
                Load.Ready(fetch())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e.message)
            }
            _state.update { put(it, result) }
        }
    }

    private fun today(): LocalDate = LocalDate.now(zoneOf(container.auth.user.value))

    private companion object {
        val READING = StatsChart.entries.filter { !it.name.startsWith("LIB_") }
        val LIBRARY = StatsChart.entries.filter { it.name.startsWith("LIB_") }
        /** The completion timeline's window: five years, as the web's. */
        const val COMPLETION_DAYS = 1825
        val WINDOWED = listOf(StatsChart.DAILY, StatsChart.PEAK, StatsChart.WEEKDAYS, StatsChart.GENRES, StatsChart.ARCHETYPES, StatsChart.FUNNEL)
    }
}

/** The zone the server splits days in: users.settings.timezone, else UTC (the server's fallback). */
internal fun zoneOf(user: AuthUser?): ZoneId =
    user?.settings?.timezone?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
