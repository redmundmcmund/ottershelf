package io.github.ottershelf.feature.timer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.ottershelf.AppContainer
import io.github.ottershelf.R
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.settings.ProgressUnit
import io.github.ottershelf.core.tracking.FinishedTimer
import io.github.ottershelf.feature.seriesnext.NextInSeriesCard
import io.github.ottershelf.feature.seriesnext.rememberSeriesNextAfterSession
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.CardTitle
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.DetailTopBar
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.components.PillProgressBar
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The result screen: [summary] once the session is saved; before that (opened from the "save your
 * session" notification) the stopped timer [finished] and its save form.
 */
data class TimerResultUiState(
    val loaded: Boolean = false,
    val summary: SessionSummary? = null,
    val finished: FinishedTimer? = null,
    val form: SaveForm? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
)

private data class ResultLocal(
    val summary: SessionSummary? = null,
    val book: TimerBook? = null,
    val bookLoaded: Boolean = false,
    val saving: Boolean = false,
    val saveError: String? = null,
)

/**
 * [sessionId]'s result: the summary the timer screen just saved (kept in the saved state, so it
 * survives process death), or the finished timer waiting to be saved with the page reached.
 */
class TimerResultViewModel(
    private val container: AppContainer,
    private val bookId: Long,
    private val sessionId: String,
    private val handle: SavedStateHandle,
) : ViewModel() {

    private val sessions = TimerSessions(container)
    private val local = MutableStateFlow(ResultLocal(summary = restore()))

    val state: StateFlow<TimerResultUiState> = combine(container.timer.state, container.timer.loaded, local) { timer, loaded, l ->
        val finished = timer.finished?.takeIf { it.sessionId == sessionId }
        TimerResultUiState(
            loaded = loaded || l.summary != null,
            summary = l.summary,
            finished = finished.takeIf { l.summary == null },
            form = finished?.takeIf { l.bookLoaded }?.let { sessions.form(l.book, it.activeMs, it.startProgress, it.title) },
            saving = l.saving,
            saveError = l.saveError,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TimerResultUiState(summary = local.value.summary))

    private val _closed = Channel<Unit>(Channel.CONFLATED)
    val closed: Flow<Unit> = _closed.receiveAsFlow()

    init {
        if (local.value.summary == null) {
            viewModelScope.launch {
                val book = attempt { sessions.loadBook(bookId) }
                local.update { it.copy(book = book, bookLoaded = true) }
            }
        }
    }

    private fun restore(): SessionSummary? {
        val saved = handle.get<String>(KEY)?.let { runCatching { ApiJson.decodeFromString(SessionSummary.serializer(), it) }.getOrNull() }
        return saved ?: TimerResults.take(sessionId)?.also(::keep)
    }

    private fun keep(summary: SessionSummary) {
        handle[KEY] = ApiJson.encodeToString(SessionSummary.serializer(), summary)
    }

    fun save(entry: ProgressEntry, unit: ProgressUnit) {
        val current = state.value
        val finished = current.finished ?: return
        val form = current.form ?: return
        if (local.value.saving) return
        local.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            val book = local.value.book
            val work = container.appScope.async { sessions.save(finished, book, form, entry, unit) }
            val result = try {
                work.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(saving = false, saveError = e.message ?: e.javaClass.simpleName) }
                return@launch
            }
            when (result) {
                is SaveResult.Saved -> {
                    keep(result.summary)
                    local.update { it.copy(saving = false, summary = result.summary) }
                }
                SaveResult.TooShort, SaveResult.AlreadySaved -> {
                    local.update { it.copy(saving = false) }
                    _closed.trySend(Unit)
                }
            }
        }
    }

    fun discard() {
        container.appScope.launch { container.timer.clearFinished(sessionId) }
        _closed.trySend(Unit)
    }

    private companion object {
        const val KEY = "summary"
    }
}

@Composable
fun TimerResultScreen(route: Route.TimerResult, navigator: AppNavigator) {
    val viewModel = appViewModel { TimerResultViewModel(it, route.bookId, route.sessionId, createSavedStateHandle()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, navigator) {
        viewModel.closed.collect { navigator.back() }
    }
    // A session that reached the end finishes the book: the next in its series (feature.seriesnext).
    val seriesNext = rememberSeriesNextAfterSession(route.bookId, saved = state.summary?.outcome == SaveOutcome.Sent)
    TimerResultContent(
        state = state,
        onDone = { navigator.back() },
        onSave = viewModel::save,
        onDiscard = viewModel::discard,
        next = {
            NextInSeriesCard(
                seriesNext.state,
                onRead = { seriesNext.readRoute()?.let(navigator::replace) },
                onDetails = { seriesNext.detailsRoute()?.let(navigator::replace) },
            )
        },
    )
}

/**
 * Bookmory's results after a session: time read, pages read, speed, pages left and the time left
 * at the user's pace, and the daily goal; then Done back to where the user was.
 */
@Composable
fun TimerResultContent(
    state: TimerResultUiState,
    onDone: () -> Unit = {},
    onSave: (ProgressEntry, ProgressUnit) -> Unit = { _, _ -> },
    onDiscard: () -> Unit = {},
    /** Above Done once saved: the next book in the series when this session finished the book (feature.seriesnext). */
    next: @Composable () -> Unit = {},
) {
    val direction = LocalLayoutDirection.current
    val summary = state.summary
    val title = when {
        summary?.outcome == SaveOutcome.Rejected -> stringResource(R.string.timer_result_rejected_title)
        summary != null -> stringResource(R.string.timer_result_title)
        state.finished != null -> stringResource(R.string.timer_save_title)
        else -> stringResource(R.string.timer_title)
    }
    Scaffold(
        topBar = { DetailTopBar(title = title, onBack = onDone) },
        containerColor = OttershelfTheme.colors.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(
                    start = padding.calculateStartPadding(direction) + 12.dp,
                    end = padding.calculateEndPadding(direction) + 12.dp,
                    top = padding.calculateTopPadding() + 12.dp,
                    bottom = padding.calculateBottomPadding() + 12.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                summary != null -> ResultBody(summary, onDone, next)
                state.finished != null && state.form != null ->
                    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
                        SaveSessionForm(
                            form = state.form,
                            saving = state.saving,
                            error = state.saveError,
                            onSave = onSave,
                            onCancel = onDone,
                            onDiscard = onDiscard,
                            cancelLabel = stringResource(R.string.timer_later),
                        )
                    }
                !state.loaded || state.finished != null -> LoadingState(Modifier.fillMaxWidth().height(240.dp))
                else -> {
                    EmptyState(stringResource(R.string.timer_result_gone), icon = "Timer")
                    AccentButton(stringResource(R.string.timer_done), onClick = onDone, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ResultBody(summary: SessionSummary, onDone: () -> Unit, next: @Composable () -> Unit) {
    val colors = OttershelfTheme.colors
    val rejected = summary.outcome == SaveOutcome.Rejected

    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val tint = if (rejected) colors.destructive else colors.success
            Box(
                Modifier
                    .size(56.dp)
                    .background(tint.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon(if (rejected) "TriangleAlert" else "Check", contentDescription = null, tint = tint, size = 28.dp)
            }
            Spacer(Modifier.height(12.dp))
            Text(durationText(summary.activeSeconds.toLong()), style = clockStyle(44), color = colors.foreground)
            summary.title?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.titleMedium, color = colors.foreground, textAlign = TextAlign.Center, maxLines = 2)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when (summary.outcome) {
                    SaveOutcome.Sent -> stringResource(R.string.timer_result_sent)
                    SaveOutcome.Queued -> stringResource(R.string.timer_result_queued)
                    SaveOutcome.Rejected -> stringResource(R.string.timer_result_rejected, summary.message.orEmpty())
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (rejected) colors.destructive else colors.mutedForeground,
                textAlign = TextAlign.Center,
            )
        }
    }

    if (!rejected) {
        val tiles = buildList {
            summary.pagesRead?.let { add(Triple("BookOpen", it.toString(), stringResource(R.string.timer_stat_pages))) }
                ?: summary.percentGained?.let { add(Triple("BookOpen", signedPercent(it), stringResource(R.string.timer_stat_progress))) }
            summary.pagesPerHour?.let { add(Triple("Gauge", it.roundToInt().toString(), stringResource(R.string.timer_stat_speed))) }
            summary.pagesLeft?.let { add(Triple("Book", it.toString(), stringResource(R.string.timer_stat_pages_left))) }
                ?: summary.percentLeft?.let { add(Triple("Book", stringResource(R.string.timer_percent, it.roundToInt()), stringResource(R.string.timer_stat_percent_left))) }
            summary.timeLeftSeconds?.let { add(Triple("Hourglass", durationText(it), stringResource(R.string.timer_stat_time_left))) }
        }
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (icon, value, label) -> StatTile(icon, value, label, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (summary.showsGoal) GoalCard(summary)
        next()
    }

    Spacer(Modifier.height(4.dp))
    AccentButton(stringResource(R.string.timer_done), onClick = onDone, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun GoalCard(summary: SessionSummary) {
    val colors = OttershelfTheme.colors
    val goal = summary.dailyGoalMinutes ?: return
    val today = summary.todayMinutes
    val reached = today >= goal
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        CardTitle(stringResource(R.string.timer_daily_goal), icon = "Target")
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.timer_goal_today, today, goal),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.foreground,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.timer_percent, (today * 100 / goal).coerceAtMost(999)),
                style = MaterialTheme.typography.labelLarge,
                color = if (reached) colors.success else colors.mutedForeground,
            )
        }
        Spacer(Modifier.height(8.dp))
        PillProgressBar((today.toFloat() / goal).coerceIn(0f, 1f), color = if (reached) colors.success else colors.primary)
        Spacer(Modifier.height(8.dp))
        Text(
            if (reached) stringResource(R.string.timer_goal_reached) else stringResource(R.string.timer_goal_to_go, goal - today),
            style = MaterialTheme.typography.bodySmall,
            color = colors.mutedForeground,
        )
    }
}

/** "+4.5%" / "-2%". */
@Composable
private fun signedPercent(value: Double): String {
    val rounded = (value * 10).roundToInt() / 10.0
    val text = if (rounded % 1.0 == 0.0) rounded.toInt().toString() else String.format(Locale.getDefault(), "%.1f", rounded)
    return stringResource(R.string.timer_signed_percent, if (rounded > 0) "+$text" else text)
}
