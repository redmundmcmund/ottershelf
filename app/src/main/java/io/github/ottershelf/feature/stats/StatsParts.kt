package io.github.ottershelf.feature.stats

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.ui.components.CardTitle
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.SkeletonBox
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * A chart's card (the web's ChartCard in the Nexus DashCard): the accent icon and title, a dim
 * subtitle (its window), then the chart for [load] once ready; a placeholder the chart's height
 * while loading, "not enough reading yet" when [isEmpty], and a tap-to-retry error. A chart
 * reloading for a new window stays, faded, until the new one arrives.
 */
@Composable
internal fun <T> ChartCard(
    title: String,
    icon: String,
    load: Load<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    placeholderHeight: Dp = 180.dp,
    isEmpty: (T) -> Boolean = { false },
    emptyMessage: String = stringResource(R.string.stats_not_enough),
    content: @Composable ColumnScope.(T) -> Unit,
) {
    val colors = OttershelfTheme.colors
    DashCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp)) {
        CardTitle(title, icon = icon)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.mutedForeground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, top = 1.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        when (load) {
            Load.Loading -> SkeletonBox(Modifier.fillMaxWidth().height(placeholderHeight), shape = RoundedCornerShape(OttershelfTheme.radii.lg))
            is Load.Failed -> ErrorState(
                onRetry = onRetry,
                message = stringResource(R.string.stats_load_failed),
                compact = true,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 20.dp, horizontal = 12.dp),
            )
            is Load.Ready -> if (isEmpty(load.data)) {
                EmptyState(emptyMessage, icon = icon, compact = true, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 18.dp))
            } else {
                Column(Modifier.fillMaxWidth().alpha(if (load.stale) 0.5f else 1f)) { content(load.data) }
            }
        }
    }
}

/** A headline figure: a framed icon, the value (semibold sans, proportional) and a dim label. */
@Composable
internal fun StatBox(icon: String, value: String, label: String, modifier: Modifier = Modifier, tint: Color = OttershelfTheme.colors.primary, compact: Boolean = false) {
    val colors = OttershelfTheme.colors
    if (compact) {
        DashCard(modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(tint.copy(alpha = 0.15f), RoundedCornerShape(OttershelfTheme.radii.lg)), contentAlignment = Alignment.Center) {
                    LucideIcon(icon, contentDescription = null, tint = tint, size = 15.dp)
                }
                Spacer(Modifier.size(10.dp))
                Column {
                    Text(value, style = figureStyle(17), color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(label, style = MaterialTheme.typography.labelSmall, color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        return
    }
    DashCard(modifier, contentPadding = PaddingValues(12.dp)) {
        Box(Modifier.size(28.dp).background(tint.copy(alpha = 0.15f), RoundedCornerShape(OttershelfTheme.radii.lg)), contentAlignment = Alignment.Center) {
            LucideIcon(icon, contentDescription = null, tint = tint, size = 15.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(value, style = figureStyle(20), color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.mutedForeground, maxLines = 2)
    }
}

/** The big numbers (the Dashboard streak's style): the sans, bold. */
@Composable
internal fun figureStyle(size: Int): TextStyle = MaterialTheme.typography.headlineLarge.copy(
    fontFamily = MaterialTheme.typography.titleMedium.fontFamily,
    fontSize = size.sp,
    lineHeight = (size + 4).sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.sp,
)

/**
 * A change against an earlier period: an arrow icon and the signed text, green when up, amber
 * when down (up is good for reading time), never colour alone.
 */
@Composable
internal fun DeltaLine(percent: Int?, template: Int, noneText: String, modifier: Modifier = Modifier, unit: String = "%") {
    if (percent == null) {
        TrendLine(noneText, direction = null, modifier = modifier)
    } else {
        TrendLine(stringResource(template, "${if (percent >= 0) "+" else "−"}${abs(percent)}$unit"), direction = percent.sign, modifier = modifier)
    }
}

/**
 * Reading time against an earlier period ([StatsMath.change]): a percentage in [percentTemplate],
 * or, on a base too small for one, the earlier time in [upFromTemplate] / [downFromTemplate]
 * ("Up from 18 s the 7 days before"); [noneText] when there was nothing before.
 */
@Composable
internal fun TimeChangeLine(change: Change, percentTemplate: Int, upFromTemplate: Int, downFromTemplate: Int, noneText: String, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    when (change) {
        Change.NoBase -> DeltaLine(null, percentTemplate, noneText, modifier)
        is Change.Percent -> DeltaLine(change.percent, percentTemplate, noneText, modifier)
        is Change.From -> TrendLine(
            stringResource(if (change.up) upFromTemplate else downFromTemplate, res.duration(change.previous)),
            direction = if (change.up) 1 else -1,
            modifier = modifier,
        )
    }
}

/** [text] after an arrow for [direction] (1 up, -1 down, 0 flat); plain muted text when null. */
@Composable
private fun TrendLine(text: String, direction: Int?, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    if (direction == null) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = colors.mutedForeground, modifier = modifier)
        return
    }
    val tint = when {
        direction > 0 -> colors.success
        direction < 0 -> colors.warning
        else -> colors.mutedForeground
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LucideIcon(if (direction >= 0) "TrendingUp" else "TrendingDown", contentDescription = null, tint = tint, size = 13.dp)
        Spacer(Modifier.size(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = colors.mutedForeground, maxLines = 2)
    }
}

/** A pill of text on a tinted background with an icon (the web's status pills). */
@Composable
internal fun StatusPill(text: String, color: Color, icon: String) {
    Row(
        Modifier.background(color.copy(alpha = 0.15f), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        LucideIcon(icon, contentDescription = null, tint = color, size = 13.dp)
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/** A small text button in the accent (a chart's "Open day"). */
@Composable
internal fun LinkAction(text: String, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    Row(
        Modifier
            .border(1.dp, colors.border, RoundedCornerShape(OttershelfTheme.radii.md))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = colors.primary)
        LucideIcon("ChevronRight", contentDescription = null, tint = colors.primary, size = 14.dp)
    }
}

// --- text -------------------------------------------------------------------------------------

/** "45 s", "25 min", "1 h 5 min", "3 h". */
internal fun Resources.duration(seconds: Double): String {
    val s = seconds.roundToInt()
    if (s in 1..59) return getString(R.string.stats_seconds, s)
    val minutes = (seconds / 60.0).roundToInt()
    return when {
        minutes < 60 -> getString(R.string.stats_minutes, minutes)
        minutes % 60 == 0 -> getString(R.string.stats_hours, minutes / 60)
        else -> getString(R.string.stats_hours_minutes, minutes / 60, minutes % 60)
    }
}

@Composable
internal fun durationText(seconds: Double): String = LocalContext.current.resources.duration(seconds)

/** An axis label for minutes: "30m", "1.5h". */
internal fun Resources.minutesAxis(minutes: Double): String =
    if (minutes < 60) getString(R.string.stats_axis_minutes, trim(minutes)) else getString(R.string.stats_axis_hours, trim(minutes / 60))

/** 2.0 -> "2", 2.5 -> "2.5", 2.25 -> "2.3". */
internal fun trim(value: Double): String {
    val rounded = (value * 10).roundToInt() / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else String.format(Locale.getDefault(), "%.1f", rounded)
}

internal fun Resources.books(count: Int): String = getQuantityString(R.plurals.stats_books, count, count)
internal fun Resources.sessions(count: Int): String = getQuantityString(R.plurals.stats_sessions, count, count)
internal fun Resources.days(count: Int): String = getQuantityString(R.plurals.stats_days, count, count)

/** "Last 90 days" / "1 January to today". */
@Composable
internal fun periodText(period: StatsPeriod, days: Int): String =
    if (period == StatsPeriod.YEAR) stringResource(R.string.stats_this_year_window) else stringResource(R.string.stats_last_n_days, days)
