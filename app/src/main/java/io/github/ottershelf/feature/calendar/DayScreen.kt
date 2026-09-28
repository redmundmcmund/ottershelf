package io.github.ottershelf.feature.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ottershelf.R
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.CardRow
import io.github.ottershelf.ui.components.CardTitle
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.DetailTopBar
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.components.SectionHeader
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** One day's reading ([Route.Day.date] is `YYYY-MM-DD`). */
@Composable
fun DayScreen(route: Route.Day, navigator: AppNavigator) {
    val date = remember(route.date) { runCatching { LocalDate.parse(route.date) }.getOrElse { LocalDate.now() } }
    val viewModel = appViewModel { DayViewModel(it, date, this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!.cacheDir) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    val dates = readingDatesViewModel()
    ReadingDatesHost(dates, navigator, onSaved = viewModel::readingSaved)
    DayContent(
        state = state,
        onBack = { navigator.back() },
        onRetry = viewModel::retry,
        onOpenBook = { row ->
            viewModel.opening(row)
            navigator.navigate(Route.BookDetail(row.bookId))
        },
        onOpenReading = { row ->
            viewModel.opening(row)
            navigator.navigate(Route.BookDetail(row.bookId))
        },
        onAddBook = { dates.openPicker(date) },
    )
}

/**
 * A day: the totals (time, sessions, books) with the daily goal's ring when one is set, the books
 * started, finished or given up that day, then each session (cover, title, start and end, time,
 * pages read); tap one for its book. [onAddBook]: "Add a book for this day" (its dates).
 */
@Composable
fun DayContent(
    state: DayUiState,
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
    onOpenBook: (DaySessionRow) -> Unit = {},
    onOpenReading: (DayReadingRow) -> Unit = {},
    onAddBook: (() -> Unit)? = null,
) {
    val direction = LocalLayoutDirection.current
    val locale = Locale.getDefault()
    val title = remember(state.date, locale) {
        state.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale)).replaceFirstChar { it.titlecase(locale) }
    }
    Scaffold(
        topBar = { DetailTopBar(title = title, onBack = onBack, subtitle = state.date.year.toString()) },
        containerColor = OttershelfTheme.colors.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = padding.calculateStartPadding(direction) + 12.dp,
                    end = padding.calculateEndPadding(direction) + 12.dp,
                    top = padding.calculateTopPadding() + 12.dp,
                    bottom = padding.calculateBottomPadding() + 12.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.loading -> LoadingState(Modifier.fillMaxWidth().height(240.dp))
                state.failed && state.sessions.isEmpty() -> ErrorState(onRetry = onRetry, message = stringResource(R.string.calendar_day_failed))
                else -> {
                    TotalsCard(state)
                    if (state.readings.isNotEmpty()) ReadingsCard(state.readings, onOpenReading)
                    if (state.sessions.isNotEmpty()) {
                        SessionsCard(state, onOpenBook)
                    } else if (state.readings.isEmpty()) {
                        EmptyState(stringResource(R.string.calendar_day_empty), icon = "BookOpen")
                    }
                }
            }
            // Not before the day has loaded: a failed load shows only Retry.
            if (onAddBook != null && !state.loading && !(state.failed && state.sessions.isEmpty())) {
                SecondaryButton(
                    stringResource(R.string.calendar_add_book_for_day),
                    onClick = onAddBook,
                    icon = "BookPlus",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The books whose reading started, was finished or given up that day: cover, title and what happened. */
@Composable
private fun ReadingsCard(readings: List<DayReadingRow>, onOpen: (DayReadingRow) -> Unit) {
    val colors = OttershelfTheme.colors
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)) {
        SectionHeader(stringResource(R.string.calendar_day_readings), icon = "BookCheck", count = readings.size, modifier = Modifier.padding(horizontal = 2.dp))
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            readings.forEach { row ->
                CardRow(onClick = { onOpen(row) }) {
                    BookCover(row.cover, row.title, Modifier.width(44.dp), seed = row.bookId.toString())
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).align(Alignment.CenterVertically)) {
                        Text(
                            row.title ?: stringResource(R.string.calendar_untitled),
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.foreground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        when (row.kind) {
                            MarkKind.FINISHED -> Pill(stringResource(R.string.calendar_mark_finished), colors.readStatus(ReadStatus.READ), icon = ReadStatus.READ.lucideIcon)
                            MarkKind.GAVE_UP -> Pill(stringResource(R.string.calendar_mark_gave_up), colors.readStatus(ReadStatus.ABANDONED), icon = ReadStatus.ABANDONED.lucideIcon)
                            MarkKind.STARTED -> Pill(stringResource(R.string.calendar_mark_started), colors.primary, icon = ReadStatus.READING.lucideIcon)
                        }
                    }
                    Chevron(left = false, tint = colors.mutedForeground, size = 18.dp, modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
    }
}

@Composable
private fun TotalsCard(state: DayUiState) {
    val colors = OttershelfTheme.colors
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        CardTitle(stringResource(R.string.calendar_day_reading_time), icon = "Timer")
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(durationLabel(state.totalSeconds), style = bigNumberStyle(30), color = colors.foreground, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.calendar_day_counts,
                        pluralStringResource(R.plurals.calendar_sessions, state.sessions.size, state.sessions.size),
                        booksLabel(state.books),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedForeground,
                )
                val goal = state.goalMinutes
                if (goal != null) {
                    val minutes = (state.totalSeconds / 60).toInt()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (minutes >= goal) stringResource(R.string.calendar_goal_reached)
                        else stringResource(R.string.calendar_goal_to_go, goal - minutes),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (minutes >= goal) colors.success else colors.primary,
                    )
                }
            }
            val goal = state.goalMinutes
            if (goal != null) {
                val minutes = (state.totalSeconds / 60).toInt()
                GoalRing(
                    progress = minutes.toFloat() / goal,
                    size = 84.dp,
                    color = if (minutes >= goal) colors.success else colors.primary,
                ) {
                    RingCaption(
                        stringResource(R.string.calendar_percent, (minutes * 100 / goal).coerceAtMost(999)),
                        stringResource(R.string.calendar_of_minutes, goal),
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionsCard(state: DayUiState, onOpenBook: (DaySessionRow) -> Unit) {
    val colors = OttershelfTheme.colors
    val times = remember(state.zone) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(state.zone) }
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)) {
        SectionHeader(stringResource(R.string.calendar_day_sessions), icon = "BookOpen", count = state.sessions.size, modifier = Modifier.padding(horizontal = 2.dp))
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.sessions.forEach { row ->
                CardRow(onClick = { onOpenBook(row) }) {
                    BookCover(row.cover, row.title, Modifier.width(44.dp), seed = row.bookId.toString())
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).align(Alignment.CenterVertically)) {
                        Text(
                            row.title ?: stringResource(R.string.calendar_untitled),
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.foreground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LucideIcon(if (row.listening) "Headphones" else "Timer", contentDescription = null, tint = colors.mutedForeground, size = 12.dp)
                            Spacer(Modifier.width(4.dp))
                            val span = if (row.startMs != null && row.endMs != null) {
                                stringResource(
                                    R.string.calendar_session_span,
                                    times.format(Instant.ofEpochMilli(row.startMs)),
                                    times.format(Instant.ofEpochMilli(row.endMs)),
                                )
                            } else {
                                null
                            }
                            Text(
                                listOfNotNull(span, durationLabel(row.seconds)).joinToString("  ·  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.mutedForeground,
                                maxLines = 1,
                            )
                        }
                        progressText(row)?.let { (text, gained) ->
                            Spacer(Modifier.height(4.dp))
                            Pill(text, if (gained) colors.success else colors.mutedForeground, icon = "BookOpen")
                        }
                    }
                    Chevron(left = false, tint = colors.mutedForeground, size = 18.dp, modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
    }
}

/** "+23 pages" (from the user's page total), else "+4.5%"; null when the session has no progress. */
@Composable
private fun progressText(row: DaySessionRow): Pair<String, Boolean>? {
    val pages = row.pages
    val percent = row.percent ?: return null
    if (pages != null) {
        if (pages == 0) return null
        val sign = if (pages > 0) "+" else "-"
        return pluralStringResource(R.plurals.calendar_pages_read, abs(pages), sign, abs(pages)) to (pages > 0)
    }
    val rounded = (percent * 10).roundToInt() / 10.0
    if (rounded == 0.0) return null
    val text = if (rounded % 1.0 == 0.0) abs(rounded).toInt().toString() else String.format(Locale.getDefault(), "%.1f", abs(rounded))
    return stringResource(R.string.calendar_percent_read, if (rounded > 0) "+" else "-", text) to (rounded > 0)
}
