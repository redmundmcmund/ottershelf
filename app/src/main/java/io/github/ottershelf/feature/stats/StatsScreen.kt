package io.github.ottershelf.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ottershelf.R
import io.github.ottershelf.feature.stats.model.Overview
import io.github.ottershelf.ui.components.CardTitle
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.SectionHeader
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.TopBarActions
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Statistics: a root list (the drawer's Tracking > Statistics) under the shell's toolbar. */
@Composable
fun StatsScreen(navigator: AppNavigator, contentPadding: PaddingValues) {
    val viewModel = appViewModel { StatsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Back from another screen or the background, perhaps on a new day.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    // The year in review (feature.achievements).
    TopBarActions {
        IconButton(onClick = { navigator.navigate(Route.Rewind()) }) {
            LucideIcon("Rewind", contentDescription = stringResource(R.string.achievements_rewind), tint = LocalContentColor.current, size = 22.dp)
        }
    }
    StatsContent(
        state = state,
        onTab = viewModel::selectTab,
        onPeriod = viewModel::selectPeriod,
        onLibrary = viewModel::selectLibrary,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retry,
        onOpenDay = { navigator.navigate(Route.Day(it.toString())) },
        onOpenGoals = { navigator.navigate(Route.ReadingGoals) },
        onOpenBook = { navigator.navigate(Route.BookDetail(it)) },
        contentPadding = contentPadding,
    )
}

/**
 * The two tabs (Reading, Library) over one filter row, then the tab's cards: for Reading the
 * activity overview (today and the week, streaks, the year's goal, the weekly rhythm) and the
 * charts; for Library the library statistics. Pull to refresh.
 */
@Composable
fun StatsContent(
    state: StatsUiState,
    onTab: (StatsTab) -> Unit = {},
    onPeriod: (StatsPeriod) -> Unit = {},
    onLibrary: (Long?) -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetry: (StatsChart) -> Unit = {},
    onOpenDay: (LocalDate) -> Unit = {},
    onOpenGoals: () -> Unit = {},
    onOpenBook: (Long) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
) {
    val colors = OttershelfTheme.colors
    val direction = LocalLayoutDirection.current
    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding()),
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "tabs") { Tabs(state.tab, onTab) }
            if (state.tab == StatsTab.READING || state.libraries.size > 1) item(key = "filters") { FilterRow(state, onPeriod, onLibrary) }
            if (state.tab == StatsTab.READING) {
                readingItems(state, onRetry, onOpenDay, onOpenGoals)
            } else {
                libraryItems(state, onRetry, onOpenBook)
            }
        }
    }
}

// --- tabs and filters -------------------------------------------------------------------------

/**
 * The web's segmented control: a muted track, the picked option on the card colour with a border.
 * Options share the width, so the whole choice is always visible.
 */
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, small: Boolean = false) {
    val colors = OttershelfTheme.colors
    val radii = OttershelfTheme.radii
    Row(
        modifier
            .background(colors.muted, RoundedCornerShape(radii.lg))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (value, label) ->
            val picked = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(if (picked) colors.card else colors.muted, RoundedCornerShape(radii.md))
                    .then(if (picked) Modifier.border(1.dp, colors.border, RoundedCornerShape(radii.md)) else Modifier)
                    .clickable { onSelect(value) }
                    .padding(vertical = if (small) 7.dp else 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    color = if (picked) (if (small) colors.primary else colors.foreground) else colors.mutedForeground,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Tabs(tab: StatsTab, onTab: (StatsTab) -> Unit) = Segmented(
    options = listOf(StatsTab.READING to stringResource(R.string.stats_tab_reading), StatsTab.LIBRARY to stringResource(R.string.stats_tab_library)),
    selected = tab,
    onSelect = onTab,
    modifier = Modifier.fillMaxWidth(),
)

/** One row above everything it scopes: the library filter (with more than one library) and the period (Reading). */
@Composable
private fun FilterRow(state: StatsUiState, onPeriod: (StatsPeriod) -> Unit, onLibrary: (Long?) -> Unit) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.lg)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.libraries.size > 1) {
            var open by remember { mutableStateOf(false) }
            val allLibraries = stringResource(R.string.stats_all_libraries)
            val name = state.libraries.firstOrNull { it.id == state.libraryId }?.name
            Box(if (state.tab == StatsTab.READING) Modifier else Modifier.weight(1f)) {
                Row(
                    Modifier
                        .height(36.dp)
                        .background(if (name != null) colors.accentTint else colors.card, shape)
                        .border(1.dp, colors.border, shape)
                        .clickable { open = true }
                        .padding(horizontal = 10.dp)
                        .semantics { contentDescription = name ?: allLibraries },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LucideIcon("Library", contentDescription = null, tint = if (name != null) colors.primary else colors.mutedForeground, size = 16.dp)
                    if (name != null || state.tab == StatsTab.LIBRARY) {
                        Text(
                            name ?: allLibraries,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (name != null) colors.primary else colors.foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = if (state.tab == StatsTab.READING) 88.dp else 240.dp),
                        )
                    }
                    LucideIcon("ChevronDown", contentDescription = null, tint = colors.mutedForeground, size = 14.dp)
                }
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text(allLibraries) }, onClick = { open = false; onLibrary(null) })
                    state.libraries.forEach { lib ->
                        DropdownMenuItem(text = { Text(lib.name) }, onClick = { open = false; onLibrary(lib.id) })
                    }
                }
            }
        }
        if (state.tab == StatsTab.READING) {
            Segmented(
                options = listOf(
                    StatsPeriod.D30 to stringResource(R.string.stats_period_30),
                    StatsPeriod.D90 to stringResource(R.string.stats_period_90),
                    StatsPeriod.D365 to stringResource(R.string.stats_period_365),
                    StatsPeriod.YEAR to stringResource(R.string.stats_period_year),
                ),
                selected = state.period,
                onSelect = onPeriod,
                modifier = Modifier.weight(1f),
                small = true,
            )
        }
    }
}

// --- the reading tab --------------------------------------------------------------------------

private fun LazyListScope.readingItems(
    state: StatsUiState,
    onRetry: (StatsChart) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onOpenGoals: () -> Unit,
) {
    val r = state.reading
    item(key = "overview-header") { SectionHeader(stringResource(R.string.stats_your_reading), icon = "BookOpen", modifier = Modifier.padding(top = 4.dp)) }
    item(key = "overview") { OverviewCards(r.overview, state.today, onRetry = { onRetry(StatsChart.OVERVIEW) }, onOpenDay, onOpenGoals) }
    item(key = "charts-header") { SectionHeader(stringResource(R.string.stats_charts), icon = "ChartColumn", count = READING_CHART_COUNT, modifier = Modifier.padding(top = 4.dp)) }
    item(key = "daily") { DailyCard(state) { onRetry(StatsChart.DAILY) } }
    item(key = "heatmap") { HeatmapCard(state, onOpenDay) { onRetry(StatsChart.HEATMAP) } }
    item(key = "peak") { PeakHoursCard(state) { onRetry(StatsChart.PEAK) } }
    item(key = "weekdays") { WeekdaysCard(state) { onRetry(StatsChart.WEEKDAYS) } }
    item(key = "completions") { CompletionsCard(state) { onRetry(StatsChart.COMPLETIONS) } }
    item(key = "trajectory") { TrajectoryCard(state) { onRetry(StatsChart.TRAJECTORY) } }
    item(key = "genres") { GenresCard(state) { onRetry(StatsChart.GENRES) } }
    item(key = "pace") { PaceCard(state) { onRetry(StatsChart.PACE) } }
    item(key = "archetypes") { ArchetypesCard(state) { onRetry(StatsChart.ARCHETYPES) } }
    item(key = "funnel") { FunnelCard(state) { onRetry(StatsChart.FUNNEL) } }
    item(key = "latency") { LatencyCard(state) { onRetry(StatsChart.LATENCY) } }
}

private const val READING_CHART_COUNT = 11

/** The activity overview (one request): today and the week, streaks, the goal, the rhythm. */
@Composable
private fun OverviewCards(load: Load<Overview>, today: LocalDate, onRetry: () -> Unit, onOpenDay: (LocalDate) -> Unit, onOpenGoals: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (load) {
            // Faded while another library's figures load, as the chart cards are.
            is Load.Ready -> Column(Modifier.fillMaxWidth().alpha(if (load.stale) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val o = load.data
                WeekCard(o, today, onOpenDay)
                StreakRow(o)
                GoalCard(o, today, onOpenGoals)
                RhythmCard(o)
            }
            else -> ChartCard(
                title = stringResource(R.string.stats_last_seven),
                icon = "CalendarDays",
                load = load,
                onRetry = onRetry,
                placeholderHeight = 150.dp,
            ) { }
        }
    }
}

/** Today, the last 7 days against the 7 before, and the 7-day strip (tap a day: its sessions). */
@Composable
private fun WeekCard(o: Overview, today: LocalDate, onOpenDay: (LocalDate) -> Unit) {
    val colors = OttershelfTheme.colors
    val snap = o.snapshot
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.stats_today), style = MaterialTheme.typography.labelMedium, color = colors.mutedForeground)
                Text(durationText(snap.today.readingSeconds), style = figureStyle(26), color = colors.foreground, maxLines = 1)
            }
            Column(Modifier.weight(1.3f)) {
                Text(stringResource(R.string.stats_last_seven), style = MaterialTheme.typography.labelMedium, color = colors.mutedForeground)
                Text(durationText(snap.lastSevenDays.readingSeconds), style = figureStyle(26), color = colors.foreground, maxLines = 1)
                TimeChangeLine(
                    StatsMath.change(snap.lastSevenDays.readingSeconds, snap.previousSevenDays.readingSeconds, StatsMath.MIN_TIME_BASE_SECONDS),
                    R.string.stats_vs_previous_seven,
                    R.string.stats_up_from_seven,
                    R.string.stats_down_from_seven,
                    stringResource(R.string.stats_new_this_week),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        val days = remember(o, today) {
            val byDay = o.dailyActivity.days.associateBy { it.day.take(10) }
            (6 downTo 0).map { today.minusDays(it.toLong()) }.map { it to (byDay[it.toString()]?.readingSeconds ?: 0.0) }
        }
        val max = days.maxOf { it.second }.coerceAtLeast(1.0)
        val locale = Locale.getDefault()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { (date, seconds) ->
                val isToday = date == today
                val shape = RoundedCornerShape(OttershelfTheme.radii.lg)
                Column(
                    Modifier
                        .weight(1f)
                        .background(if (isToday) colors.accentTint else colors.cardRow, shape)
                        .clickable { onOpenDay(date) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val series = colors.primary
                    val track = colors.muted
                    Canvas(Modifier.width(10.dp).height(44.dp)) {
                        val r = CornerRadius(size.width / 2)
                        drawRoundRect(track, cornerRadius = r)
                        val h = (seconds / max * size.height).toFloat()
                        if (h > 0f) drawRoundRect(series, Offset(0f, size.height - h.coerceAtLeast(size.width)), Size(size.width, h.coerceAtLeast(size.width)), r)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isToday) colors.primary else colors.mutedForeground,
                    )
                    Text(
                        date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isToday) colors.primary else colors.foreground,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.stats_week_strip_hint), style = MaterialTheme.typography.labelSmall, color = colors.mutedForeground)
    }
}

@Composable
private fun StreakRow(o: Overview) {
    val colors = OttershelfTheme.colors
    val res = LocalContext.current.resources
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatBox("Flame", res.days(o.snapshot.currentStreak), stringResource(R.string.stats_current_streak), Modifier.weight(1f), tint = colors.flame)
        StatBox("Trophy", res.days(o.snapshot.longestStreak), stringResource(R.string.stats_longest_streak), Modifier.weight(1f))
        StatBox("BookCheck", o.snapshot.completedBooksYtd.toString(), stringResource(R.string.stats_books_this_year), Modifier.weight(1f), tint = colors.success)
    }
}

/** The year's goal: progress, ahead / on pace / behind (icon and label), the projection and the monthly trajectory. */
@Composable
private fun GoalCard(o: Overview, today: LocalDate, onOpenGoals: () -> Unit) {
    val colors = OttershelfTheme.colors
    val res = LocalContext.current.resources
    val goal = o.goal ?: return
    val locale = Locale.getDefault()
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        CardTitle(stringResource(R.string.stats_goal_title, goal.year), icon = "Target")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                Text(goal.completedBooks.toString(), style = figureStyle(32), color = colors.foreground)
                Text(
                    if (goal.goalBooks != null) stringResource(R.string.stats_of_books, goal.goalBooks) else stringResource(R.string.stats_books_finished_big),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.mutedForeground,
                    modifier = Modifier.padding(start = 6.dp, bottom = 5.dp),
                )
            }
            when (goal.status) {
                "ahead" -> StatusPill(stringResource(R.string.stats_goal_ahead), colors.success, "Sparkles")
                "on_pace" -> StatusPill(stringResource(R.string.stats_goal_on_pace), colors.primary, "Target")
                "behind" -> StatusPill(stringResource(R.string.stats_goal_behind), colors.warning, "TriangleAlert")
            }
        }
        Text(stringResource(R.string.stats_goal_projection, trim(goal.projectedBooks)), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
        if (goal.goalBooks != null && goal.goalBooks > 0) {
            Spacer(Modifier.height(8.dp))
            MeterBar(goal.completedBooks.toFloat() / goal.goalBooks, if (goal.completedBooks >= goal.goalBooks) colors.success else colors.primary)
        }
        Spacer(Modifier.height(8.dp))
        val points = goal.points.sortedBy { it.month }
        if (points.isNotEmpty()) {
            val currentMonth = if (today.year == goal.year) today.monthValue else 12
            var selected by rememberSaveable(goal.year) { mutableIntStateOf(-1) }
            val monthName = { m: Int -> java.time.Month.of(m).getDisplayName(TextStyle.SHORT, locale) }
            val sel = points.getOrNull(selected)
            Readout(
                value = sel?.let { res.getQuantityString(R.plurals.stats_books_finished, it.actualCumulative.roundToInt(), it.actualCumulative.roundToInt()) }
                    ?.takeIf { sel.month <= currentMonth },
                label = sel?.let { p ->
                    val month = java.time.Month.of(p.month).getDisplayName(TextStyle.FULL_STANDALONE, locale)
                    p.targetCumulative?.let { stringResource(R.string.stats_goal_by_then, month, trim(it)) } ?: month
                } ?: "",
                hint = stringResource(R.string.stats_tap_hint),
            )
            val ink = rememberChartInk()
            val series = remember(goal, currentMonth, ink) {
                buildList {
                    add(LineSeries(points.map { if (it.month <= currentMonth) it.actualCumulative else null }, ink.series, area = true, endDot = true))
                    if (goal.goalBooks != null) add(LineSeries(points.map { it.targetCumulative }, ink.comparison, dashed = true))
                }
            }
            LineChart(
                series = series,
                labels = remember(goal, locale) { points.map { if (it.month % 3 == 1) monthName(it.month) else null } },
                selected = selected,
                onSelect = { selected = it },
                formatY = { trim(it) },
                integerTicks = true,
                height = 130.dp,
            )
            if (goal.goalBooks != null) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LegendKey(stringResource(R.string.stats_finished), ink.series, line = true)
                    LegendKey(stringResource(R.string.stats_goal_line, goal.goalBooks), ink.comparison, line = true, dashed = true)
                }
            }
        }
        if (goal.goalBooks == null) {
            Spacer(Modifier.height(10.dp))
            LinkAction(stringResource(R.string.stats_goal_set), onOpenGoals)
        }
    }
}

/** Average reading a day per weekday over the last year (Monday first), the favourite in the accent. */
@Composable
private fun RhythmCard(o: Overview) {
    val colors = OttershelfTheme.colors
    val res = LocalContext.current.resources
    val rhythm = o.rhythm
    if (rhythm.weekdays.isEmpty() || rhythm.sessionsCount == 0) return
    val locale = Locale.getDefault()
    val averages = DoubleArray(7)
    rhythm.weekdays.forEach { if (it.dayOfWeek in 0..6) averages[StatsMath.mondayFirst(it.dayOfWeek)] = it.averageReadingSeconds }
    val favourite = rhythm.favoriteDayOfWeek?.let { StatsMath.mondayFirst(it) }
    val max = averages.max().coerceAtLeast(1.0)
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        CardTitle(stringResource(R.string.stats_rhythm_title), icon = "CalendarCheck")
        Text(
            stringResource(R.string.stats_rhythm_caption),
            style = MaterialTheme.typography.bodySmall,
            color = colors.mutedForeground,
            modifier = Modifier.padding(start = 24.dp, top = 1.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            (0 until 7).forEach { i ->
                val emphasis = i == favourite
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (emphasis) res.duration(averages[i]) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.foreground,
                        maxLines = 1,
                    )
                    val fill = if (emphasis) colors.primary else colors.primary.copy(alpha = 0.35f)
                    val track = colors.muted
                    Canvas(Modifier.fillMaxWidth(0.6f).height(56.dp)) {
                        val r = CornerRadius(4.dp.toPx())
                        val h = (averages[i] / max * size.height).toFloat()
                        drawRoundRect(track, cornerRadius = r, alpha = 0.6f)
                        if (h > 0f) {
                            val hh = h.coerceAtLeast(4.dp.toPx())
                            drawRoundRect(fill, Offset(0f, size.height - hh), Size(size.width, hh), r)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        StatsMath.weekday(i).getDisplayName(TextStyle.SHORT, locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (emphasis) colors.primary else colors.mutedForeground,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val lines = listOfNotNull(
            favourite?.let { stringResource(R.string.stats_favourite_day, StatsMath.weekday(it).getDisplayName(TextStyle.FULL, locale)) },
            rhythm.peakHour?.let { stringResource(R.string.stats_peak_hour, StatsMath.clock(it.toDouble())) },
        )
        Text(lines.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
    }
}
