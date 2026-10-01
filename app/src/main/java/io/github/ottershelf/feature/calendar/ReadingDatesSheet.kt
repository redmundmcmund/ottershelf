package io.github.ottershelf.feature.calendar

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ottershelf.R
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.tracking.AttemptOutcome
import io.github.ottershelf.core.tracking.ReadingAttempt
import io.github.ottershelf.feature.history.SavedReading
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.CardRow
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.FormatChip
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.components.StatusIcon
import io.github.ottershelf.ui.icons.AppIcons
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import io.github.ottershelf.ui.components.belowStatusBar

/**
 * The picker and the dates sheet of [viewModel], over the screen that owns it (Calendar, Day,
 * History): bottom sheets on the card colour, as the quote's book picker. "Scan a book" opens the
 * scanner to pick one (it hands the book back when the screen shows again). Each book saved goes
 * to [onSaved].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingDatesHost(viewModel: ReadingDatesViewModel, navigator: AppNavigator, onSaved: (SavedReading) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = OttershelfTheme.colors
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    val latestSaved by rememberUpdatedState(onSaved)
    LaunchedEffect(viewModel) { viewModel.saved.collect { latestSaved(it) } }

    val picker = state.picker
    if (picker != null && !state.scanning) {
        ModalBottomSheet(
            modifier = Modifier.belowStatusBar(),
            onDismissRequest = viewModel::closePicker,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.card,
        ) {
            BookPickerContent(
                state = picker,
                onQuery = viewModel::search,
                onPick = viewModel::pick,
                onScan = {
                    viewModel.scan()
                    navigator.navigate(Route.Scan(pick = true))
                },
                onRetry = viewModel::retrySearch,
            )
        }
    }

    val dates = state.dates
    if (dates != null) {
        val saving by rememberUpdatedState(dates.saving)
        ModalBottomSheet(
            modifier = Modifier.belowStatusBar(),
            onDismissRequest = viewModel::closeDates,
            // Not while saving: the sheet stays until the save is in (or failed).
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !saving }),
            containerColor = colors.card,
        ) {
            ReadingDatesContent(
                state = dates,
                actions = DatesActions(
                    onTarget = viewModel::chooseTarget,
                    onStarted = viewModel::setStarted,
                    onEnded = viewModel::setEnded,
                    onOutcome = viewModel::setOutcome,
                    onSave = viewModel::save,
                    onCancel = viewModel::closeDates,
                    onRetry = viewModel::retryLoad,
                ),
            )
        }
    }
}

// --- the picker -------------------------------------------------------------------------------

/**
 * Pick the book: a search of the library by title or author (the quick search), what the user is
 * reading now before the user types, and "Scan a book". Each row: cover, title, author, formats and
 * the user's status.
 */
@Composable
fun BookPickerContent(
    state: PickerUiState,
    onQuery: (String) -> Unit = {},
    onPick: (PickerBook) -> Unit = {},
    onScan: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val colors = OttershelfTheme.colors
    Column(Modifier.fillMaxWidth().heightIn(min = 420.dp).padding(horizontal = 16.dp)) {
        SheetTitle(stringResource(R.string.calendar_add_book), stringResource(R.string.calendar_picker_for_day, longDay(state.day)))
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.calendar_picker_search)) },
            leadingIcon = { LucideIcon("Search", contentDescription = null, tint = colors.mutedForeground, size = 18.dp) },
            trailingIcon = if (state.query.isNotEmpty()) {
                {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = stringResource(R.string.calendar_picker_clear)) { onQuery("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        LucideIcon("X", contentDescription = stringResource(R.string.calendar_picker_clear), tint = colors.mutedForeground, size = 18.dp)
                    }
                }
            } else {
                null
            },
            singleLine = true,
            shape = RoundedCornerShape(OttershelfTheme.radii.md),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        Spacer(Modifier.height(10.dp))
        CardRow(onClick = onScan, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
            Box(Modifier.size(36.dp).background(colors.accentTint, CircleShape), contentAlignment = Alignment.Center) {
                Icon(AppIcons.ScanBarcode, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.calendar_picker_scan), style = MaterialTheme.typography.bodyLarge, color = colors.foreground)
                Text(stringResource(R.string.calendar_picker_scan_detail), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
            }
            Chevron(left = false, tint = colors.mutedForeground, size = 18.dp)
        }
        Text(
            stringResource(if (state.query.isBlank()) R.string.calendar_picker_reading_now else R.string.calendar_picker_results),
            style = MaterialTheme.typography.labelMedium,
            color = colors.mutedForeground,
            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp, start = 2.dp),
        )
        when {
            state.loading && state.books.isEmpty() -> LoadingState(Modifier.fillMaxWidth().heightIn(min = 180.dp))
            state.failed && state.books.isEmpty() ->
                ErrorState(onRetry, Modifier.fillMaxWidth(), message = stringResource(R.string.calendar_picker_failed), compact = true)
            state.books.isEmpty() -> EmptyState(
                stringResource(if (state.query.isBlank()) R.string.calendar_picker_nothing_reading else R.string.calendar_picker_no_results),
                Modifier.fillMaxWidth(),
                icon = "Search",
                compact = true,
            )
            else -> LazyColumn(
                Modifier.fillMaxWidth().imePadding(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.books, key = { it.id }) { book -> PickerRow(book, onClick = { onPick(book) }) }
            }
        }
    }
}

/** A book to pick: the scanner's chooser row (cover, title, author, formats, the user's status). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerRow(book: PickerBook, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    val authors = book.authors.joinToString(", ")
    CardRow(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
        BookCover(book.cover, book.title, Modifier.size(40.dp, 60.dp), authors = authors, seed = book.id.toString())
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                book.title ?: stringResource(R.string.calendar_untitled),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (authors.isNotEmpty()) {
                Text(authors, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (book.formats.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    book.formats.forEach { FormatChip(it) }
                }
            }
        }
        ReadStatus.of(book.status)?.takeIf { it != ReadStatus.UNREAD }?.let { status ->
            Spacer(Modifier.width(8.dp))
            Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                StatusIcon(status, size = 18.dp)
                Text(
                    status.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.mutedForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// --- the dates sheet --------------------------------------------------------------------------

/** What the dates sheet does. */
class DatesActions(
    val onTarget: (DatesTarget) -> Unit = {},
    val onStarted: (LocalDate?) -> Unit = {},
    val onEnded: (LocalDate) -> Unit = {},
    val onOutcome: (String?) -> Unit = {},
    val onSave: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

private enum class Picking { Start, End }

/**
 * The dates: the book; its readings, when it has some, to pick the one these dates are for (or
 * another reading); how it went; the start (optional) and the end, up to today in the user's zone; what
 * the save does to the user's status when it changes it; Cancel and Save.
 */
@Composable
fun ReadingDatesContent(state: DatesUiState, actions: DatesActions = DatesActions()) {
    val colors = OttershelfTheme.colors
    var picking by rememberSaveable { mutableStateOf<Picking?>(null) }
    val form = state.form
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val editing = state.attempts.firstOrNull { (state.target as? DatesTarget.Existing)?.attemptId == it.id }
        SheetTitle(
            when {
                !state.fixed -> stringResource(R.string.calendar_dates_add_title)
                editing != null && editing.outcome != null && ReadingDates.date(editing.endedOn) == null ->
                    stringResource(R.string.calendar_dates_add_dates_title)
                else -> stringResource(R.string.calendar_dates_edit_title)
            },
            subtitle = null,
        )
        BookStrip(state.book)

        if (state.loading && !state.fixed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallProgress()
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.calendar_dates_loading), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
            }
        } else if (state.loadFailed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.calendar_dates_load_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.destructive,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = actions.onRetry) { Text(stringResource(R.string.calendar_retry)) }
            }
        } else if (!state.fixed && state.attempts.isNotEmpty()) {
            Readings(state, actions.onTarget)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel(stringResource(R.string.calendar_dates_how))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.outcomes.forEach { outcome ->
                    OptionChip(
                        text = stringResource(outcomeName(outcome)),
                        selected = form.outcome == outcome,
                        onClick = { actions.onOutcome(outcome) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel(stringResource(R.string.calendar_dates_started_optional))
            DateField(
                icon = "CalendarDays",
                text = form.started?.let { mediumDay(it) } ?: stringResource(R.string.calendar_dates_not_set),
                dim = form.started == null,
                onClick = { picking = Picking.Start },
                onClear = if (form.started != null) ({ actions.onStarted(null) }) else null,
            )
        }
        val outcome = form.outcome
        if (outcome != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel(stringResource(endLabel(outcome)))
                DateField(icon = "CalendarCheck", text = mediumDay(form.ended), onClick = { picking = Picking.End })
            }
        }

        state.problem?.let { problem ->
            Text(stringResource(problemText(problem)), style = MaterialTheme.typography.bodySmall, color = colors.destructive)
        }
        if (state.targetGone) {
            Text(stringResource(R.string.calendar_dates_reading_gone), style = MaterialTheme.typography.bodySmall, color = colors.destructive)
        }
        state.statusChange?.let { status ->
            InfoNote(stringResource(R.string.calendar_dates_status_change, ReadStatus.of(status)?.label ?: status))
        }
        if (state.staysWanted) InfoNote(stringResource(R.string.calendar_dates_stays_wanted))
        state.error?.let { error ->
            Text(
                if (error.isBlank()) stringResource(R.string.calendar_dates_save_failed) else stringResource(R.string.calendar_dates_save_failed_because, error),
                style = MaterialTheme.typography.bodySmall,
                color = colors.destructive,
            )
        }

        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.calendar_cancel), onClick = actions.onCancel, modifier = Modifier.weight(1f), enabled = !state.saving)
            AccentButton(
                stringResource(if (state.saving) R.string.calendar_dates_saving else R.string.calendar_save),
                onClick = actions.onSave,
                modifier = Modifier.weight(1f),
                icon = if (state.saving) null else "Check",
                enabled = state.canSave,
            )
        }
    }

    when (picking) {
        Picking.Start -> DayPick(form.started ?: form.ended, state.today, onDismiss = { picking = null }) {
            actions.onStarted(it)
            picking = null
        }
        Picking.End -> DayPick(form.ended, state.today, onDismiss = { picking = null }) {
            actions.onEnded(it)
            picking = null
        }
        null -> Unit
    }
}

@Composable
private fun SheetTitle(title: String, subtitle: String?) {
    val colors = OttershelfTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            color = colors.foreground,
        )
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
    }
}

/** The book the dates are for: cover, title, author and the user's status. */
@Composable
private fun BookStrip(book: PickerBook) {
    val colors = OttershelfTheme.colors
    val authors = book.authors.joinToString(", ")
    Row(verticalAlignment = Alignment.CenterVertically) {
        BookCover(book.cover, book.title, Modifier.size(44.dp, 66.dp), authors = authors, seed = book.id.toString())
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                book.title ?: stringResource(R.string.calendar_untitled),
                style = MaterialTheme.typography.titleMedium,
                color = colors.foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (authors.isNotEmpty()) {
                Text(authors, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ReadStatus.of(book.status)?.takeIf { it != ReadStatus.UNREAD }?.let { status ->
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusIcon(status, size = 14.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(status.label, style = MaterialTheme.typography.labelMedium, color = colors.mutedForeground)
                }
            }
        }
    }
}

/** The book's readings, to pick the one the dates are for, and "Add another reading". */
@Composable
private fun Readings(state: DatesUiState, onTarget: (DatesTarget) -> Unit) {
    val colors = OttershelfTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(stringResource(R.string.calendar_dates_readings))
        Text(stringResource(R.string.calendar_dates_readings_hint), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
        Spacer(Modifier.height(2.dp))
        ReadingDates.ordered(state.attempts).forEach { attempt ->
            ChoiceRow(
                title = readingTitle(attempt),
                detail = readingDetail(attempt),
                selected = state.target == DatesTarget.Existing(attempt.id),
                onClick = { onTarget(DatesTarget.Existing(attempt.id)) },
            )
        }
        ChoiceRow(
            title = stringResource(R.string.calendar_dates_new),
            detail = stringResource(
                if (state.attempts.any { it.outcome == AttemptOutcome.COMPLETED }) R.string.calendar_dates_new_reread else R.string.calendar_dates_new_first,
            ),
            selected = state.target == DatesTarget.New,
            onClick = { onTarget(DatesTarget.New) },
            icon = "Plus",
        )
    }
}

/** A reading as the sheet names it: "Finished 3 Sept 2024", "Reading now", "Finished, no date". */
@Composable
private fun readingTitle(attempt: ReadingAttempt): String {
    val end = ReadingDates.date(attempt.endedOn)?.let { mediumDay(it) }
    return when (attempt.outcome) {
        null -> stringResource(R.string.calendar_dates_reading_now)
        AttemptOutcome.ABANDONED -> end?.let { stringResource(R.string.calendar_dates_gave_up_on, it) } ?: stringResource(R.string.calendar_dates_gave_up_no_date)
        AttemptOutcome.SKIMMED -> end?.let { stringResource(R.string.calendar_dates_skimmed_on, it) } ?: stringResource(R.string.calendar_dates_skimmed_no_date)
        else -> end?.let { stringResource(R.string.calendar_dates_finished_on, it) } ?: stringResource(R.string.calendar_dates_finished_no_date)
    }
}

/** "Started 1 Aug 2024 · 12 sessions", "No start date". */
@Composable
private fun readingDetail(attempt: ReadingAttempt): String {
    val start = ReadingDates.date(attempt.startedOn)?.let { stringResource(R.string.calendar_dates_started_on, mediumDay(it)) }
        ?: stringResource(R.string.calendar_dates_no_start)
    if (attempt.totalSessions <= 0) return start
    return stringResource(R.string.calendar_dates_detail, start, pluralStringResource(R.plurals.calendar_sessions, attempt.totalSessions, attempt.totalSessions))
}

/** A radio row in the app's card-row look: accent tint and edge when chosen. */
@Composable
private fun ChoiceRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit, icon: String? = null) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.lg)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.accentTint else colors.cardRow)
            .border(1.dp, if (selected) colors.primary.copy(alpha = 0.5f) else Color.Transparent, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(start = 2.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = colors.primary, unselectedColor = colors.mutedForeground))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (selected) colors.primary else colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (icon != null) LucideIcon(icon, contentDescription = null, tint = if (selected) colors.primary else colors.mutedForeground, size = 18.dp)
    }
}

/** A choice among a few (History's chip look: 36dp, card or accent tint). */
@Composable
private fun OptionChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.lg)
    Box(
        modifier
            .height(38.dp)
            .clip(shape)
            .background(if (selected) colors.accentTint else colors.card)
            .border(1.dp, if (selected) colors.primary.copy(alpha = 0.5f) else colors.border, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.primary else colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** What the save does beyond the dates: an info icon and a muted line. */
@Composable
private fun InfoNote(text: String) {
    val colors = OttershelfTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        LucideIcon("Info", contentDescription = null, tint = colors.mutedForeground, size = 15.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = OttershelfTheme.colors.mutedForeground)
}

/**
 * A field that opens the date picker, in the book page's DetailAction look (48dp, card fill, a
 * 1dp border, the md radius, an accent icon). [onClear]: a clear button at the end.
 */
@Composable
private fun DateField(icon: String, text: String, onClick: () -> Unit, dim: Boolean = false, onClear: (() -> Unit)? = null) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.md)
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.border, shape)
            .clickable(role = Role.Button, indication = ripple(color = colors.primary), interactionSource = null, onClick = onClick)
            .padding(start = 14.dp, end = if (onClear != null) 4.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon(icon, contentDescription = null, tint = colors.primary, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.sp),
            color = if (dim) colors.mutedForeground else colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onClear != null) {
            val label = stringResource(R.string.calendar_dates_clear_start)
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon("X", contentDescription = label, tint = colors.mutedForeground, size = 18.dp)
            }
        } else {
            LucideIcon("ChevronDown", contentDescription = null, tint = colors.mutedForeground, size = 16.dp, fallback = null)
        }
    }
}

@Composable
private fun SmallProgress() {
    val colors = OttershelfTheme.colors
    if (LocalInspectionMode.current) {
        CircularProgressIndicator(progress = { 0.3f }, modifier = Modifier.size(16.dp), color = colors.primary, strokeWidth = 2.dp)
    } else {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = colors.primary, strokeWidth = 2.dp)
    }
}

private const val DAY_MS = 86_400_000L

/** The date picker, up to [today] (the user's), as the book page's. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPick(initial: LocalDate, today: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = minOf(initial, today).toEpochDay() * DAY_MS,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis / DAY_MS <= today.toEpochDay()
            override fun isSelectableYear(year: Int) = year <= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onPick(LocalDate.ofEpochDay(it / DAY_MS)) } ?: onDismiss() }) {
                Text(stringResource(R.string.calendar_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.calendar_cancel)) } },
    ) {
        DatePicker(state = state)
    }
}

private fun outcomeName(outcome: String?): Int = when (outcome) {
    null -> R.string.calendar_dates_outcome_reading
    AttemptOutcome.ABANDONED -> R.string.calendar_dates_outcome_gave_up
    AttemptOutcome.SKIMMED -> R.string.calendar_dates_outcome_skimmed
    else -> R.string.calendar_dates_outcome_finished
}

private fun endLabel(outcome: String): Int = when (outcome) {
    AttemptOutcome.ABANDONED -> R.string.calendar_dates_end_gave_up
    AttemptOutcome.SKIMMED -> R.string.calendar_dates_end_skimmed
    else -> R.string.calendar_dates_end_finished
}

private fun problemText(problem: DatesProblem): Int = when (problem) {
    DatesProblem.START_IN_FUTURE -> R.string.calendar_dates_start_future
    DatesProblem.END_IN_FUTURE -> R.string.calendar_dates_end_future
    DatesProblem.START_AFTER_END -> R.string.calendar_dates_start_after_end
}

/** "3 Sept 2024" (the phone's order and month names). */
@Composable
internal fun mediumDay(date: LocalDate): String {
    val locale = Locale.getDefault()
    val format = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMMy"), locale) }
    return date.format(format)
}

/** "Thursday 3 September 2026". */
@Composable
internal fun longDay(date: LocalDate): String {
    val locale = Locale.getDefault()
    val format = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMMy"), locale) }
    return date.format(format)
}
