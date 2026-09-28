package io.github.ottershelf.feature.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ottershelf.R
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The reading tab's chart cards, each a phone version of its web chart
 * (client/src/features/statistics/components/user) with the web's low-confidence thresholds.
 */

private val formatters = ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()

/** One formatter per pattern and locale (built once, not on every label). */
private fun formatter(pattern: String): DateTimeFormatter =
    Locale.getDefault().let { locale -> formatters.getOrPut(pattern to locale) { DateTimeFormatter.ofPattern(pattern, locale) } }

private val dayMonth: DateTimeFormatter get() = formatter("d MMM")
private val weekdayDayMonth: DateTimeFormatter get() = formatter("EEE d MMM")

/** The peak-hours axis: every third hour. */
private val HourLabels: List<String?> = (0 until 24).map { if (it % 3 == 0) "%02d".format(Locale.ROOT, it) else null }

/** The archetypes' hour axis. */
private val DayHourTicks: List<Pair<Float, String>> = listOf(0, 6, 12, 18, 24).map { it.toFloat() to "%02d".format(Locale.ROOT, it) }

/** Daily (30/90 days) or weekly (longer) reading time, with the previous period as a grey line. */
@Composable
internal fun DailyCard(state: StatsUiState, onRetry: () -> Unit) {
    val res = LocalContext.current.resources
    val colors = OttershelfTheme.colors
    val load = state.reading.daily
    val weekly = (load as? Load.Ready)?.data?.bucketDays == 7 || state.days > 90
    ChartCard(
        title = stringResource(if (weekly) R.string.stats_weekly_title else R.string.stats_daily_title),
        icon = "Clock",
        subtitle = periodText(state.period, state.days),
        load = load,
        onRetry = onRetry,
        isEmpty = { it.totalSeconds <= 0 && it.previousTotalSeconds <= 0 },
    ) { data ->
        Row(verticalAlignment = Alignment.Bottom) {
            Text(durationText(data.totalSeconds), style = figureStyle(22), color = colors.foreground)
        }
        TimeChangeLine(
            StatsMath.change(data.totalSeconds, data.previousTotalSeconds, StatsMath.MIN_TIME_BASE_SECONDS),
            R.string.stats_vs_previous_period,
            R.string.stats_up_from_previous,
            R.string.stats_down_from_previous,
            stringResource(R.string.stats_active_days, data.activeDays, data.days),
        )
        if (data.previousTotalSeconds > 0) {
            Text(stringResource(R.string.stats_active_days, data.activeDays, data.days), style = MaterialTheme.typography.labelSmall, color = colors.mutedForeground)
        }
        Spacer(Modifier.height(4.dp))
        var selected by rememberSaveable(data.days, data.bucketDays) { mutableIntStateOf(-1) }
        val sel = data.buckets.getOrNull(selected)
        Readout(
            value = sel?.let { res.duration(it.seconds) },
            label = sel?.let {
                val date = if (data.bucketDays == 1) it.start.format(weekdayDayMonth) else stringResource(R.string.stats_date_range, it.start.format(dayMonth), it.end.format(dayMonth))
                stringResource(R.string.stats_previous_value, date, res.duration(it.previousSeconds))
            } ?: "",
            hint = stringResource(R.string.stats_tap_hint),
        )
        val values = remember(data) { data.buckets.map { it.seconds / 60 } }
        val previous = remember(data) { data.buckets.map { it.previousSeconds / 60 } }
        val labels = remember(data, Locale.getDefault()) {
            val count = data.buckets.size
            val step = when {
                count <= 31 -> 7
                count <= 60 -> 8
                else -> 15
            }
            data.buckets.mapIndexed { i, b -> if ((count - 1 - i) % step == 0) b.start.format(dayMonth) else null }
        }
        ColumnChart(
            values = values,
            previous = previous,
            labels = labels,
            selected = selected,
            onSelect = { selected = it },
            formatY = { res.minutesAxis(it) },
            timeAxis = true,
            description = stringResource(R.string.stats_daily_title),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val ink = rememberChartInk()
            LegendKey(stringResource(R.string.stats_this_period), ink.series)
            LegendKey(stringResource(R.string.stats_previous_period), ink.comparison, line = true)
        }
    }
}

/** This year as a calendar heatmap (the web's fixed minute bins); tap a day, then open it. */
@Composable
internal fun HeatmapCard(state: StatsUiState, onOpenDay: (LocalDate) -> Unit, onRetry: () -> Unit) {
    val res = LocalContext.current.resources
    val colors = OttershelfTheme.colors
    val load = state.reading.heatmap
    val activeDays = (load as? Load.Ready)?.data?.count { it.seconds > 0 } ?: 0
    ChartCard(
        title = stringResource(R.string.stats_heatmap_title),
        icon = "CalendarDays",
        subtitle = if (load is Load.Ready) stringResource(R.string.stats_heatmap_subtitle, state.today.year, res.days(activeDays)) else null,
        load = load,
        onRetry = onRetry,
        placeholderHeight = 240.dp,
        // Drawn from the first day with reading: the subtitle already counts them.
        isEmpty = { days -> days.none { it.seconds > 0 } },
    ) { days ->
        val locale = Locale.getDefault()
        var selected by rememberSaveable(state.today.year) { mutableIntStateOf(-1) }
        val sel = days.getOrNull(selected)
        Readout(
            value = sel?.let { res.duration(it.seconds).takeIf { _ -> it.seconds > 0 } ?: "0" },
            label = sel?.let { stringResource(R.string.stats_time_sessions, it.date.format(weekdayDayMonth), res.sessions(it.sessions)) } ?: "",
            hint = stringResource(R.string.stats_tap_cell_hint),
            action = sel?.takeIf { it.seconds > 0 }?.let { d -> @Composable { LinkAction(stringResource(R.string.stats_open_day)) { onOpenDay(d.date) } } },
        )
        Spacer(Modifier.height(4.dp))
        val ramp = remember(colors) { ChartPalette.heatRamp(colors.primary, colors.card) }
        val dayLabels = remember(locale) { (1..7).map { java.time.DayOfWeek.of(it).getDisplayName(TextStyle.NARROW, locale) } }
        YearHeatmap(
            days = days,
            levelColors = ramp,
            empty = colors.muted,
            monthLabel = { Month.of(it).getDisplayName(TextStyle.SHORT, locale) },
            dayLabels = dayLabels,
            selected = selected,
            onSelect = { selected = it },
            description = stringResource(R.string.stats_heatmap_title),
        )
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LegendKey(stringResource(R.string.stats_heat_none), colors.muted)
            listOf(R.string.stats_heat_1, R.string.stats_heat_2, R.string.stats_heat_3, R.string.stats_heat_4).forEachIndexed { i, label ->
                LegendKey(stringResource(label), ramp[i])
            }
        }
    }
}

/** Reading minutes by the hour sessions start (the web's PeakReadingHoursChart, one series). */
@Composable
internal fun PeakHoursCard(state: StatsUiState, onRetry: () -> Unit) {
    val res = LocalContext.current.resources
    val load = state.reading.peakHours
    ChartCard(
        title = stringResource(R.string.stats_peak_title),
        icon = "Sun",
        subtitle = periodText(state.period, state.days),
        load = load,
        onRetry = onRetry,
        isEmpty = { hours -> hours.sumOf { it.eventsCount } < 20 },
    ) { rows ->
        val hours = remember(rows) { DoubleArray(24).also { a -> rows.forEach { if (it.hour in 0..23) a[it.hour] += it.readingSeconds } } }
        val events = remember(rows) { IntArray(24).also { a -> rows.forEach { if (it.hour in 0..23) a[it.hour] += it.eventsCount.roundToInt() } } }
        var selected by rememberSaveable(state.days) { mutableIntStateOf(-1) }
        val peak = hours.indices.maxBy { hours[it] }
        Readout(
            value = if (selected in 0..23) res.duration(hours[selected]) else null,
            label = if (selected in 0..23) stringResource(
                R.string.stats_hour_span, StatsMath.clock(selected.toDouble()), StatsMath.clock((selected + 1).toDouble()), res.sessions(events[selected]),
            ) else "",
            hint = stringResource(R.string.stats_peak_hour, StatsMath.clock(peak.toDouble())),
        )
        ColumnChart(
            values = remember(hours) { hours.map { it / 60 } },
            labels = HourLabels,
            selected = selected,
            onSelect = { selected = it },
            formatY = { res.minutesAxis(it) },
            timeAxis = true,
            description = stringResource(R.string.stats_peak_title),
        )
    }
}

/** Average minutes a day per weekday over the window (the web's FavoriteReadingDaysChart). */
@Composable
internal fun WeekdaysCard(state: StatsUiState, onRetry: () -> Unit) {
    val res = LocalContext.current.resources
    val load = state.reading.weekdays
    val locale = Locale.getDefault()
    ChartCard(
        title = stringResource(R.string.stats_weekdays_title),
        icon = "CalendarCheck",
        subtitle = periodText(state.period, state.days),
        load = load,
        onRetry = onRetry,
        isEmpty = { it.events < 14 },
    ) { data ->
        var selected by rememberSaveable(state.days) { mutableIntStateOf(-1) }
        val favourite = data.averageMinutes.indices.maxBy { data.averageMinutes[it] }
        Readout(
            value = if (selected in 0..6) stringResource(R.string.stats_a_day, res.duration(data.averageMinutes[selected] * 60)) else null,
            label = if (selected in 0..6) stringResource(
                R.string.stats_weekday_readout, StatsMath.weekday(selected).getDisplayName(TextStyle.FULL, locale), res.duration(data.totalSeconds[selected]),
            ) else "",
            hint = stringResource(R.string.stats_favourite_day, StatsMath.weekday(favourite).getDisplayName(TextStyle.FULL, locale)),
        )
        ColumnChart(
            values = data.averageMinutes,
            labels = remember(locale) { (0 until 7).map { StatsMath.weekday(it).getDisplayName(TextStyle.SHORT, locale) } },
            selected = selected,
            onSelect = { selected = it },
            formatY = { res.minutesAxis(it) },
            timeAxis = true,
            height = 150.dp,
            description = stringResource(R.string.stats_weekdays_title),
        )
    }
}

/** Books finished per month since the first one (up to five years): a line with a wash. */
@Composable
internal fun CompletionsCard(state: StatsUiState, onRetry: () -> Unit) {
    val res = LocalContext.current.resources
    val locale = Locale.getDefault()
    ChartCard(
        title = stringResource(R.string.stats_completions_title),
        icon = "BookCheck",
        subtitle = stringResource(R.string.stats_completions_subtitle),
        load = state.reading.completions,
        onRetry = onRetry,
        isEmpty = { months -> months.sumOf { it.count } < 3 },
    ) { months ->
        var selected by rememberSaveable(months.size) { mutableIntStateOf(-1) }
        val sel = months.getOrNull(selected)
        Readout(
            value = sel?.let { res.books(it.count.roundToInt()) },
            label = sel?.let { "${Month.of(it.month).getDisplayName(TextStyle.FULL_STANDALONE, locale)} ${it.year}" } ?: "",
            hint = stringResource(R.string.stats_tap_hint),
        )
        val ink = rememberChartInk()
        LineChart(
            series = remember(months, ink) { listOf(LineSeries(months.map { it.count }, ink.series, area = true, endDot = true)) },
            labels = remember(months, locale) {
                val short = months.size <= 14
                months.map {
                    when {
                        short -> Month.of(it.month).getDisplayName(TextStyle.SHORT, locale)
                        it.month == 1 -> it.year.toString()
                        else -> null
                    }
                }
            },
            selected = selected,
            onSelect = { selected = it },
            formatY = { trim(it) },
            integerTicks = true,
            description = stringResource(R.string.stats_completions_title),
        )
    }
}

/**
 * Cumulative finished books against the goal line, the last 12 months (the web's
 * GoalTrajectoryChart). Without a yearly goal only the finished books are drawn, as the goal card.
 */
@Composable
internal fun TrajectoryCard(state: StatsUiState, onRetry: () -> Unit) {
    val res = LocalContext.current.resources
    val locale = Locale.getDefault()
    val ink = rememberChartInk()
    val load = state.reading.trajectory
    val noGoal = load is Load.Ready && load.data.goalBooks == null
    ChartCard(
        title = stringResource(R.string.stats_trajectory_title),
        icon = "Target",
        subtitle = stringResource(if (noGoal) R.string.stats_trajectory_subtitle_no_goal else R.string.stats_trajectory_subtitle),
        load = load,
        onRetry = onRetry,
        isEmpty = { t -> t.points.isEmpty() || (t.points.lastOrNull()?.actualCumulative ?: 0.0) < 2 },
    ) { t ->
        var selected by rememberSaveable(t.points.size) { mutableIntStateOf(-1) }
        val sel = t.points.getOrNull(selected)
        Readout(
            value = sel?.let { res.getQuantityString(R.plurals.stats_books_finished, it.actualCumulative.roundToInt(), it.actualCumulative.roundToInt()) },
            label = sel?.let {
                val month = "${Month.of(it.month).getDisplayName(TextStyle.SHORT, locale)} ${it.year}"
                if (t.goalBooks != null) stringResource(R.string.stats_goal_by_then, month, trim(it.targetCumulative)) else month
            } ?: "",
            hint = stringResource(R.string.stats_tap_hint),
        )
        LineChart(
            series = remember(t, ink) {
                listOfNotNull(
                    LineSeries(t.points.map { it.actualCumulative }, ink.series, area = true, endDot = true),
                    t.goalBooks?.let { LineSeries(t.points.map { it.targetCumulative }, ink.comparison, dashed = true) },
                )
            },
            labels = remember(t, locale) { t.points.mapIndexed { i, p -> if (i % 3 == 0) Month.of(p.month).getDisplayName(TextStyle.SHORT, locale) else null } },
            selected = selected,
            onSelect = { selected = it },
            formatY = { trim(it) },
            integerTicks = true,
            description = stringResource(R.string.stats_trajectory_title),
        )
        // One line needs no legend: the title and subtitle say what it is.
        if (t.goalBooks != null) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendKey(stringResource(R.string.stats_finished), ink.series, line = true)
                LegendKey(stringResource(R.string.stats_goal_line, t.goalBooks), ink.comparison, line = true, dashed = true)
            }
        }
    }
}

/** Reading time by genre as ranked bars (the web's treemap, readable on a phone): top 8 and Other. */
@Composable
internal fun GenresCard(state: StatsUiState, onRetry: () -> Unit) {
    val res = LocalContext.current.resources
    val other = stringResource(R.string.stats_other)
    ChartCard(
        title = stringResource(R.string.stats_genres_title),
        icon = "Tag",
        subtitle = periodText(state.period, state.days),
        load = state.reading.genres,
        onRetry = onRetry,
        placeholderHeight = 220.dp,
        isEmpty = { g -> g.count { it.readingSeconds > 0 } < 2 },
    ) { genres ->
        val total = genres.sumOf { it.readingSeconds }.coerceAtLeast(1.0)
        val items = remember(genres, other) { StatsMath.topWithOther(genres.map { RankedItem(it.genre, it.readingSeconds) }, 8, other) }
        RankedBars(
            items = items,
            valueLabel = { stringResource(R.string.stats_value_share, res.duration(it.value), (it.value / total * 100).roundToInt().toString()) },
        )
    }
}

/** Session length against progress made, one dot a session (the web's ReadingPaceScatterChart). */
@Composable
internal fun PaceCard(state: StatsUiState, onRetry: () -> Unit) {
    val res = LocalContext.current.resources
    val ink = rememberChartInk()
    val colors = OttershelfTheme.colors
    ChartCard(
        title = stringResource(R.string.stats_pace_title),
        icon = "Gauge",
        subtitle = stringResource(R.string.stats_pace_subtitle),
        load = state.reading.pace,
        onRetry = onRetry,
        placeholderHeight = 200.dp,
        isEmpty = { it.size < 10 },
    ) { points ->
        val xs = remember(points) { FloatArray(points.size) { (points[it].durationSeconds / 60).toFloat() } }
        val ys = remember(points) { FloatArray(points.size) { points[it].progressDelta.toFloat() } }
        val xTop = remember(xs) { StatsMath.niceTicks((xs.maxOrNull() ?: 60f).toDouble(), 4, true).last().toFloat() }
        val xTicks = remember(xTop) { StatsMath.niceTicks(xTop.toDouble(), 4, true).map { it.toFloat() to trim(it) } }
        val medianMinutes = remember(xs) { StatsMath.median(xs.map { it.toDouble() }) ?: 0.0 }
        val medianProgress = remember(ys) { StatsMath.median(ys.map { it.toDouble() }) ?: 0.0 }
        val groups = remember(points) { IntArray(points.size) }
        val dots = remember(ink) { listOf(ink.series) }
        Text(
            stringResource(R.string.stats_pace_typical, res.duration(medianMinutes * 60), trim(medianProgress)),
            style = MaterialTheme.typography.bodySmall,
            color = colors.foreground,
        )
        var selected by rememberSaveable(points.size) { mutableIntStateOf(-1) }
        Readout(
            value = if (selected in points.indices) stringResource(R.string.stats_pace_readout, trim(ys[selected].toDouble())) else null,
            label = if (selected in points.indices) stringResource(R.string.stats_pace_readout_label, res.duration(xs[selected] * 60.0)) else "",
            hint = stringResource(R.string.stats_tap_point_hint),
        )
        ScatterChart(
            xs = xs,
            ys = ys,
            groups = groups,
            colors = dots,
            xMax = xTop,
            yMax = ys.maxOrNull() ?: 1f,
            xTicks = xTicks,
            selected = selected,
            onSelect = { selected = it },
            formatY = { res.getString(R.string.stats_percent, trim(it)) },
            description = stringResource(R.string.stats_pace_title),
        )
        Text(
            stringResource(R.string.stats_axis_duration),
            style = MaterialTheme.typography.labelSmall,
            color = colors.mutedForeground,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * When sessions start (x, hour of day) and how long they last (y), weekdays against weekends: the
 * web colours all seven days, which no palette keeps apart on a scatter, so they fold into two.
 */
@Composable
internal fun ArchetypesCard(state: StatsUiState, onRetry: () -> Unit) {
    val res = LocalContext.current.resources
    val ink = rememberChartInk()
    val colors = OttershelfTheme.colors
    val locale = Locale.getDefault()
    ChartCard(
        title = stringResource(R.string.stats_archetypes_title),
        icon = "Sparkles",
        subtitle = stringResource(R.string.stats_archetypes_subtitle),
        load = state.reading.archetypes,
        onRetry = onRetry,
        placeholderHeight = 200.dp,
        isEmpty = { it.size < 3 },
    ) { points ->
        val weekend = remember(colors) { ChartPalette.secondSeries(colors.primary, colors.isDark) }
        val xs = remember(points) { FloatArray(points.size) { points[it].hour.toFloat().coerceIn(0f, 24f) } }
        val ys = remember(points) { FloatArray(points.size) { points[it].durationMinutes.toFloat() } }
        val groups = remember(points) { IntArray(points.size) { if (points[it].dayOfWeek == 0 || points[it].dayOfWeek == 6) 1 else 0 } }
        val dots = remember(ink, weekend) { listOf(ink.series, weekend) }
        var selected by rememberSaveable(points.size) { mutableIntStateOf(-1) }
        val sel = points.getOrNull(selected)
        Readout(
            value = sel?.let { res.duration(it.durationMinutes * 60) },
            label = sel?.let {
                stringResource(R.string.stats_day_at, StatsMath.weekday(StatsMath.mondayFirst(it.dayOfWeek)).getDisplayName(TextStyle.FULL, locale), StatsMath.clock(it.hour))
            } ?: "",
            hint = stringResource(R.string.stats_tap_point_hint),
        )
        ScatterChart(
            xs = xs,
            ys = ys,
            groups = groups,
            colors = dots,
            xMax = 24f,
            yMax = max(ys.maxOrNull() ?: 1f, 1f),
            xTicks = DayHourTicks,
            selected = selected,
            onSelect = { selected = it },
            formatY = { res.minutesAxis(it) },
            timeAxis = true,
            description = stringResource(R.string.stats_archetypes_title),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendKey(stringResource(R.string.stats_weekdays), ink.series, dot = true)
            LegendKey(stringResource(R.string.stats_weekends), weekend, dot = true)
        }
    }
}

/** Started, 25/50/75 % and finished as an ordinal ramp, with the finish rate against the period before. */
@Composable
internal fun FunnelCard(state: StatsUiState, onRetry: () -> Unit) {
    val colors = OttershelfTheme.colors
    ChartCard(
        title = stringResource(R.string.stats_funnel_title),
        icon = "Waypoints",
        subtitle = "${stringResource(R.string.stats_funnel_subtitle)} · ${periodText(state.period, state.days)}",
        load = state.reading.funnel,
        onRetry = onRetry,
        placeholderHeight = 200.dp,
        isEmpty = { it.current.started < 10 },
    ) { f ->
        val stages = StatsMath.funnel(f.current)
        val labels = listOf(
            stringResource(R.string.stats_funnel_started), stringResource(R.string.stats_funnel_25), stringResource(R.string.stats_funnel_50),
            stringResource(R.string.stats_funnel_75), stringResource(R.string.stats_funnel_finished),
        )
        val ramp = remember(colors) { ChartPalette.ordinalRamp(colors.primary, colors.card, 5) }
        val items = stages.mapIndexed { i, s -> RankedItem(labels[i], s.count.toDouble(), key = i.toString()) }
        RankedBars(
            items = items,
            valueLabel = { item -> val s = stages[item.key.toInt()]; stringResource(R.string.stats_funnel_value, s.count, (s.ofStarted * 100).roundToInt()) },
            colorOf = { _, i -> ramp[i] },
            scaleMax = stages.first().count.toDouble(),
        )
        Spacer(Modifier.height(12.dp))
        val rate = StatsMath.finishRate(f.current) ?: 0f
        Text(stringResource(R.string.stats_finish_rate, (rate * 100).roundToInt()), style = MaterialTheme.typography.titleSmall, color = colors.foreground)
        // Points against a period with a handful of books started would swing on one book.
        val previous = StatsMath.finishRate(f.previous?.takeIf { it.started >= StatsMath.MIN_FUNNEL_STARTED })
        if (previous != null) {
            DeltaLine(((rate - previous) * 100).roundToInt(), R.string.stats_points_vs_previous, "", unit = "")
        }
    }
}

/** How long books took from start to finish, in buckets, with the median and percentiles. */
@Composable
internal fun LatencyCard(state: StatsUiState, onRetry: () -> Unit) {
    val res = LocalContext.current.resources
    val colors = OttershelfTheme.colors
    val shortLabels = listOf(
        R.string.stats_latency_7, R.string.stats_latency_30, R.string.stats_latency_90, R.string.stats_latency_180,
        R.string.stats_latency_365, R.string.stats_latency_730, R.string.stats_latency_more,
    ).map { stringResource(it) }
    ChartCard(
        title = stringResource(R.string.stats_latency_title),
        icon = "Timer",
        subtitle = stringResource(R.string.stats_latency_subtitle),
        load = state.reading.latency,
        onRetry = onRetry,
        isEmpty = { it.totalCompletions < 5 },
    ) { l ->
        Row(Modifier.fillMaxWidth()) {
            listOf(
                R.string.stats_median to l.medianDays,
                R.string.stats_p75 to l.percentile75Days,
                R.string.stats_p90 to l.percentile90Days,
            ).forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    Text(value?.let { res.getString(R.string.stats_days_short, trim(it)) } ?: "–", style = figureStyle(18), color = colors.foreground)
                    Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = colors.mutedForeground)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        var selected by rememberSaveable(l.buckets.size) { mutableIntStateOf(-1) }
        val sel = l.buckets.getOrNull(selected)
        Readout(
            value = sel?.let { res.books(it.count.roundToInt()) },
            // The axis's short labels ("1–2 y") don't read as "days"; the readout spells the range out.
            label = sel?.let {
                when {
                    it.maxDays == null -> stringResource(R.string.stats_latency_readout_over, (it.minDays - 1).coerceAtLeast(0))
                    it.minDays <= 0 -> stringResource(R.string.stats_latency_readout_upto, it.maxDays)
                    else -> stringResource(R.string.stats_latency_readout_range, it.minDays, it.maxDays)
                }
            } ?: "",
            hint = stringResource(R.string.stats_tap_hint),
        )
        ColumnChart(
            values = remember(l) { l.buckets.map { it.count } },
            labels = remember(l, shortLabels) { l.buckets.indices.map { shortLabels.getOrElse(it) { l.buckets[it].label } } },
            selected = selected,
            onSelect = { selected = it },
            formatY = { trim(it) },
            integerTicks = true,
            height = 150.dp,
            description = stringResource(R.string.stats_latency_title),
        )
    }
}
