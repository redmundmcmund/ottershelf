package io.github.ottershelf.feature.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ottershelf.R
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.tracking.AttemptOutcome
import io.github.ottershelf.core.tracking.BookSession
import io.github.ottershelf.core.tracking.PageMath
import io.github.ottershelf.core.util.IsoTime
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The dialogs the tracking part of the page opens. Sessions are referred to by id (looked up in
 * the loaded sessions), so an open dialog survives the activity being recreated or the process
 * being killed ([TrackingDialogSaver]).
 */
sealed interface TrackingDialog {
    data object PageTotal : TrackingDialog
    data object CurrentPage : TrackingDialog
    data object LogSession : TrackingDialog
    data object Finish : TrackingDialog
    data object PastRead : TrackingDialog
    data class SessionMenu(val sessionId: Long) : TrackingDialog
    data class DeleteSession(val sessionId: Long) : TrackingDialog
    data class MoveSession(val sessionId: Long) : TrackingDialog
}

/** Saves an open [TrackingDialog] as a short string (`"MoveSession:42"`). */
internal val TrackingDialogSaver: Saver<TrackingDialog?, String> = Saver(
    save = { dialog ->
        when (dialog) {
            null -> null
            TrackingDialog.PageTotal -> "PageTotal"
            TrackingDialog.CurrentPage -> "CurrentPage"
            TrackingDialog.LogSession -> "LogSession"
            TrackingDialog.Finish -> "Finish"
            TrackingDialog.PastRead -> "PastRead"
            is TrackingDialog.SessionMenu -> "SessionMenu:${dialog.sessionId}"
            is TrackingDialog.DeleteSession -> "DeleteSession:${dialog.sessionId}"
            is TrackingDialog.MoveSession -> "MoveSession:${dialog.sessionId}"
        }
    },
    restore = { saved ->
        val id = saved.substringAfter(':', "").toLongOrNull()
        when (saved.substringBefore(':')) {
            "PageTotal" -> TrackingDialog.PageTotal
            "CurrentPage" -> TrackingDialog.CurrentPage
            "LogSession" -> TrackingDialog.LogSession
            "Finish" -> TrackingDialog.Finish
            "PastRead" -> TrackingDialog.PastRead
            "SessionMenu" -> id?.let(TrackingDialog::SessionMenu)
            "DeleteSession" -> id?.let(TrackingDialog::DeleteSession)
            "MoveSession" -> id?.let(TrackingDialog::MoveSession)
            else -> null
        }
    },
)

private val LocalDateSaver = Saver<LocalDate, Long>(save = { it.toEpochDay() }, restore = LocalDate::ofEpochDay)
private val LocalTimeSaver = Saver<LocalTime, Int>(save = { it.toSecondOfDay() }, restore = { LocalTime.ofSecondOfDay(it.toLong()) })

/** Shows [dialog] (if any); [onDialog] opens the next one or closes (null). */
@Composable
internal fun TrackingDialogs(
    dialog: TrackingDialog?,
    book: BookDetail,
    status: String?,
    tracking: BookTrackingUiState,
    actions: BookTrackingActions,
    onDialog: (TrackingDialog?) -> Unit,
) {
    val close = { onDialog(null) }
    val zone = tracking.zone
    val progress = progressOf(book, status, tracking, LocalDate.now(zone))
    // A session dialog restored before the sessions have loaded (again) waits for them.
    fun session(id: Long): BookSession? = tracking.sessions.firstOrNull { it.id == id }
    when (dialog) {
        null -> Unit
        TrackingDialog.PageTotal -> NumberDialog(
            title = stringResource(R.string.book_page_total_title),
            message = progress.serverPageCount?.let { stringResource(R.string.book_page_total_message, it) }
                ?: stringResource(R.string.book_page_total_message_none),
            initial = progress.total,
            max = MAX_PAGES,
            onDismiss = close,
            onSave = {
                actions.setPageTotal(it)
                close()
            },
            extra = if (progress.totalIsOwn) {
                { TextButton(onClick = { actions.setPageTotal(null); close() }) { Text(stringResource(R.string.book_page_total_reset)) } }
            } else {
                null
            },
        )
        TrackingDialog.CurrentPage -> NumberDialog(
            title = stringResource(R.string.book_current_page_title),
            message = stringResource(R.string.book_current_page_message),
            initial = progress.currentPage,
            max = progress.total ?: MAX_PAGES,
            onDismiss = close,
            onSave = {
                actions.setCurrentPage(it)
                close()
            },
        )
        TrackingDialog.LogSession -> LogSessionDialog(
            total = progress.total,
            zone = zone,
            onDismiss = close,
            onSave = { start, minutes, percent ->
                actions.logSession(start, minutes, percent)
                close()
            },
        )
        TrackingDialog.Finish -> AlertDialog(
            onDismissRequest = close,
            containerColor = OttershelfTheme.colors.popover,
            title = { Text(stringResource(R.string.book_finish_question)) },
            text = { Text(stringResource(R.string.book_finish_message, book.title ?: stringResource(R.string.book_untitled))) },
            confirmButton = {
                TextButton(onClick = {
                    close()
                    actions.markFinished()
                }) { Text(stringResource(R.string.book_mark_finished)) }
            },
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.book_cancel)) } },
        )
        TrackingDialog.PastRead -> PastReadDialog(
            today = LocalDate.now(zone),
            onDismiss = close,
            onSave = { start, end, outcome ->
                actions.addPastRead(start, end, outcome)
                close()
            },
        )
        is TrackingDialog.SessionMenu -> session(dialog.sessionId)?.let { session ->
            SessionMenuDialog(
                session = session,
                zone = zone,
                onDismiss = close,
                onMove = { onDialog(TrackingDialog.MoveSession(session.id)) },
                onDelete = { onDialog(TrackingDialog.DeleteSession(session.id)) },
            )
        }
        is TrackingDialog.DeleteSession -> session(dialog.sessionId)?.let { session ->
            AlertDialog(
                onDismissRequest = close,
                containerColor = OttershelfTheme.colors.popover,
                title = { Text(stringResource(R.string.book_session_delete_question)) },
                text = {
                    Text(stringResource(R.string.book_session_delete_message, durationText(session.durationSeconds.toLong()), sessionWhen(session, zone)))
                },
                confirmButton = {
                    TextButton(onClick = {
                        close()
                        actions.deleteSession(session)
                    }) { Text(stringResource(R.string.book_delete), color = OttershelfTheme.colors.destructive) }
                },
                dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.book_cancel)) } },
            )
        }
        is TrackingDialog.MoveSession -> session(dialog.sessionId)?.let { session ->
            MoveSessionDialog(
                session = session,
                zone = zone,
                onDismiss = close,
                onSave = { start ->
                    actions.moveSession(session, start)
                    close()
                },
            )
        }
    }
}

private const val MAX_PAGES = 99_999

/** A dialog with one whole-number field (1..[max]). */
@Composable
private fun NumberDialog(
    title: String,
    message: String,
    initial: Int?,
    max: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    var text by rememberSaveable { mutableStateOf(initial?.toString().orEmpty()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in 0..max
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OttershelfTheme.colors.popover,
        title = { Text(title) },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = OttershelfTheme.colors.mutedForeground)
                NumberField(text, { text = it }, Modifier.padding(top = 12.dp).fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { value?.let(onSave) }, enabled = valid) { Text(stringResource(R.string.book_save)) } },
        dismissButton = {
            Row {
                extra?.invoke()
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.book_cancel)) }
            }
        },
    )
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, label: String? = null, error: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter(Char::isDigit).take(5)) },
        modifier = modifier,
        label = label?.let { { Text(it) } },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        shape = RoundedCornerShape(OttershelfTheme.radii.md),
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

/**
 * Log a session by hand: date, start time (in [zone], the account's, as the log shows them),
 * minutes and (optionally) the page reached.
 */
@Composable
private fun LogSessionDialog(total: Int?, zone: ZoneId, onDismiss: () -> Unit, onSave: (startMs: Long, minutes: Int, percent: Double?) -> Unit) {
    var minutesText by rememberSaveable { mutableStateOf("30") }
    val minutes = minutesText.toIntOrNull()
    // Half an hour ago, date and time from the same moment (just after midnight that is yesterday).
    val defaultStart = remember { LocalDateTime.now(zone).minusMinutes(30).withSecond(0).withNano(0) }
    var date by rememberSaveable(stateSaver = LocalDateSaver) { mutableStateOf(defaultStart.toLocalDate()) }
    var time by rememberSaveable(stateSaver = LocalTimeSaver) { mutableStateOf(defaultStart.toLocalTime()) }
    var pageText by rememberSaveable { mutableStateOf("") }
    val page = pageText.toIntOrNull()
    val startMs = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
    val minutesError = if (minutes == null || minutes !in 1..1440) stringResource(R.string.book_log_minutes_invalid) else null
    val futureError = if (startMs > System.currentTimeMillis()) stringResource(R.string.book_log_future) else null
    val limit = total ?: 100
    val pageError = if (page != null && page > limit) {
        if (total != null) stringResource(R.string.book_log_page_invalid, total) else stringResource(R.string.book_log_percent_invalid)
    } else {
        null
    }
    var picking by rememberSaveable { mutableStateOf<Picker?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OttershelfTheme.colors.popover,
        title = { Text(stringResource(R.string.book_log_session)) },
        text = {
            // Scrolls: in landscape, or with the keyboard up, the fields would be cut off.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldButton("CalendarDays", mediumDate(date)) { picking = Picker.Date }
                FieldButton("Clock", time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))) { picking = Picker.Time }
                futureError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = OttershelfTheme.colors.destructive) }
                NumberField(minutesText, { minutesText = it }, Modifier.fillMaxWidth(), stringResource(R.string.book_field_minutes), minutesError)
                NumberField(
                    pageText,
                    { pageText = it },
                    Modifier.fillMaxWidth(),
                    if (total != null) stringResource(R.string.book_field_page, total) else stringResource(R.string.book_field_percent),
                    pageError,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val percent = page?.let { if (total != null) PageMath.pageToPercent(it, total) else it.toDouble() }
                    onSave(startMs, minutes ?: return@TextButton, percent)
                },
                enabled = minutesError == null && futureError == null && pageError == null,
            ) { Text(stringResource(R.string.book_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.book_cancel)) } },
    )
    when (picking) {
        Picker.Date -> DatePick(date, LocalDate.now(zone), onDismiss = { picking = null }) {
            date = it
            picking = null
        }
        Picker.Time -> TimePick(time, onDismiss = { picking = null }) {
            time = it
            picking = null
        }
        else -> Unit
    }
}

private enum class Picker { Date, Time, Start, End }

/** Move a session: a new date and start time (in [zone]); it keeps its length. */
@Composable
private fun MoveSessionDialog(session: BookSession, zone: ZoneId, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    val start = remember(session, zone) {
        LocalDateTime.ofInstant(Instant.ofEpochMilli(IsoTime.parse(session.startedAt) ?: System.currentTimeMillis()), zone)
    }
    var date by rememberSaveable(stateSaver = LocalDateSaver) { mutableStateOf(start.toLocalDate()) }
    var time by rememberSaveable(stateSaver = LocalTimeSaver) { mutableStateOf(start.toLocalTime().withSecond(0).withNano(0)) }
    var picking by rememberSaveable { mutableStateOf<Picker?>(null) }
    val startMs = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
    val future = startMs + session.durationSeconds * 1000L > System.currentTimeMillis()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OttershelfTheme.colors.popover,
        title = { Text(stringResource(R.string.book_move_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.book_move_message, durationText(session.durationSeconds.toLong())),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OttershelfTheme.colors.mutedForeground,
                )
                FieldButton("CalendarDays", mediumDate(date)) { picking = Picker.Date }
                FieldButton("Clock", time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))) { picking = Picker.Time }
                if (future) Text(stringResource(R.string.book_session_future), style = MaterialTheme.typography.bodySmall, color = OttershelfTheme.colors.destructive)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(startMs) }, enabled = !future) { Text(stringResource(R.string.book_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.book_cancel)) } },
    )
    when (picking) {
        Picker.Date -> DatePick(date, LocalDate.now(zone), onDismiss = { picking = null }) {
            date = it
            picking = null
        }
        Picker.Time -> TimePick(time, onDismiss = { picking = null }) {
            time = it
            picking = null
        }
        else -> Unit
    }
}

/** A past reading: when it started (optional) and ended (up to [today]), and how. */
@Composable
private fun PastReadDialog(today: LocalDate, onDismiss: () -> Unit, onSave: (LocalDate?, LocalDate, String) -> Unit) {
    var startDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val start = startDay?.let(LocalDate::ofEpochDay)
    var end by rememberSaveable(stateSaver = LocalDateSaver) { mutableStateOf(today) }
    var outcome by rememberSaveable { mutableStateOf(AttemptOutcome.COMPLETED) }
    var picking by rememberSaveable { mutableStateOf<Picker?>(null) }
    val invalid = start?.isAfter(end) == true
    val colors = OttershelfTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.popover,
        title = { Text(stringResource(R.string.book_log_add_past)) },
        text = {
            // Scrolls: in landscape the outcomes would be cut off.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.book_field_started), style = MaterialTheme.typography.labelLarge, color = colors.mutedForeground)
                FieldButton("CalendarDays", start?.let(::mediumDate) ?: stringResource(R.string.book_field_not_set)) { picking = Picker.Start }
                Text(stringResource(R.string.book_field_ended), style = MaterialTheme.typography.labelLarge, color = colors.mutedForeground)
                FieldButton("CalendarCheck", mediumDate(end)) { picking = Picker.End }
                if (invalid) Text(stringResource(R.string.book_past_read_dates_invalid), style = MaterialTheme.typography.bodySmall, color = colors.destructive)
                Spacer(Modifier.height(4.dp))
                listOf(AttemptOutcome.COMPLETED, AttemptOutcome.SKIMMED, AttemptOutcome.ABANDONED).forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.RadioButton) { outcome = option }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = outcome == option,
                            onClick = { outcome = option },
                            colors = RadioButtonDefaults.colors(selectedColor = colors.primary),
                        )
                        Text(stringResource(outcomeLabel(option)), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(start, end, outcome) }, enabled = !invalid) { Text(stringResource(R.string.book_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.book_cancel)) } },
    )
    when (picking) {
        Picker.Start -> DatePick(start ?: end, today, onDismiss = { picking = null }) {
            startDay = it.toEpochDay()
            picking = null
        }
        Picker.End -> DatePick(end, today, onDismiss = { picking = null }) {
            end = it
            picking = null
        }
        else -> Unit
    }
}

/** Tap or long-press on a session: move it or delete it. */
@Composable
private fun SessionMenuDialog(session: BookSession, zone: ZoneId, onDismiss: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    val colors = OttershelfTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.popover,
        title = { Text(stringResource(R.string.book_session_menu_title, sessionWhen(session, zone))) },
        text = {
            Column {
                MenuRow("CalendarClock", stringResource(R.string.book_session_move), colors.foreground, onMove)
                MenuRow("Trash", stringResource(R.string.book_session_delete), colors.destructive, onDelete)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.book_cancel)) } },
    )
}

@Composable
private fun MenuRow(icon: String, text: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon(icon, contentDescription = null, tint = tint, size = 20.dp)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/** A field that opens a picker: the book page's DetailAction look. */
@Composable
private fun FieldButton(icon: String, text: String, onClick: () -> Unit) {
    DetailAction(
        text = text,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        leading = { LucideIcon(icon, contentDescription = null, tint = OttershelfTheme.colors.primary, size = 18.dp) },
        trailing = { LucideIcon("ChevronDown", contentDescription = null, tint = OttershelfTheme.colors.mutedForeground, size = 16.dp, fallback = null) },
    )
}

@Composable
private fun sessionWhen(session: BookSession, zone: ZoneId): String {
    val ms = IsoTime.parse(session.startedAt) ?: return ""
    val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
    return "${mediumDate(start.toLocalDate())} ${start.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}"
}

private const val DAY_MS = 86_400_000L

/** The date picker (up to [today], the account's). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePick(initial: LocalDate, today: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.toEpochDay() * DAY_MS,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis / DAY_MS <= today.toEpochDay()
            override fun isSelectableYear(year: Int) = year <= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onPick(LocalDate.ofEpochDay(it / DAY_MS)) } ?: onDismiss() }) {
                Text(stringResource(R.string.book_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.book_cancel)) } },
    ) {
        DatePicker(state = state)
    }
}

/** The time picker, in the phone's 12/24-hour format. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePick(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = is24)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OttershelfTheme.colors.popover,
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(R.string.book_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.book_cancel)) } },
    )
}
