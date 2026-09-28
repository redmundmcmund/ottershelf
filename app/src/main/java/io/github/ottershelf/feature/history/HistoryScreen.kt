package io.github.ottershelf.feature.history

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ottershelf.R
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.tracking.AttemptOutcome
import io.github.ottershelf.core.tracking.ReadingAttempt
import io.github.ottershelf.feature.calendar.PickerBook
import io.github.ottershelf.feature.calendar.ReadingDatesHost
import io.github.ottershelf.feature.calendar.readingDatesViewModel
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.CardRow
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.components.SectionHeader
import io.github.ottershelf.ui.components.StatusIcon
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.TopBarActions
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The reading history: a root list (the drawer's Tracking > History) under the shell's toolbar. */
@Composable
fun HistoryScreen(navigator: AppNavigator, contentPadding: PaddingValues) {
    val viewModel = appViewModel { HistoryViewModel(it, this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!.cacheDir) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Back from a book or the reader, or the app resumed: reading may have changed meanwhile.
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { viewModel.onPause() }
    }
    TopBarActions {
        IconButton(onClick = { navigator.navigate(Route.Calendar) }) {
            LucideIcon("CalendarDays", contentDescription = stringResource(R.string.history_open_calendar), tint = LocalContentColor.current, size = 22.dp)
        }
    }
    // A reading's dates, in the Calendar's dates sheet (feature.calendar).
    val dates = readingDatesViewModel()
    ReadingDatesHost(dates, navigator, onSaved = viewModel::readingSaved)
    HistoryContent(
        state = state,
        coverOf = viewModel::cover,
        onFilter = viewModel::setFilter,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retry,
        onOpenBook = { entry ->
            viewModel.opening(entry)
            navigator.navigate(Route.BookDetail(entry.bookId))
        },
        onEditDates = { entry ->
            dates.edit(PickerBook(entry.bookId, entry.title, entry.authors, viewModel.cover(entry)), entry.attempt())
        },
        contentPadding = contentPadding,
    )
}

/**
 * The history: filter chips, then what the user is reading now, each year (its summary, then a card per
 * month, newest first) and the readings without dates. Each row: cover, title, author, the dates,
 * the days it took, a reread's number and the user's rating; tap opens the book. [onEditDates]: the
 * row's trailing button, "Edit dates" (or "Add dates" for a reading without them).
 */
@Composable
fun HistoryContent(
    state: HistoryUiState,
    coverOf: (HistoryEntry) -> Any?,
    onFilter: (HistoryFilter) -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    onOpenBook: (HistoryEntry) -> Unit = {},
    onEditDates: ((HistoryEntry) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val colors = OttershelfTheme.colors
    val direction = LocalLayoutDirection.current
    val outer = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())
    val entries = state.entries
    when {
        entries == null && state.failed -> ErrorState(onRetry, outer, message = stringResource(R.string.history_load_failed))
        entries == null -> LoadingState(outer)
        else -> {
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                state = pullState,
                modifier = outer,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = state.refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = colors.card,
                        color = colors.primary,
                    )
                },
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = contentPadding.calculateStartPadding(direction) + 12.dp,
                        end = contentPadding.calculateEndPadding(direction) + 12.dp,
                        top = 12.dp,
                        bottom = contentPadding.calculateBottomPadding() + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (entries.isNotEmpty()) {
                        item(key = "filters") { FilterChips(entries, state.filter, onFilter) }
                    }
                    if (state.failed || state.failedBooks > 0) {
                        item(key = "failed") {
                            Text(
                                if (state.failed) {
                                    stringResource(R.string.history_refresh_failed)
                                } else {
                                    pluralStringResource(R.plurals.history_some_failed, state.failedBooks, state.failedBooks)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.mutedForeground,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(OttershelfTheme.radii.md)).clickable(onClick = onRetry).padding(6.dp),
                            )
                        }
                    }
                    when {
                        entries.isEmpty() -> item(key = "empty") {
                            EmptyState(stringResource(R.string.history_empty), icon = "RotateCcwClock", contentPadding = PaddingValues(top = 64.dp, start = 24.dp, end = 24.dp))
                        }
                        state.groups.isEmpty() -> item(key = "empty-filter") {
                            EmptyState(stringResource(R.string.history_empty_filter), icon = "RotateCcwClock", compact = true)
                        }
                    }
                    state.groups.forEach { group ->
                        when (group) {
                            is HistoryGroup.Now -> {
                                item(key = "h-${group.key}") {
                                    GroupHeader(
                                        title = stringResource(R.string.history_reading_now),
                                        icon = "BookOpen",
                                        count = group.entries.size,
                                        summary = pluralStringResource(R.plurals.history_reading_count, group.entries.size, group.entries.size),
                                    )
                                }
                                item(key = "c-${group.key}") { RowsCard(null, group.entries, state.today.year, coverOf, onOpenBook, onEditDates) }
                            }
                            is HistoryGroup.Year -> {
                                item(key = "h-${group.key}") {
                                    GroupHeader(
                                        title = group.year.toString(),
                                        icon = "CalendarRange",
                                        count = group.months.sumOf { it.entries.size },
                                        summary = yearSummary(group),
                                    )
                                }
                                group.months.forEach { month ->
                                    item(key = "m-${group.key}-${month.month}") {
                                        RowsCard(monthTitle(month), month.entries, group.year, coverOf, onOpenBook, onEditDates)
                                    }
                                }
                            }
                            is HistoryGroup.Undated -> {
                                item(key = "h-${group.key}") {
                                    GroupHeader(title = stringResource(R.string.history_undated), icon = "CalendarRange", count = group.entries.size, summary = null)
                                }
                                item(key = "c-${group.key}") { RowsCard(null, group.entries, state.today.year, coverOf, onOpenBook, onEditDates) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChips(entries: List<HistoryEntry>, selected: HistoryFilter, onFilter: (HistoryFilter) -> Unit) {
    val counts = remember(entries) { HistoryFilter.entries.associateWith { f -> entries.count { f.matches(it.outcome) } } }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HistoryFilter.entries.forEach { filter ->
            val label = when (filter) {
                HistoryFilter.ALL -> R.string.history_filter_all
                HistoryFilter.FINISHED -> R.string.history_filter_finished
                HistoryFilter.READING -> R.string.history_filter_reading
                HistoryFilter.GAVE_UP -> R.string.history_filter_gave_up
            }
            Chip(stringResource(label), counts[filter] ?: 0, active = filter == selected, onClick = { onFilter(filter) })
        }
    }
}

/** A filter chip in the app's chip look (36dp, card or accent tint), with its count. */
@Composable
private fun Chip(label: String, count: Int, active: Boolean, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.lg)
    Row(
        Modifier
            .height(36.dp)
            .clip(shape)
            .background(if (active) colors.accentTint else colors.card)
            .border(1.dp, if (active) colors.primary.copy(alpha = 0.5f) else colors.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (active) colors.primary else colors.foreground, maxLines = 1)
        Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = if (active) colors.primary else colors.mutedForeground, maxLines = 1)
    }
}

/** A group's header between the cards (as the Achievements sections): icon, title, count, and its summary. */
@Composable
private fun GroupHeader(title: String, icon: String, count: Int, summary: String?) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, start = 2.dp, end = 2.dp)) {
        SectionHeader(title = title, icon = icon, count = count)
        if (summary != null) {
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = OttershelfTheme.colors.mutedForeground,
                maxLines = 2,
                modifier = Modifier.padding(start = 38.dp, top = 2.dp),
            )
        }
    }
}

// Days are left out when none can be counted: books marked read by hand have no start date, and
// "0 days of reading" next to 14 finished books reads as a mistake.
@Composable
private fun yearSummary(group: HistoryGroup.Year): String {
    val parts = buildList {
        add(pluralStringResource(R.plurals.history_finished_count, group.finished, group.finished))
        if (group.days > 0) add(pluralStringResource(R.plurals.history_days_total, group.days, group.days))
        if (group.gaveUp > 0) add(pluralStringResource(R.plurals.history_gave_up_count, group.gaveUp, group.gaveUp))
    }
    return parts.joinToString(stringResource(R.string.history_summary_separator))
}

@Composable
private fun monthTitle(month: HistoryMonth): String {
    val locale = Locale.getDefault()
    return remember(month.month, locale) {
        month.month.format(DateTimeFormatter.ofPattern("LLLL", locale)).replaceFirstChar { it.titlecase(locale) }
    }
}

/** A card of readings, under an optional [title] (the month). [year]: dates in another year show theirs. */
@Composable
private fun RowsCard(
    title: String?,
    entries: List<HistoryEntry>,
    year: Int,
    coverOf: (HistoryEntry) -> Any?,
    onOpenBook: (HistoryEntry) -> Unit,
    onEditDates: ((HistoryEntry) -> Unit)?,
) {
    val colors = OttershelfTheme.colors
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = if (title != null) 12.dp else 10.dp, bottom = 10.dp)) {
        if (title != null) {
            Row(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    pluralStringResource(R.plurals.history_book_count, entries.size, entries.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.mutedForeground,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            entries.forEach { entry -> HistoryRow(entry, year, coverOf, onOpenBook, onEditDates) }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    year: Int,
    coverOf: (HistoryEntry) -> Any?,
    onOpenBook: (HistoryEntry) -> Unit,
    onEditDates: ((HistoryEntry) -> Unit)?,
) {
    val colors = OttershelfTheme.colors
    CardRow(onClick = { onOpenBook(entry) }) {
        BookCover(coverOf(entry), entry.title, Modifier.width(44.dp), authors = entry.authors.joinToString(", "), seed = entry.bookId.toString())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title ?: stringResource(R.string.history_untitled),
                style = MaterialTheme.typography.titleSmall,
                color = colors.foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.authors.isNotEmpty()) {
                Text(
                    entry.authors.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIcon(statusOf(entry), size = 14.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    datesLine(entry, year),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.foreground.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val days = entry.days
            if (days != null || entry.isReread || entry.rating != null) {
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (days != null) {
                        Pill(
                            if (entry.isOpen) stringResource(R.string.history_day_n, days) else pluralStringResource(R.plurals.history_days, days, days),
                            background = colors.muted,
                            color = colors.mutedForeground,
                        )
                    }
                    if (entry.isReread) {
                        Pill(stringResource(R.string.history_reread, entry.readingNumber), background = colors.accentTint, color = colors.primary)
                    }
                    entry.rating?.let { Stars(it) }
                }
            }
        }
        if (onEditDates != null) {
            Spacer(Modifier.width(6.dp))
            if (!entry.isOpen && entry.ended == null) AddDatesButton { onEditDates(entry) } else EditDatesButton { onEditDates(entry) }
        }
    }
}

/** A row's "Edit dates": a small pencil at its end (the row itself opens the book). */
@Composable
private fun EditDatesButton(onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    val label = stringResource(R.string.history_edit_dates)
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(colors.muted.copy(alpha = 0.6f))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon("Pencil", contentDescription = label, tint = colors.mutedForeground, size = 16.dp)
    }
}

/** A reading without dates: "Add dates", in the accent. */
@Composable
private fun AddDatesButton(onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    Row(
        Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(colors.accentTint)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon("Plus", contentDescription = null, tint = colors.primary, size = 14.dp)
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.history_add_dates), style = MaterialTheme.typography.labelMedium, color = colors.primary, maxLines = 1)
    }
}

/** The reading a row shows, for the dates sheet (the sheet fetches the book's readings again). */
internal fun HistoryEntry.attempt(): ReadingAttempt = ReadingAttempt(
    id = attemptId,
    bookId = bookId,
    startedOn = started?.toString(),
    endedOn = ended?.toString(),
    outcome = when (outcome) {
        HistoryOutcome.FINISHED -> AttemptOutcome.COMPLETED
        HistoryOutcome.SKIMMED -> AttemptOutcome.SKIMMED
        HistoryOutcome.GAVE_UP -> AttemptOutcome.ABANDONED
        HistoryOutcome.READING, HistoryOutcome.ON_HOLD -> null
    },
)

@Composable
private fun Pill(text: String, background: androidx.compose.ui.graphics.Color, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        modifier = Modifier.background(background, CircleShape).padding(horizontal = 8.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold),
        color = color,
        maxLines = 1,
    )
}

/** The user's rating as five small stars, the given ones in the warning (amber) colour. */
@Composable
private fun Stars(rating: Int) {
    val colors = OttershelfTheme.colors
    val description = stringResource(R.string.history_rating, rating)
    Text(
        buildString { repeat(5) { append(if (it < rating) '★' else '☆') } },
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 15.sp, letterSpacing = 0.5.sp),
        color = colors.warning,
        // For TalkBack: "Rated 4 of 5" rather than the star characters.
        modifier = Modifier.padding(start = 2.dp).clearAndSetSemantics { contentDescription = description },
        maxLines = 1,
    )
}

private fun statusOf(entry: HistoryEntry): ReadStatus = when (entry.outcome) {
    HistoryOutcome.READING -> if (entry.isReread) ReadStatus.REREADING else ReadStatus.READING
    HistoryOutcome.ON_HOLD -> ReadStatus.ON_HOLD
    HistoryOutcome.FINISHED -> ReadStatus.READ
    HistoryOutcome.SKIMMED -> ReadStatus.SKIMMED
    HistoryOutcome.GAVE_UP -> ReadStatus.ABANDONED
}

/** "Started 3 Sept · Finished 21 Sept", "Reading since 3 Sept", "Gave up 12 Aug"... */
@Composable
private fun datesLine(entry: HistoryEntry, year: Int): String {
    val locale = Locale.getDefault()
    val formats = remember(locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMM"), locale) to
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMMy"), locale)
    }
    fun day(date: LocalDate) = date.format(if (date.year == year) formats.first else formats.second)
    val started = entry.started?.let { stringResource(R.string.history_started, day(it)) }
    return when (entry.outcome) {
        HistoryOutcome.READING -> entry.started?.let { stringResource(R.string.history_reading_since, day(it)) } ?: stringResource(R.string.history_reading_no_date)
        HistoryOutcome.ON_HOLD -> entry.started?.let { stringResource(R.string.history_on_hold_since, day(it)) } ?: stringResource(R.string.history_on_hold_no_date)
        else -> {
            val end = entry.ended?.let { d ->
                when (entry.outcome) {
                    HistoryOutcome.SKIMMED -> stringResource(R.string.history_skimmed, day(d))
                    HistoryOutcome.GAVE_UP -> stringResource(R.string.history_gave_up, day(d))
                    else -> stringResource(R.string.history_finished, day(d))
                }
            } ?: when (entry.outcome) {
                HistoryOutcome.SKIMMED -> stringResource(R.string.history_skimmed_no_date)
                HistoryOutcome.GAVE_UP -> stringResource(R.string.history_gave_up_no_date)
                else -> stringResource(R.string.history_finished_no_date)
            }
            if (started != null) stringResource(R.string.history_dates, started, end) else end
        }
    }
}
