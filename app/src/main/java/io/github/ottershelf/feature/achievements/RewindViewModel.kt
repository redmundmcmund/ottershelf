package io.github.ottershelf.feature.achievements

import androidx.compose.runtime.Immutable
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.feature.achievements.model.Achievement
import io.github.ottershelf.feature.stats.RankedItem
import io.github.ottershelf.feature.stats.model.GenreReadingTime
import io.github.ottershelf.ui.components.coverModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * A book finished in the year: its card, cover model, (from its readings) how long it took, and
 * the day it was finished (`YYYY-MM-DD`, for the order).
 */
@Immutable
data class RewindBook(val book: BookCard, val cover: Any?, val daysTaken: Int? = null, val seconds: Long? = null, val finishedOn: String? = null)

/**
 * Everything the year in review shows for [year]; a part that couldn't load is empty (its card is
 * left out). [genres] only for the current year: the server's window ends today. [finishedCount]
 * is every reading completed that year (the completion timeline's, as the per-month chart), which
 * can be more than the [finished] books found.
 */
@Immutable
data class RewindData(
    val year: Int,
    val availableYears: List<Int> = listOf(year),
    val totalSeconds: Double = 0.0,
    val daysRead: Int = 0,
    val sessions: Int = 0,
    val monthlySeconds: List<Double> = List(12) { 0.0 },
    val streak: Streak? = null,
    val bestDay: Pair<LocalDate, Long>? = null,
    val allTimeStreak: Int? = null,
    val finished: List<RewindBook> = emptyList(),
    val finishedCount: Int = finished.size,
    val completionsByMonth: List<Double> = List(12) { 0.0 },
    val peakHours: List<Double> = emptyList(),
    val genres: List<GenreReadingTime> = emptyList(),
    val authors: List<RankedItem> = emptyList(),
    val badges: List<Achievement> = emptyList(),
) {
    val hasAnything: Boolean get() = totalSeconds > 0 || finishedCount > 0 || finished.isNotEmpty() || badges.isNotEmpty()
    val fastest: RewindBook? get() = finished.filter { it.daysTaken != null }.minByOrNull { it.daysTaken!! }
    val mostTime: RewindBook? get() = finished.filter { (it.seconds ?: 0) > 0 }.maxByOrNull { it.seconds!! }
    val bestMonth: Int? get() = monthlySeconds.withIndex().filter { it.value > 0 }.maxByOrNull { it.value }?.index
}

@Immutable
data class RewindUiState(val year: Int, val loading: Boolean = true, val error: String? = null, val data: RewindData? = null)

/** The pages of the story, in order; a page with nothing to show is left out ([RewindData.pages]). */
enum class RewindPage { INTRO, TIME, BOOKS, STREAK, HOURS, GENRES, AUTHORS, BADGES, OUTRO }

fun RewindData.pages(): List<RewindPage> = buildList {
    add(RewindPage.INTRO)
    if (!hasAnything) return@buildList
    if (totalSeconds > 0) add(RewindPage.TIME)
    if (finishedCount > 0 || finished.isNotEmpty()) add(RewindPage.BOOKS)
    if (streak != null) add(RewindPage.STREAK)
    if (peakHours.any { it > 0 }) add(RewindPage.HOURS)
    if (genres.isNotEmpty()) add(RewindPage.GENRES)
    if (authors.isNotEmpty()) add(RewindPage.AUTHORS)
    if (badges.isNotEmpty()) add(RewindPage.BADGES)
    add(RewindPage.OUTRO)
}

/**
 * Loads a year in review from the existing endpoints: `activity-calendar/:year` (time, days,
 * sessions, months, the streak and best day), `activity-overview` (the all-time streak), the book
 * query for books finished that year (covers, authors) with each one's readings (days taken, time),
 * `completion-timeline` (finished per month), peak hours (the server's for this year; for an
 * earlier year worked out from that year's `session-timeline` weeks), `genre-reading-time` (this
 * year only) and the achievements earned that year. Each part fails on its own.
 */
class RewindViewModel(private val container: AppContainer, initialYear: Int?) : ViewModel() {

    private val remote: AchievementsRemote = ApiAchievementsRemote(container.api)
    private val zone: ZoneId = zoneOf(container.auth.user.value)
    private val today: LocalDate get() = LocalDate.now(zone)

    private val _state = MutableStateFlow(RewindUiState(initialYear ?: RewindMath.defaultYear(LocalDate.now(zone))))
    val state: StateFlow<RewindUiState> = _state.asStateFlow()
    private var job: Job? = null
    /** Years learned from earlier loads (a year before the first session answers with none). */
    private var knownYears: List<Int> = emptyList()

    init {
        load(_state.value.year)
    }

    fun selectYear(year: Int) {
        if (year == _state.value.year && _state.value.data != null) return
        load(year)
    }

    fun retry() = load(_state.value.year)

    private fun load(year: Int) {
        job?.cancel()
        _state.value = RewindUiState(year, loading = true, data = _state.value.data?.takeIf { it.year == year })
        job = viewModelScope.launch {
            try {
                val data = build(year)
                _state.update { it.copy(loading = false, error = null, data = data) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private suspend fun <T> soft(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun build(year: Int): RewindData = coroutineScope {
        val tracking = container.tracking
        val now = today
        val current = year == now.year
        val daysSinceJan1 = (ChronoUnit.DAYS.between(LocalDate.of(year, 1, 1), now).toInt() + 1)

        val calendarAsync = async { tracking.activityCalendar(year) } // the core: fails the whole review
        val overviewAsync = async { soft { tracking.activityOverview() } }
        val finishedAsync = async { soft { remote.finishedIn(year).items } }
        val timelineAsync = async { if (daysSinceJan1 in 1..3650) soft { remote.completionTimeline(daysSinceJan1) } else null }
        val genresAsync = async { if (current) soft { remote.genreReadingTime(daysSinceJan1.coerceIn(1, 366)) } else null }
        val catalogueAsync = async { soft { remote.catalogue() } }
        val hoursAsync = async {
            if (current) {
                soft { remote.peakHours(daysSinceJan1.coerceIn(1, 366)) }?.let { rows ->
                    List(24) { h -> rows.filter { it.hour == h }.sumOf { it.readingSeconds } }
                }
            } else {
                peakHoursFromTimeline(year, now)
            }
        }

        val calendar = calendarAsync.await()
        val days = RewindMath.daysOf(calendar.days, year)
        // Every reading completed that year, rereads included (the status keeps only the latest).
        val completions = timelineAsync.await()?.let { RewindMath.completionsByMonth(it, year) }
        val completed = completions?.sum()?.roundToInt()
        val finishedCards = finishedAsync.await().orEmpty().filter { it.readStatus?.status != "abandoned" }
        var finished = withReadings(finishedCards, year)
        if (completed != null && finished.size < completed) {
            // Some finishes aren't in their book's status any more (a reread since, a later finish):
            // look for them among the books whose status could hide one.
            finished = (finished + hiddenFinishes(year, finishedCards.mapTo(HashSet()) { it.id }, completed - finished.size))
                .sortedBy { it.finishedOn.orEmpty() }
        }

        RewindData(
            year = year,
            availableYears = RewindMath.years(calendar.availableYears, year, now.year, knownYears).also { knownYears = it },
            totalSeconds = days.sumOf { it.second }.toDouble(),
            daysRead = days.count { it.second > 0 },
            sessions = calendar.days.sumOf { it.sessionsCount },
            monthlySeconds = RewindMath.monthlySeconds(days),
            streak = RewindMath.longestStreak(days),
            bestDay = RewindMath.bestDay(days),
            allTimeStreak = overviewAsync.await()?.snapshot?.longestStreak?.takeIf { it > 0 },
            finished = finished,
            finishedCount = completed ?: finished.size,
            completionsByMonth = completions
                ?: finished.fold(DoubleArray(12)) { acc, b -> finishedMonth(b.finishedOn, year)?.let { acc[it - 1] += 1.0 }; acc }.toList(),
            peakHours = hoursAsync.await().orEmpty(),
            genres = genresAsync.await().orEmpty().filter { it.readingSeconds > 0 }.sortedByDescending { it.readingSeconds }.take(6),
            authors = RewindMath.topAuthors(finished.map { it.book }),
            badges = catalogueAsync.await()?.let { AchievementLogic.earnedIn(it, year, zone) }.orEmpty(),
        )
    }

    /** Each finished book's reading that ended this year (six requests at a time, 60 books at most). */
    private suspend fun withReadings(cards: List<BookCard>, year: Int): List<RewindBook> = coroutineScope {
        val gate = Semaphore(6)
        cards.mapIndexed { i, card ->
            async {
                val attempt = if (i < 60) gate.withPermit { soft { container.tracking.attempts(card.id).items } }?.let { RewindMath.finishedAttempt(it, year) } else null
                RewindBook(
                    card, container.api.coverModel(card), attempt?.let { RewindMath.daysTaken(it) }, attempt?.totalSeconds?.takeIf { it > 0 },
                    finishedOn = attempt?.endedOn?.take(10) ?: card.readStatus?.finishedAt?.take(10),
                )
            }
        }.awaitAll()
    }

    /**
     * Books with a reading completed in [year] that their status no longer shows ([known] are those
     * it does): each candidate ([AchievementsRemote.finishCandidates]) is kept if one of its readings
     * completed that year. Pages through the candidates until [missing] are found, checking at most
     * [MAX_CANDIDATES] (six at a time); what isn't found still counts in the headline.
     */
    private suspend fun hiddenFinishes(year: Int, known: Set<Long>, missing: Int): List<RewindBook> = coroutineScope {
        val gate = Semaphore(6)
        val found = ArrayList<RewindBook>()
        var checked = 0
        var page = 0
        while (found.size < missing && checked < MAX_CANDIDATES) {
            val result = soft { remote.finishCandidates(year, page) } ?: break
            val cards = result.items.filter { it.id !in known }.take(MAX_CANDIDATES - checked)
            checked += cards.size
            found += cards.map { card ->
                async {
                    val attempt = gate.withPermit { soft { container.tracking.attempts(card.id).items } }?.let { RewindMath.finishedAttempt(it, year) }
                    attempt?.let {
                        RewindBook(card, container.api.coverModel(card), RewindMath.daysTaken(it), it.totalSeconds.takeIf { s -> s > 0 }, finishedOn = it.endedOn?.take(10))
                    }
                }
            }.awaitAll().filterNotNull()
            page++
            if (result.items.size < ApiAchievementsRemote.PAGE_SIZE || page * ApiAchievementsRemote.PAGE_SIZE >= result.total) break
        }
        found
    }

    private suspend fun peakHoursFromTimeline(year: Int, now: LocalDate): List<Double>? = coroutineScope {
        val gate = Semaphore(6)
        val weeks = RewindMath.weeksOf(year, now)
        if (weeks.isEmpty()) return@coroutineScope null
        val sessions = weeks.map { (y, w) -> async { gate.withPermit { soft { container.tracking.sessionTimeline(y, w).items } } } }.awaitAll()
        if (sessions.all { it == null }) null else RewindMath.peakHours(sessions.filterNotNull().flatten(), zone, year)
    }

    private fun finishedMonth(day: String?, year: Int): Int? =
        day?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { it.year == year }?.monthValue

    companion object {
        /** The most candidate books checked for finishes their status hides (one request each). */
        private const val MAX_CANDIDATES = 200

        /** The zone the server splits days in: users.settings.timezone, else UTC. */
        fun zoneOf(user: AuthUser?): ZoneId =
            user?.settings?.timezone?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
    }
}
