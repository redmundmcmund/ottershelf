package io.github.ottershelf.feature.timer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.ottershelf.AppContainer
import io.github.ottershelf.R
import io.github.ottershelf.core.tracking.ActiveTimer
import io.github.ottershelf.core.tracking.FinishedTimer
import io.github.ottershelf.core.tracking.TimerNotifications
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme

/** The running timer for screens outside the timer (the Dashboard's bar, the reader's notice). */
class RunningTimerViewModel(private val container: AppContainer) : ViewModel() {

    /** The timer that's on (running or paused), whichever book it is for. */
    val active: StateFlow<ActiveTimer?> = container.timer.state
        .map { it.active }
        .stateIn(viewModelScope, SharingStarted.Eagerly, container.timer.state.value.active)

    /** A stopped timer still waiting to be saved (with something to save), whichever book. */
    val unsaved: StateFlow<FinishedTimer?> = container.timer.state
        .map { it.finished?.takeUnless { f -> f.tooShort } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, container.timer.state.value.finished?.takeUnless { it.tooShort })

    /** The time, every second, while something shows the clock. */
    val now: StateFlow<Long> = container.timer.ticks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), System.currentTimeMillis())

    /**
     * Stops [bookId]'s timer as of [atMs] and saves its time without a page (the reader records
     * its own session from then on).
     */
    fun stopWithoutPage(bookId: Long, atMs: Long) {
        container.appScope.launch { TimerSessions(container).stopWithoutPage(bookId, atMs) }
    }
}

/**
 * The Dashboard's timer bars: the running timer's, and a stopped session still waiting to be
 * saved (its notification may be long gone); nothing when there is neither.
 */
@Composable
fun TimerBars(
    running: ActiveTimer?,
    unsaved: FinishedTimer?,
    now: () -> Long,
    onTimer: (bookId: Long) -> Unit,
    onUnsaved: (FinishedTimer) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        running?.let { RunningTimerBar(it, now(), onClick = { onTimer(it.bookId) }) }
        unsaved?.let { UnsavedSessionBar(it, onClick = { onUnsaved(it) }) }
    }
}

/** "Save your session: 25:10" and the title; tapping it opens the save form. */
@Composable
fun UnsavedSessionBar(timer: FinishedTimer, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.xl)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.accentTint)
            .border(1.dp, colors.dashCardBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon("Timer", contentDescription = null, tint = colors.primary, size = 18.dp)
        Text(
            stringResource(R.string.timer_bar_unsaved, TimerNotifications.clock(timer.activeMs)),
            style = MaterialTheme.typography.titleSmall,
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        Text(
            timer.title.ifBlank { stringResource(R.string.core_timer_untitled) },
            style = MaterialTheme.typography.bodySmall,
            color = colors.mutedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp),
        )
        LucideIcon("ChevronRight", contentDescription = null, tint = colors.mutedForeground, size = 16.dp, modifier = Modifier.padding(start = 4.dp))
    }
}

/**
 * The Dashboard's slim bar while a timer is on: "Reading <title>" and the clock (remaining for a
 * countdown); tapping it opens the timer.
 */
@Composable
fun RunningTimerBar(timer: ActiveTimer, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.xl)
    val title = timer.title.ifBlank { stringResource(R.string.core_timer_untitled) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.accentTint)
            .border(1.dp, colors.dashCardBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon(if (timer.paused) "Pause" else "Timer", contentDescription = null, tint = colors.primary, size = 18.dp)
        Text(
            stringResource(if (timer.paused) R.string.timer_bar_paused else R.string.timer_bar_reading, title),
            style = MaterialTheme.typography.titleSmall,
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        Text(
            clockText(timer, now),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            color = if (timer.paused) colors.mutedForeground else colors.primary,
        )
        LucideIcon("ChevronRight", contentDescription = null, tint = colors.mutedForeground, size = 16.dp, modifier = Modifier.padding(start = 4.dp))
    }
}

/**
 * Shown over the reader when a timer is on for the same book: the reader records its own session,
 * so both would count the same time. Says how long the timer has run (a forgotten one stands out)
 * and offers to stop it (that time is saved without a page); asked once per opening of the reader.
 */
@Composable
fun ReaderTimerNotice(bookId: Long) {
    val viewModel = appViewModel(key = "reader-timer-notice") { RunningTimerViewModel(it) }
    val active by viewModel.active.collectAsStateWithLifecycle()
    var answered by rememberSaveable(bookId) { mutableStateOf(false) }
    // When the reader opened: its own session starts about now, so the timer's ends here.
    val openedAt by rememberSaveable(bookId) { mutableStateOf(System.currentTimeMillis()) }
    val timer = active
    if (answered || timer == null || timer.bookId != bookId) return
    ReaderTimerDialog(
        // What Stop timer saves: the timer's time up to the reader's opening.
        ranMs = timer.activeMs(openedAt),
        onStop = {
            answered = true
            viewModel.stopWithoutPage(bookId, openedAt)
        },
        onKeep = { answered = true },
    )
}

/** [ReaderTimerNotice]'s dialog: the timer has run for [ranMs]; Stop timer or Keep it running. */
@Composable
internal fun ReaderTimerDialog(ranMs: Long, onStop: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        containerColor = OttershelfTheme.colors.card,
        icon = { LucideIcon("Timer", contentDescription = null, tint = OttershelfTheme.colors.primary, size = 24.dp) },
        title = { Text(stringResource(R.string.timer_reader_title)) },
        text = { Text(stringResource(R.string.timer_reader_message, durationText(ranMs / 1000))) },
        confirmButton = {
            TextButton(onClick = onStop) { Text(stringResource(R.string.timer_reader_stop)) }
        },
        dismissButton = {
            TextButton(onClick = onKeep) { Text(stringResource(R.string.timer_reader_keep)) }
        },
    )
}
