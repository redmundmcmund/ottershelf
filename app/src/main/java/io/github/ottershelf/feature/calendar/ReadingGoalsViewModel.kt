package io.github.ottershelf.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.tracking.ActivityOverview
import io.github.ottershelf.feature.stats.ApiStatsRemote
import io.github.ottershelf.feature.stats.StatsRemote
import java.time.LocalDate
import kotlin.math.roundToInt

data class GoalsUiState(
    val today: LocalDate,
    val loading: Boolean = true,
    /** The statistics couldn't be loaded (the goals themselves still show and can be set). */
    val failed: Boolean = false,
    /** Books per year (dashboardConfig.readingGoal); null for none. */
    val yearlyGoal: Int? = null,
    /** Books finished this year (completed readings); null until loaded. */
    val completed: Int? = null,
    /** Books by 31 December at the current rate. */
    val projected: Double? = null,
    val savingYearly: Boolean = false,
    /** Minutes per day (the app's settings key); null for none. */
    val dailyGoal: Int? = null,
    val todaySeconds: Long? = null,
    val currentStreak: Int? = null,
    val longestStreak: Int? = null,
    val lastSevenSeconds: Long? = null,
    val previousSevenSeconds: Long? = null,
) {
    val pace: GoalPace?
        get() = if (yearlyGoal != null && completed != null) CalendarMath.goalPace(completed, yearlyGoal, today) else null
}

/** What the goals screen reports in a snackbar. */
enum class GoalsMessage { YearlySaved, YearlyFailed }

/**
 * Reading goals. The yearly book goal is the server's (`dashboardConfig.readingGoal`, fetched
 * fresh from `auth/me`; `TrackingRepository.setYearlyGoal` writes the whole dashboardConfig back so
 * the web dashboard's widgets survive); the year's finished books come from `completion-timeline`,
 * as Statistics counts them (older servers' `activity-overview.goal` only counts books finished in
 * a session, so books marked read by hand read as 0; see StatsMath.withFinishedBooks), falling back
 * to the overview. The pace is worked out here against the goal shown (the server's overview is
 * cached for minutes, so it can still carry the old goal). The daily minutes goal lives in the
 * app's settings key, checked against today's `activity-days` total. Streaks come from
 * `activity-overview.snapshot`.
 */
class ReadingGoalsViewModel(
    private val container: AppContainer,
    private val stats: StatsRemote = ApiStatsRemote(container.api),
) : ViewModel() {

    private val tracking = container.tracking
    private var loadedAt = 0L
    private val _messages = Channel<GoalsMessage>(Channel.BUFFERED)
    val messages: Flow<GoalsMessage> = _messages.receiveAsFlow()

    private val _state = MutableStateFlow(
        GoalsUiState(
            today = today(),
            yearlyGoal = tracking.yearlyGoal(),
            dailyGoal = container.appSettings.settings.value.dailyGoalMinutes,
        ),
    )
    val state: StateFlow<GoalsUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            container.appSettings.settings.collect { s -> _state.update { it.copy(dailyGoal = s.dailyGoalMinutes?.takeIf { m -> m > 0 }) } }
        }
    }

    fun retry() = load()

    /** The screen showed again (reading may have happened meanwhile, or the day changed): refreshes unless it just did. */
    fun onResume() {
        if (System.currentTimeMillis() - loadedAt >= RESUME_REFRESH_MS || today() != _state.value.today) load()
    }

    fun setDailyGoal(minutes: Int?) {
        container.appSettings.update { it.copy(dailyGoalMinutes = minutes?.coerceIn(1, MAX_DAILY_MINUTES)) }
    }

    fun setYearlyGoal(books: Int?) {
        if (_state.value.savingYearly) return
        _state.update { it.copy(savingYearly = true) }
        // In the app scope: the write finishes even if the screen closes right away.
        container.appScope.launch {
            try {
                tracking.setYearlyGoal(books)
                _state.update { it.copy(savingYearly = false, yearlyGoal = books) }
                _messages.trySend(GoalsMessage.YearlySaved)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(savingYearly = false) }
                _messages.trySend(GoalsMessage.YearlyFailed)
            }
        }
    }

    private fun load() {
        loadedAt = System.currentTimeMillis()
        _state.update { it.copy(loading = it.completed == null, failed = false, today = today()) }
        viewModelScope.launch {
            val user = async { container.refreshUser() }
            val overview = async { attempt { tracking.activityOverview() } }
            val today = async { attempt { tracking.activityDay(today()) } }
            val finished = async { attempt { finishedThisYear() } }
            user.await()
            apply(overview.await(), today.await()?.totals?.totalSeconds, finished.await())
        }
    }

    /** This year's finished books by the completion timeline, the count Statistics shows. */
    private suspend fun finishedThisYear(): Int {
        val now = today()
        return stats.completionTimeline(now.dayOfYear, null)
            .filter { it.year == now.year }
            .sumOf { it.count }
            .roundToInt()
    }

    private fun apply(overview: ActivityOverview?, todaySeconds: Long?, finished: Int? = null) {
        val today = today()
        _state.update { s ->
            val snapshot = overview?.snapshot
            val completed = finished ?: overview?.goal?.completedBooks ?: snapshot?.completedBooksYtd
            s.copy(
                today = today,
                loading = false,
                failed = overview == null,
                yearlyGoal = if (s.savingYearly) s.yearlyGoal else tracking.yearlyGoal(),
                completed = completed ?: s.completed,
                // The overview's projection is from its own count, so it only applies without ours.
                projected = (if (finished == null) overview?.goal?.projectedBooks else null)
                    ?: completed?.let { CalendarMath.projected(it, today) } ?: s.projected,
                todaySeconds = todaySeconds ?: snapshot?.today?.totalSeconds ?: s.todaySeconds,
                currentStreak = snapshot?.currentStreak ?: s.currentStreak,
                longestStreak = snapshot?.longestStreak ?: s.longestStreak,
                lastSevenSeconds = snapshot?.lastSevenDays?.totalSeconds ?: s.lastSevenSeconds,
                previousSevenSeconds = snapshot?.previousSevenDays?.totalSeconds ?: s.previousSevenSeconds,
            )
        }
    }

    private fun today(): LocalDate = LocalDate.now(serverZone(container.auth.user.value))

    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        const val MAX_DAILY_MINUTES = 1440
    }
}
