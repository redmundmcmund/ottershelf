package io.github.ottershelf.feature.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ottershelf.R
import io.github.ottershelf.core.tracking.TrackingRepository
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.CardTitle
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.DetailTopBar
import io.github.ottershelf.ui.components.PillProgressBar
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.util.Locale

/** The yearly book goal, the daily minutes goal and the streaks. */
@Composable
fun ReadingGoalsScreen(navigator: AppNavigator) {
    val viewModel = appViewModel { ReadingGoalsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    val snackbar = remember { SnackbarHostState() }
    val saved = stringResource(R.string.calendar_yearly_saved)
    val failed = stringResource(R.string.calendar_yearly_failed)
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(if (it == GoalsMessage.YearlySaved) saved else failed) }
    }
    ReadingGoalsContent(
        state = state,
        onBack = { navigator.back() },
        onSetYearly = viewModel::setYearlyGoal,
        onSetDaily = viewModel::setDailyGoal,
        onRetry = viewModel::retry,
        snackbar = snackbar,
    )
}

private enum class GoalDialog { Yearly, Daily }

@Composable
fun ReadingGoalsContent(
    state: GoalsUiState,
    onBack: () -> Unit = {},
    onSetYearly: (Int?) -> Unit = {},
    onSetDaily: (Int?) -> Unit = {},
    onRetry: () -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val direction = LocalLayoutDirection.current
    var dialog by remember { mutableStateOf<GoalDialog?>(null) }
    Scaffold(
        topBar = { DetailTopBar(title = stringResource(R.string.calendar_goals_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
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
            YearlyCard(state, onEdit = { dialog = GoalDialog.Yearly })
            DailyCard(state, onEdit = { dialog = GoalDialog.Daily })
            StreakCards(state)
            if (state.failed && !state.loading) {
                Text(
                    stringResource(R.string.calendar_stats_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = OttershelfTheme.colors.mutedForeground,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecondaryButton(stringResource(R.string.calendar_retry), onClick = onRetry, icon = "RefreshCw", modifier = Modifier.fillMaxWidth())
            }
        }
    }
    when (dialog) {
        GoalDialog.Yearly -> YearlyDialog(
            year = state.today.year,
            initial = state.yearlyGoal,
            onDismiss = { dialog = null },
            onSave = {
                dialog = null
                onSetYearly(it)
            },
        )
        GoalDialog.Daily -> DailyDialog(
            initial = state.dailyGoal,
            onDismiss = { dialog = null },
            onSave = {
                dialog = null
                onSetDaily(it)
            },
        )
        null -> Unit
    }
}

@Composable
private fun YearlyCard(state: GoalsUiState, onEdit: () -> Unit) {
    val colors = OttershelfTheme.colors
    val goal = state.yearlyGoal
    val completed = state.completed
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        CardTitle(stringResource(R.string.calendar_yearly_goal, state.today.year), icon = "Trophy")
        Spacer(Modifier.height(14.dp))
        if (goal == null) {
            Text(stringResource(R.string.calendar_no_yearly_goal), style = MaterialTheme.typography.bodyMedium, color = colors.foreground)
            if (completed != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    pluralStringResource(R.plurals.calendar_finished_this_year, completed, completed),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedForeground,
                )
            }
            Spacer(Modifier.height(14.dp))
            AccentButton(stringResource(R.string.calendar_set_goal), onClick = onEdit, icon = "Target", enabled = !state.savingYearly, modifier = Modifier.fillMaxWidth())
            return@DashCard
        }
        val done = completed ?: 0
        val reached = done >= goal
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (completed == null) "–" else done.toString(), style = bigNumberStyle(34), color = colors.foreground)
                    Text(
                        stringResource(R.string.calendar_of_books, goal),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.mutedForeground,
                        modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                state.pace?.let { pace ->
                    val (label, tint, icon) = when {
                        reached -> Triple(stringResource(R.string.calendar_goal_done), colors.success, "Check")
                        pace == GoalPace.AHEAD -> Triple(stringResource(R.string.calendar_pace_ahead), colors.success, "Sparkles")
                        pace == GoalPace.ON_PACE -> Triple(stringResource(R.string.calendar_pace_on), colors.primary, "Target")
                        else -> Triple(stringResource(R.string.calendar_pace_behind), colors.warning, "TriangleAlert")
                    }
                    Pill(label, tint, icon = icon)
                }
            }
            GoalRing(
                progress = if (goal > 0) done.toFloat() / goal else 0f,
                size = 84.dp,
                color = if (reached) colors.success else colors.primary,
            ) {
                RingCaption(stringResource(R.string.calendar_percent, if (goal > 0) (done * 100 / goal).coerceAtMost(999) else 0), stringResource(R.string.calendar_done))
            }
        }
        Spacer(Modifier.height(14.dp))
        PillProgressBar(if (goal > 0) done.toFloat() / goal else 0f, color = if (reached) colors.success else colors.primary)
        Spacer(Modifier.height(10.dp))
        val lines = buildList {
            if (!reached) add(pluralStringResource(R.plurals.calendar_books_to_go, goal - done, goal - done))
            state.projected?.let { add(stringResource(R.string.calendar_projected, formatBooks(it))) }
            if (!reached) {
                val expected = CalendarMath.expectedByNow(goal, state.today)
                add(stringResource(R.string.calendar_expected_now, expected))
            }
        }
        lines.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
        }
        Spacer(Modifier.height(14.dp))
        SecondaryButton(stringResource(R.string.calendar_change_goal), onClick = onEdit, icon = "Target", enabled = !state.savingYearly, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DailyCard(state: GoalsUiState, onEdit: () -> Unit) {
    val colors = OttershelfTheme.colors
    val goal = state.dailyGoal
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        CardTitle(stringResource(R.string.calendar_daily_goal), icon = "Target")
        Spacer(Modifier.height(14.dp))
        if (goal == null) {
            Text(stringResource(R.string.calendar_no_daily_goal), style = MaterialTheme.typography.bodyMedium, color = colors.foreground)
            Spacer(Modifier.height(14.dp))
            AccentButton(stringResource(R.string.calendar_set_goal), onClick = onEdit, icon = "Target", modifier = Modifier.fillMaxWidth())
            return@DashCard
        }
        val minutes = ((state.todaySeconds ?: 0) / 60).toInt()
        val reached = minutes >= goal
        Row(verticalAlignment = Alignment.CenterVertically) {
            GoalRing(progress = minutes.toFloat() / goal, size = 84.dp, color = if (reached) colors.success else colors.primary) {
                RingCaption(stringResource(R.string.calendar_minutes_short, minutes), stringResource(R.string.calendar_today))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.calendar_minutes_a_day, goal), style = MaterialTheme.typography.titleMedium, color = colors.foreground)
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        state.todaySeconds == null -> stringResource(R.string.calendar_today_unknown)
                        reached -> stringResource(R.string.calendar_goal_reached)
                        else -> stringResource(R.string.calendar_goal_to_go, goal - minutes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (reached) colors.success else colors.mutedForeground,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        SecondaryButton(stringResource(R.string.calendar_change_goal), onClick = onEdit, icon = "Target", modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StreakCards(state: GoalsUiState) {
    val colors = OttershelfTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(
            icon = "Flame",
            value = state.currentStreak?.let { pluralStringResource(R.plurals.calendar_streak_days, it, it) } ?: "–",
            label = stringResource(R.string.calendar_current_streak),
            tint = colors.flame,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            icon = "Trophy",
            value = state.longestStreak?.let { pluralStringResource(R.plurals.calendar_streak_days, it, it) } ?: "–",
            label = stringResource(R.string.calendar_longest_streak),
            modifier = Modifier.weight(1f),
        )
    }
    val last = state.lastSevenSeconds
    if (last != null) {
        DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
            CardTitle(stringResource(R.string.calendar_last_seven), icon = "CalendarDays")
            Spacer(Modifier.height(10.dp))
            Text(durationLabel(last), style = bigNumberStyle(24), color = colors.foreground)
            state.previousSevenSeconds?.let {
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.calendar_previous_seven, durationLabel(it)), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
            }
        }
    }
}

private fun formatBooks(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.getDefault(), "%.1f", value)

@Composable
private fun YearlyDialog(year: Int, initial: Int?, onDismiss: () -> Unit, onSave: (Int?) -> Unit) {
    var text by remember { mutableStateOf((initial ?: 12).toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in 1..TrackingRepository.MAX_YEARLY_GOAL
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OttershelfTheme.colors.popover,
        title = { Text(stringResource(R.string.calendar_yearly_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.calendar_yearly_dialog_message, year),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OttershelfTheme.colors.mutedForeground,
                )
                NumberField(text, { text = it }, stringResource(R.string.calendar_books_unit), Modifier.padding(top = 12.dp).fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                PresetChips(listOf(6, 12, 24, 36, 52), value) { text = it.toString() }
            }
        },
        confirmButton = { TextButton(onClick = { value?.let(onSave) }, enabled = valid) { Text(stringResource(R.string.calendar_save)) } },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.calendar_remove_goal)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.calendar_cancel)) }
            }
        },
    )
}

@Composable
private fun DailyDialog(initial: Int?, onDismiss: () -> Unit, onSave: (Int?) -> Unit) {
    var text by remember { mutableStateOf((initial ?: 30).toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in 1..ReadingGoalsViewModel.MAX_DAILY_MINUTES
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OttershelfTheme.colors.popover,
        title = { Text(stringResource(R.string.calendar_daily_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.calendar_daily_dialog_message), style = MaterialTheme.typography.bodyMedium, color = OttershelfTheme.colors.mutedForeground)
                NumberField(text, { text = it }, stringResource(R.string.calendar_minutes_unit), Modifier.padding(top = 12.dp).fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                PresetChips(listOf(10, 15, 20, 30, 45, 60), value) { text = it.toString() }
            }
        },
        confirmButton = { TextButton(onClick = { value?.let(onSave) }, enabled = valid) { Text(stringResource(R.string.calendar_save)) } },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.calendar_remove_goal)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.calendar_cancel)) }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetChips(values: List<Int>, selected: Int?, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { v ->
            FilterChip(
                selected = v == selected,
                onClick = { onPick(v) },
                label = { Text(v.toString()) },
                shape = RoundedCornerShape(OttershelfTheme.radii.md),
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, suffix: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter(Char::isDigit).take(4)) },
        modifier = modifier,
        singleLine = true,
        suffix = { Text(suffix) },
        shape = RoundedCornerShape(OttershelfTheme.radii.md),
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
