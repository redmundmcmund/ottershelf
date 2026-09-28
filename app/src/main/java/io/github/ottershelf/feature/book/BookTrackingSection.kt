package io.github.ottershelf.feature.book

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import io.github.ottershelf.R
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.tracking.AttemptOutcome
import io.github.ottershelf.core.tracking.PageMath
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.CardTitle
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.components.PillProgressBar
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The tracking part of the book page, under the details: the progress card (Bookmory's
 * current-book card), rating and private review, and the reading log. Dialogs are opened through
 * [onDialog]; the page hosts them ([TrackingDialogs]).
 */
@Composable
internal fun TrackingProgressCard(
    book: BookDetail,
    status: String?,
    tracking: BookTrackingUiState,
    actions: BookTrackingActions,
    onDialog: (TrackingDialog) -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    val colors = OttershelfTheme.colors
    val p = progressOf(book, status, tracking, today)
    DashCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val headline = when {
                p.dayNumber != null -> stringResource(R.string.book_day_n, p.dayNumber)
                p.finished -> stringResource(R.string.book_finished)
                p.active -> stringResource(R.string.book_reading_now)
                else -> stringResource(R.string.book_not_started)
            }
            Text(
                text = headline,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                color = colors.foreground,
            )
            if (p.active || p.finished) {
                val label = if (p.readingNumber <= 1) stringResource(R.string.book_first_reading) else stringResource(R.string.book_reread_n, p.readingNumber)
                Pill(label, icon = if (p.readingNumber <= 1) "BookOpen" else "RotateCcw")
            }
        }
        val dates = listOfNotNull(
            p.startedOn?.takeIf { p.active || p.finished }?.let { stringResource(R.string.book_started_on, mediumDate(it)) },
            p.finishedOn?.let { stringResource(R.string.book_finished_on, mediumDate(it)) },
        ).joinToString("  ·  ")
        if (dates.isNotEmpty()) {
            Text(dates, modifier = Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = colors.mutedForeground)
        }

        // "p. 130 / 464": the total opens its dialog, the pencil sets the page by hand.
        Row(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                Text(
                    text = p.currentPage?.let { stringResource(R.string.book_page_n, it) } ?: stringResource(R.string.book_page_unknown),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                )
                Text(
                    text = " / ",
                    modifier = Modifier.padding(bottom = 3.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.mutedForeground,
                )
                Text(
                    text = p.total?.toString() ?: stringResource(R.string.book_set_total),
                    modifier = Modifier
                        .padding(bottom = 3.dp)
                        .clip(RoundedCornerShape(OttershelfTheme.radii.sm))
                        .clickable(role = Role.Button) { onDialog(TrackingDialog.PageTotal) }
                        .padding(horizontal = 2.dp),
                    style = MaterialTheme.typography.titleMedium.copy(textDecoration = TextDecoration.Underline),
                    color = colors.primary,
                )
            }
            val editPage = stringResource(R.string.book_edit_page)
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onDialog(TrackingDialog.CurrentPage) }
                    .semantics { contentDescription = editPage },
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon("Pencil", contentDescription = null, tint = colors.mutedForeground, size = 18.dp)
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PillProgressBar(((p.percent ?: 0.0) / 100.0).toFloat(), Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.book_percent, (p.percent ?: 0.0).roundToInt()),
                style = MaterialTheme.typography.labelLarge,
                color = colors.mutedForeground,
            )
        }
        if (p.pageSetByHand) {
            Text(
                stringResource(R.string.book_page_by_hand),
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.mutedForeground,
            )
        }

        // Speed (in pages, so only with a page total; never %/h) and time left, once there is
        // enough reading behind them (trustedPace).
        val pagesPerHour = p.pace?.pagesPerHour?.roundToInt()?.takeIf { it > 0 }
        val timeLeft = p.timeLeftSeconds?.takeIf { it > 0 }
        if (pagesPerHour != null || timeLeft != null) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pagesPerHour?.let { Fact("Gauge", stringResource(R.string.book_pace_pages, it)) }
                timeLeft?.let { Fact("Hourglass", stringResource(R.string.book_time_left, durationText(it))) }
            }
        }

        Row(Modifier.padding(top = 16.dp).fillMaxWidth()) {
            AccentButton(
                text = stringResource(if (tracking.timerHere) R.string.book_open_timer else R.string.book_start_timer),
                onClick = actions::startTimer,
                modifier = Modifier.weight(1f),
                icon = "Timer",
            )
            Spacer(Modifier.width(8.dp))
            SecondaryButton(
                text = stringResource(R.string.book_log_session),
                onClick = { onDialog(TrackingDialog.LogSession) },
                modifier = Modifier.weight(1f),
                icon = "Plus",
            )
        }
        if (!p.finished) {
            SecondaryButton(
                text = stringResource(R.string.book_mark_finished),
                onClick = { onDialog(TrackingDialog.Finish) },
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                icon = "BookCheck",
                enabled = !tracking.busy,
            )
        }
    }
}

/** A small icon and text (speed, time left). */
@Composable
private fun Fact(icon: String, text: String) {
    val colors = OttershelfTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        LucideIcon(icon, contentDescription = null, tint = colors.primary, size = 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.foreground)
    }
}

/** A muted pill with an optional icon ("First reading"). */
@Composable
private fun Pill(text: String, icon: String? = null) {
    val colors = OttershelfTheme.colors
    Row(
        Modifier.clip(CircleShape).background(colors.muted).padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            LucideIcon(icon, contentDescription = null, tint = colors.primary, size = 14.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = colors.foreground, maxLines = 1)
    }
}

/** Under the stars when the book's rating field is metadata-locked. */
@Composable
internal fun RatingLockedNote(modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LucideIcon("Lock", contentDescription = null, tint = colors.mutedForeground, size = 14.dp, fallback = null)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.book_rating_locked), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
    }
}

/** The user's rating (with the permission) and the private review, edited in place. */
@Composable
internal fun RatingReviewCard(tracking: BookTrackingUiState, actions: BookTrackingActions, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    DashCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        if (tracking.canRate) {
            CardTitle(stringResource(R.string.book_rating_title), icon = "Star")
            // A locked rating field: the server skips the book without an error (as the web, show it disabled).
            StarRating(
                rating = tracking.rating,
                onRate = { n -> actions.setRating(if (n == tracking.rating) null else n) },
                modifier = Modifier.padding(top = 10.dp, bottom = if (tracking.ratingLocked) 0.dp else 16.dp),
                enabled = !tracking.busy && !tracking.ratingLocked,
            )
            if (tracking.ratingLocked) RatingLockedNote(Modifier.padding(top = 2.dp, bottom = 16.dp))
        }
        CardTitle(stringResource(R.string.book_review_title), icon = "NotebookPen")
        var editing by rememberSaveable { mutableStateOf(false) }
        if (editing) {
            var text by rememberSaveable { mutableStateOf(tracking.note.orEmpty()) }
            ReviewField(text, { text = it }, Modifier.padding(top = 10.dp).fillMaxWidth())
            Row(Modifier.padding(top = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.book_cancel)) }
                Spacer(Modifier.width(8.dp))
                AccentButton(
                    text = stringResource(R.string.book_save),
                    onClick = {
                        actions.saveNote(text)
                        editing = false
                    },
                )
            }
        } else {
            val note = tracking.note
            Row(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(OttershelfTheme.radii.md))
                    .clickable(role = Role.Button) { editing = true }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = note ?: stringResource(R.string.book_review_add),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                    color = if (note != null) colors.foreground else colors.mutedForeground,
                )
                Spacer(Modifier.width(8.dp))
                LucideIcon("Pencil", contentDescription = stringResource(R.string.book_edit), tint = colors.mutedForeground, size = 16.dp)
            }
            Text(stringResource(R.string.book_review_private), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
        }
    }
}

@Composable
internal fun ReviewField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(TrackingLimits.NOTE_CHARS)) },
        modifier = modifier,
        placeholder = { Text(stringResource(R.string.book_review_add)) },
        minLines = 3,
        maxLines = 10,
        shape = RoundedCornerShape(OttershelfTheme.radii.md),
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
}

internal object TrackingLimits {
    const val NOTE_CHARS = 10_000
}

/** Five stars; tapping the current rating again clears it (handled by the caller). */
@Composable
internal fun StarRating(rating: Int?, onRate: (Int) -> Unit, modifier: Modifier = Modifier, size: Dp = 34.dp, enabled: Boolean = true) {
    val colors = OttershelfTheme.colors
    val resources = LocalResources.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (n in 1..5) {
            val filled = rating != null && n <= rating
            Box(
                Modifier
                    .size(size + 6.dp)
                    .clip(CircleShape)
                    .clickable(enabled = enabled, role = Role.Button) { onRate(n) }
                    .semantics { contentDescription = resources.getQuantityString(R.plurals.book_stars, n, n) },
                contentAlignment = Alignment.Center,
            ) {
                Star(filled, if (filled) colors.starHighlight else colors.mutedForeground, Modifier.size(size))
            }
        }
    }
}

/** A five-pointed star, filled or outlined. */
@Composable
private fun Star(filled: Boolean, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f + size.height * 0.03f
        val outer = size.minDimension / 2f * 0.95f
        val inner = outer * 0.45f
        val path = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val a = Math.toRadians(-90.0 + i * 36.0)
            val point = Offset(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat())
            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        path.close()
        if (filled) drawPath(path, color) else drawPath(path, color, style = Stroke(width = 1.6.dp.toPx()))
    }
}

/**
 * The reading log (Bookmory's book timeline): readings and sessions by date, newest first, older
 * sessions a page at a time. Tap or long-press a session to move or delete it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReadingLogCard(
    book: BookDetail,
    tracking: BookTrackingUiState,
    actions: BookTrackingActions,
    onDialog: (TrackingDialog) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OttershelfTheme.colors
    val total = PageMath.pageTotal(book, tracking.settings)
    val items = remember(tracking.sessions, tracking.attempts, tracking.sessionsTotal, tracking.endReached, tracking.zone, total) { timelineOf(tracking, total) }
    DashCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle(stringResource(R.string.book_log_title), icon = "ScrollText", modifier = Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(OttershelfTheme.radii.md))
                    .clickable(role = Role.Button) { onDialog(TrackingDialog.PastRead) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LucideIcon("Plus", contentDescription = null, tint = colors.primary, size = 16.dp)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.book_log_add_past), style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        }
        val stats = tracking.stats
        if (stats.totalSessions > 0) {
            Text(
                text = pluralStringResource(R.plurals.book_log_summary, stats.totalSessions, stats.totalSessions, durationText(stats.totalSeconds)),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.mutedForeground,
            )
        }
        when {
            tracking.loading && items.isEmpty() -> LoadingState(Modifier.fillMaxWidth().padding(vertical = 16.dp))
            tracking.error != null && items.isEmpty() -> ErrorState(
                onRetry = actions::retryTracking,
                message = stringResource(R.string.book_log_failed),
                detail = tracking.error.takeIf { it.isNotBlank() },
                compact = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            items.isEmpty() -> Text(
                stringResource(R.string.book_log_empty),
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.mutedForeground,
            )
            else -> Column(Modifier.padding(top = 8.dp)) {
                var lastDate: LocalDate? = null
                items.forEach { item ->
                    if (item.date != lastDate) {
                        lastDate = item.date
                        DateHeader(item.date)
                    }
                    TimelineRow(item, onSession = { onDialog(TrackingDialog.SessionMenu(it.id)) })
                }
            }
        }
        if (!tracking.allSessionsLoaded && items.isNotEmpty()) {
            SecondaryButton(
                text = stringResource(R.string.book_log_more),
                onClick = actions::loadMoreSessions,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                icon = "ChevronDown",
                enabled = !tracking.loadingMore,
            )
        }
    }
}

private val RAIL = 28.dp

/** A day's heading, with the rail running through. */
@Composable
private fun DateHeader(date: LocalDate) {
    val colors = OttershelfTheme.colors
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Rail(Modifier.fillMaxHeight())
        Spacer(Modifier.width(10.dp))
        Text(
            text = dayLabel(date),
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun Rail(modifier: Modifier) {
    Box(modifier.width(RAIL), contentAlignment = Alignment.Center) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(OttershelfTheme.colors.border))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineRow(item: TimelineItem, onSession: (io.github.ottershelf.core.tracking.BookSession) -> Unit) {
    val colors = OttershelfTheme.colors
    val session = item as? TimelineItem.Session
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(OttershelfTheme.radii.md))
            .then(
                if (session != null) {
                    Modifier.combinedClickable(
                        onClick = { onSession(session.session) },
                        onLongClick = { onSession(session.session) },
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Box(Modifier.width(RAIL).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Rail(Modifier.fillMaxHeight())
            when (item) {
                is TimelineItem.Session -> Box(Modifier.size(10.dp).clip(CircleShape).background(colors.primary))
                is TimelineItem.Started -> Marker("Play", colors.primary)
                is TimelineItem.Ended -> when (item.outcome) {
                    AttemptOutcome.COMPLETED -> Marker("BookCheck", colors.success)
                    AttemptOutcome.SKIMMED -> Marker("ScanLine", colors.info)
                    else -> Marker("BookX", colors.destructive)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            when (item) {
                is TimelineItem.Session -> {
                    val s = item.session
                    Text(
                        text = "${timeLabel(item)}  ·  ${durationText(s.durationSeconds.toLong())}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.foreground,
                    )
                    val details = listOfNotNull(
                        item.endPage?.let { stringResource(R.string.book_log_to_page, it) },
                        sourceLabel(s.source),
                    ).joinToString("  ·  ")
                    if (details.isNotEmpty()) {
                        Text(details, style = MaterialTheme.typography.bodyMedium, color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                is TimelineItem.Started -> Text(stringResource(R.string.book_log_started), style = MaterialTheme.typography.bodyLarge, color = colors.foreground)
                is TimelineItem.Ended -> Text(stringResource(outcomeLabel(item.outcome)), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = colors.foreground)
            }
        }
        if (session != null) {
            val gain = when {
                session.pages != null && session.pages != 0 -> pluralStringResource(R.plurals.book_log_pages, kotlin.math.abs(session.pages), signed(session.pages))
                session.session.progressDelta != null && session.session.progressDelta != 0.0 ->
                    stringResource(R.string.book_log_percent, String.format(Locale.getDefault(), "%+.1f", session.session.progressDelta))
                else -> null
            }
            if (gain != null) {
                Text(
                    text = gain,
                    modifier = Modifier.align(Alignment.CenterVertically).padding(start = 8.dp, end = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                )
            }
        }
    }
}

private fun signed(n: Int) = if (n > 0) "+$n" else n.toString()

@Composable
private fun Marker(icon: String, tint: Color) {
    val colors = OttershelfTheme.colors
    Box(
        Modifier.size(24.dp).clip(CircleShape).background(colors.card),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            LucideIcon(icon, contentDescription = null, tint = tint, size = 13.dp)
        }
    }
}

@Composable
private fun sourceLabel(source: String?): String? = when (source) {
    null -> null
    "manual" -> stringResource(R.string.book_source_manual)
    "android" -> stringResource(R.string.book_source_android)
    "web" -> stringResource(R.string.book_source_web)
    "koreader" -> "KOReader"
    "kobo" -> "Kobo"
    "ios" -> "iOS"
    else -> source.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

/** "45 min", "1 h 5 min", "< 1 min". */
@Composable
internal fun durationText(seconds: Long): String {
    val minutes = (seconds + 30) / 60
    return when {
        seconds < 60 -> stringResource(R.string.book_duration_under_min)
        minutes < 60 -> stringResource(R.string.book_duration_min, minutes)
        minutes % 60 == 0L -> stringResource(R.string.book_duration_h, minutes / 60)
        else -> stringResource(R.string.book_duration_h_min, minutes / 60, minutes % 60)
    }
}

internal fun mediumDate(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

private fun dayLabel(date: LocalDate): String {
    val pattern = if (date.year == LocalDate.now().year) "EEE d MMM" else "EEE d MMM yyyy"
    return date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault())).uppercase(Locale.getDefault())
}

private fun timeLabel(item: TimelineItem.Session): String =
    item.start.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
