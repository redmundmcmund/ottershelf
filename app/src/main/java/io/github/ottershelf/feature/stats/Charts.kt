package io.github.ottershelf.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.ui.theme.OttershelfTheme
import kotlin.math.max
import kotlin.math.min

/*
 * The charts, drawn on Canvas with the dataviz mark specs: bars at most 24dp thick with a 4dp
 * rounded data end and a square base, separated by real gaps; 2dp lines with round joins; hairline
 * solid gridlines one step off the surface; muted axis text; the value readout above the plot
 * (tap or drag), never a number on every mark. Geometry and text are built in drawWithCache; the
 * selection is a State read only while drawing, so a tap redraws without rebuilding anything. That
 * holds only while the cache block's other captures stay the same objects: callers remember the
 * lists they pass (values, labels, series) per data set.
 */

/** The ink every chart shares, from the theme. */
@Immutable
internal data class ChartInk(
    val series: Color,
    val comparison: Color,
    val grid: Color,
    val axisText: Color,
    val surface: Color,
    val text: Color,
)

@Composable
internal fun rememberChartInk(): ChartInk {
    val c = OttershelfTheme.colors
    return remember(c) {
        ChartInk(
            series = c.primary,
            comparison = ChartPalette.comparison(c.primary, c.mutedForeground, c.foreground, c.card),
            grid = c.border,
            axisText = c.mutedForeground,
            surface = c.background,
            text = c.foreground,
        )
    }
}

private val AxisStyle = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
private val YGutter = 34.dp
private val XBand = 18.dp
private val PlotTop = 8.dp

/** The plot's rectangle inside a chart of [size] (a y-axis gutter on the left, x labels below). */
private class Plot(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

private fun Density.plot(size: Size, yAxis: Boolean = true, xAxis: Boolean = true) = Plot(
    left = if (yAxis) YGutter.toPx() else 0f,
    top = PlotTop.toPx(),
    right = size.width - 2.dp.toPx(),
    bottom = size.height - if (xAxis) XBand.toPx() else 2.dp.toPx(),
)

/** Horizontal gridlines at [ticks] with their labels in the gutter; returns y for a value. */
private class YAxis(val ticks: List<Double>, val labels: List<TextLayoutResult>) {
    val max get() = ticks.last().takeIf { it > 0 } ?: 1.0
}

private fun ticksFor(maxValue: Double, integer: Boolean, time: Boolean): List<Double> =
    if (time) StatsMath.timeTicks(maxValue) else StatsMath.niceTicks(maxValue, 3, integer)

private fun buildYAxis(measurer: TextMeasurer, maxValue: Double, integer: Boolean, format: (Double) -> String, time: Boolean = false): YAxis {
    val ticks = ticksFor(maxValue, integer, time)
    return YAxis(ticks, ticks.map { measurer.measure(format(it), AxisStyle) })
}

private fun DrawScope.drawYAxis(axis: YAxis, plot: Plot, ink: ChartInk) {
    val hairline = 1.dp.toPx().coerceAtMost(1.5f)
    axis.ticks.forEachIndexed { i, tick ->
        val y = plot.bottom - (tick / axis.max * plot.height).toFloat()
        drawLine(ink.grid, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = hairline)
        val label = axis.labels[i]
        drawText(label, color = ink.axisText, topLeft = Offset(plot.left - label.size.width - 6.dp.toPx(), y - label.size.height / 2f))
    }
}

/** X labels centred at [centerOf] each index; labels that would touch the previous one are skipped. */
private fun DrawScope.drawXLabels(labels: List<TextLayoutResult?>, centerOf: (Int) -> Float, plot: Plot, ink: ChartInk) {
    var lastRight = Float.NEGATIVE_INFINITY
    val gap = 6.dp.toPx()
    labels.forEachIndexed { i, label ->
        if (label == null) return@forEachIndexed
        val x = (centerOf(i) - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
        if (x < lastRight + gap) return@forEachIndexed
        drawText(label, color = ink.axisText, topLeft = Offset(x, plot.bottom + 4.dp.toPx()))
        lastRight = x + label.size.width
    }
}

/** Taps and horizontal drags report the index under the finger (the hit target is the whole slot). */
private fun Modifier.selectByX(key: Any, count: Int, indexAt: Density.(Float, Float) -> Int, onSelect: (Int) -> Unit): Modifier = this
    .pointerInput(key, count) { detectTapGestures { onSelect(indexAt(it.x, size.width.toFloat())) } }
    .pointerInput(key, count) {
        detectHorizontalDragGestures(onDragStart = { onSelect(indexAt(it.x, size.width.toFloat())) }) { change, _ ->
            change.consume()
            onSelect(indexAt(change.position.x, size.width.toFloat()))
        }
    }

/**
 * Columns from one baseline, [values] oldest first, in [color]; [previous] (the same length) is
 * drawn as a grey step line over them (the previous period). [selected] lifts one column (the
 * others fade) and draws a hairline through it.
 */
@Composable
internal fun ColumnChart(
    values: List<Double>,
    labels: List<String?>,
    selected: Int,
    onSelect: (Int) -> Unit,
    formatY: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    color: Color = rememberChartInk().series,
    colors: List<Color>? = null,
    previous: List<Double>? = null,
    integerTicks: Boolean = false,
    timeAxis: Boolean = false,
    description: String = "",
) {
    val ink = rememberChartInk()
    val measurer = rememberTextMeasurer()
    val selection = rememberUpdatedState(selected)
    val count = values.size
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .selectByX(values, count, { x, width ->
                val p = plot(Size(width, 0f))
                ((x - p.left) / (p.width / count)).toInt().coerceIn(0, count - 1)
            }, onSelect)
            .drawWithCache {
                val p = plot(size)
                val maxValue = max(values.maxOrNull() ?: 0.0, previous?.maxOrNull() ?: 0.0)
                val axis = buildYAxis(measurer, maxValue, integerTicks, formatY, timeAxis)
                val slot = p.width / count
                val gap = min(2.dp.toPx(), slot * 0.35f)
                val barWidth = min(24.dp.toPx(), slot - gap)
                val radius = min(4.dp.toPx(), barWidth / 2f)
                val corner = CornerRadius(radius, radius)
                val tops = FloatArray(count) { i -> p.bottom - (values[i] / axis.max * p.height).toFloat() }
                val lefts = FloatArray(count) { i -> p.left + slot * i + (slot - barWidth) / 2f }
                // The previous period: a short grey mark across each slot at its value (none for 0).
                val stepLine = previous?.let { prev ->
                    Path().apply {
                        val inset = min(slot * 0.15f, 3.dp.toPx())
                        prev.forEachIndexed { i, v ->
                            if (v <= 0.0) return@forEachIndexed
                            val y = p.bottom - (v / axis.max * p.height).toFloat()
                            moveTo(p.left + slot * i + inset, y)
                            lineTo(p.left + slot * (i + 1) - inset, y)
                        }
                    }
                }
                val lineStroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val xLabels = labels.map { it?.let { t -> measurer.measure(t, AxisStyle) } }
                onDrawBehind {
                    drawYAxis(axis, p, ink)
                    val sel = selection.value
                    for (i in 0 until count) {
                        val top = tops[i]
                        if (values[i] <= 0.0) continue
                        val alpha = if (sel >= 0 && sel != i) 0.45f else 1f
                        val c = colors?.getOrNull(i) ?: color
                        val h = p.bottom - top
                        if (h >= radius * 2) {
                            drawRoundRect(c, Offset(lefts[i], top), Size(barWidth, h), corner, alpha = alpha)
                            drawRect(c, Offset(lefts[i], p.bottom - radius), Size(barWidth, radius), alpha = alpha)
                        } else {
                            drawRect(c, Offset(lefts[i], top), Size(barWidth, max(h, 1f)), alpha = alpha)
                        }
                    }
                    if (stepLine != null) drawPath(stepLine, ink.comparison, style = lineStroke)
                    if (sel in 0 until count) {
                        val x = p.left + slot * sel + slot / 2f
                        drawLine(ink.text.copy(alpha = 0.35f), Offset(x, p.top), Offset(x, p.bottom), strokeWidth = 1.dp.toPx())
                    }
                    drawXLabels(xLabels, { p.left + slot * it + slot / 2f }, p, ink)
                }
            },
    )
}

/** One line of a [LineChart]; null values leave a gap (months still to come). */
@Immutable
internal data class LineSeries(val values: List<Double?>, val color: Color, val area: Boolean = false, val dashed: Boolean = false, val endDot: Boolean = false)

/**
 * Lines over shared x positions ([count] points, evenly spaced), 2dp, round joins; an area series
 * gets a 10% wash. The crosshair snaps to the nearest x and rings each series' point there.
 */
@Composable
internal fun LineChart(
    series: List<LineSeries>,
    labels: List<String?>,
    selected: Int,
    onSelect: (Int) -> Unit,
    formatY: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    integerTicks: Boolean = false,
    timeAxis: Boolean = false,
    description: String = "",
) {
    val ink = rememberChartInk()
    val measurer = rememberTextMeasurer()
    val selection = rememberUpdatedState(selected)
    val count = labels.size
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .selectByX(series, count, { x, width ->
                val p = plot(Size(width, 0f))
                val step = p.width / max(1, count - 1)
                ((x - p.left) / step + 0.5f).toInt().coerceIn(0, count - 1)
            }, onSelect)
            .drawWithCache {
                val p = plot(size)
                val maxValue = series.maxOf { s -> s.values.maxOf { it ?: 0.0 } }
                val axis = buildYAxis(measurer, maxValue, integerTicks, formatY, timeAxis)
                val step = p.width / max(1, count - 1)
                fun x(i: Int) = p.left + step * i
                fun y(v: Double) = p.bottom - (v / axis.max * p.height).toFloat()
                val paths = series.map { s ->
                    val line = Path()
                    var started = false
                    var lastX = 0f
                    var firstX = 0f
                    s.values.forEachIndexed { i, v ->
                        if (v == null) return@forEachIndexed
                        if (!started) { line.moveTo(x(i), y(v)); firstX = x(i); started = true } else line.lineTo(x(i), y(v))
                        lastX = x(i)
                    }
                    val area = if (s.area && started) Path().apply {
                        addPath(line)
                        lineTo(lastX, p.bottom)
                        lineTo(firstX, p.bottom)
                        close()
                    } else null
                    line to area
                }
                val lastIndex = series.map { s -> s.values.indexOfLast { it != null } }
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val dashedStroke = Stroke(
                    width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                )
                val xLabels = labels.map { it?.let { t -> measurer.measure(t, AxisStyle) } }
                val dot = 4.dp.toPx()
                val ring = 2.dp.toPx()
                onDrawBehind {
                    drawYAxis(axis, p, ink)
                    series.forEachIndexed { k, s ->
                        val (line, area) = paths[k]
                        if (area != null) drawPath(area, s.color, alpha = 0.10f)
                        drawPath(line, s.color, style = if (s.dashed) dashedStroke else stroke)
                        val last = lastIndex[k]
                        if (s.endDot && last >= 0) {
                            val c = Offset(x(last), y(s.values[last]!!))
                            drawCircle(ink.surface, dot + ring, c)
                            drawCircle(s.color, dot, c)
                        }
                    }
                    val sel = selection.value
                    if (sel in 0 until count) {
                        drawLine(ink.text.copy(alpha = 0.35f), Offset(x(sel), p.top), Offset(x(sel), p.bottom), strokeWidth = 1.dp.toPx())
                        series.forEach { s ->
                            val v = s.values.getOrNull(sel) ?: return@forEach
                            val c = Offset(x(sel), y(v))
                            drawCircle(ink.surface, dot + ring, c)
                            drawCircle(s.color, dot, c)
                        }
                    }
                    drawXLabels(xLabels, ::x, p, ink)
                }
            },
    )
}

/**
 * Dots at ([xs], [ys]) in plot units (0..[xMax], 0..[yMax]), [groups] indexing [colors]; 50%
 * fills like the web's, the selected one full with a surface ring. A tap picks the nearest point.
 */
@Composable
internal fun ScatterChart(
    xs: FloatArray,
    ys: FloatArray,
    groups: IntArray,
    colors: List<Color>,
    xMax: Float,
    yMax: Float,
    xTicks: List<Pair<Float, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    formatY: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    timeAxis: Boolean = false,
    description: String = "",
) {
    val ink = rememberChartInk()
    val measurer = rememberTextMeasurer()
    val selection = rememberUpdatedState(selected)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(xs, ys) {
                detectTapGestures { tap ->
                    val p = plot(Size(size.width.toFloat(), size.height.toFloat()))
                    val yTop = ticksFor(yMax.toDouble(), false, timeAxis).last().toFloat()
                    var best = -1
                    var bestD = Float.MAX_VALUE
                    for (i in xs.indices) {
                        val px = p.left + xs[i] / xMax * p.width
                        val py = p.bottom - ys[i] / yTop * p.height
                        val d = (px - tap.x) * (px - tap.x) + (py - tap.y) * (py - tap.y)
                        if (d < bestD) { bestD = d; best = i }
                    }
                    val reach = 32.dp.toPx()
                    onSelect(if (bestD <= reach * reach) best else -1)
                }
            }
            .drawWithCache {
                val p = plot(size)
                val axis = buildYAxis(measurer, yMax.toDouble(), false, formatY, timeAxis)
                val top = axis.max.toFloat()
                val px = FloatArray(xs.size) { p.left + xs[it] / xMax * p.width }
                val py = FloatArray(ys.size) { p.bottom - ys[it] / top * p.height }
                val tickLabels = xTicks.map { (v, t) -> (p.left + v / xMax * p.width) to measurer.measure(t, AxisStyle) }
                val r = 4.dp.toPx()
                val ring = 2.dp.toPx()
                val hairline = 1.dp.toPx()
                onDrawBehind {
                    drawYAxis(axis, p, ink)
                    tickLabels.forEach { (x, label) ->
                        drawLine(ink.grid, Offset(x, p.bottom), Offset(x, p.bottom + 3.dp.toPx()), strokeWidth = hairline)
                        drawText(label, color = ink.axisText, topLeft = Offset((x - label.size.width / 2f).coerceIn(0f, size.width - label.size.width), p.bottom + 4.dp.toPx()))
                    }
                    for (i in xs.indices) drawCircle(colors[groups[i]], r, Offset(px[i], py[i]), alpha = 0.5f)
                    val sel = selection.value
                    if (sel in xs.indices) {
                        val c = Offset(px[sel], py[sel])
                        drawCircle(ink.surface, r + ring, c)
                        drawCircle(colors[groups[sel]], r, c)
                    }
                }
            },
    )
}

/**
 * The year as two half-year calendars (January-June over July-December), Monday-first rows and
 * one column a week, so each cell stays a tappable size on a phone. [levelColors] are levels 1..4
 * ([StatsMath.heatLevel]); level 0 is [empty]. Future days are left blank; today is outlined.
 */
@Composable
internal fun YearHeatmap(
    days: List<HeatDay>,
    levelColors: List<Color>,
    empty: Color,
    monthLabel: (Int) -> String,
    dayLabels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    description: String = "",
) {
    val ink = rememberChartInk()
    val measurer = rememberTextMeasurer()
    val selection = rememberUpdatedState(selected)
    if (days.isEmpty()) return
    val grid = remember(days) { HeatGrid(days) }
    val columns = grid.columns
    val rows = grid.rows
    val halfOf = grid.halfOf
    val maxWeeks = grid.maxWeeks
    val gutter = 18.dp
    val monthBand = 14.dp
    val halfGap = 10.dp
    Box(
        modifier
            .fillMaxWidth()
            .semantics { contentDescription = description }
            .drawWithCache {
                val cell = (size.width - gutter.toPx()) / maxWeeks
                val gap = max(1.5f, cell * 0.16f)
                val halfHeight = monthBand.toPx() + cell * 7
                val cellSize = Size(cell - gap, cell - gap)
                val corner = CornerRadius(min(3.dp.toPx(), cell * 0.22f))
                fun topLeft(i: Int) = Offset(
                    gutter.toPx() + columns[i] * cell,
                    halfOf[i] * (halfHeight + halfGap.toPx()) + monthBand.toPx() + rows[i] * cell,
                )
                val tl = Array(days.size) { topLeft(it) }
                val months = (1..12).map { m ->
                    val first = days.indexOfFirst { it.date.monthValue == m }
                    val label = measurer.measure(monthLabel(m), AxisStyle)
                    Offset(gutter.toPx() + (columns[first] + if (rows[first] != 0) 1 else 0) * cell, halfOf[first] * (halfHeight + halfGap.toPx())) to label
                }
                val weekdayLabels = listOf(0, 2, 4, 6).map { r -> r to measurer.measure(dayLabels[r], AxisStyle) }
                val levels = IntArray(days.size) { StatsMath.heatLevel(days[it].seconds) }
                val todayIndex = days.indexOfLast { !it.future }
                val outline = Stroke(width = 1.5.dp.toPx())
                onDrawBehind {
                    months.forEach { (at, label) -> drawText(label, color = ink.axisText, topLeft = at) }
                    for (h in 0..1) {
                        val y0 = h * (halfHeight + halfGap.toPx()) + monthBand.toPx()
                        weekdayLabels.forEach { (r, label) ->
                            drawText(label, color = ink.axisText, topLeft = Offset(0f, y0 + r * cell + (cell - gap - label.size.height) / 2f))
                        }
                    }
                    val sel = selection.value
                    for (i in days.indices) {
                        if (days[i].future) continue
                        val level = levels[i]
                        val c = if (level == 0) empty else levelColors[level - 1]
                        drawRoundRect(c, tl[i], cellSize, corner, alpha = if (sel >= 0 && sel != i) 0.7f else 1f)
                    }
                    if (todayIndex >= 0) drawRoundRect(ink.text.copy(alpha = 0.6f), tl[todayIndex], cellSize, corner, style = outline)
                    if (sel in days.indices) drawRoundRect(ink.text, tl[sel], cellSize, corner, style = outline)
                }
            }
            .pointerInput(days) {
                detectTapGestures { tap ->
                    val cell = (size.width - gutter.toPx()) / maxWeeks
                    val halfHeight = monthBand.toPx() + cell * 7
                    val h = if (tap.y > halfHeight + halfGap.toPx() / 2) 1 else 0
                    val col = ((tap.x - gutter.toPx()) / cell).toInt()
                    val row = ((tap.y - h * (halfHeight + halfGap.toPx()) - monthBand.toPx()) / cell).toInt()
                    val hit = days.indices.firstOrNull { halfOf[it] == h && columns[it] == col && rows[it] == row && !days[it].future }
                    onSelect(hit ?: -1)
                }
            },
    ) {
        // Height: two halves of seven rows (cell = width / weeks), their month bands and the gap.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cell = (maxWidth - gutter) / maxWeeks
            Spacer(Modifier.height((monthBand + cell * 7) * 2 + halfGap))
        }
    }
}

/** Where each day of a [YearHeatmap] sits: its half-year, its week column and weekday row (Monday first). */
private class HeatGrid(days: List<HeatDay>) {
    val columns = IntArray(days.size)
    val rows = IntArray(days.size)
    val halfOf = IntArray(days.size)
    val maxWeeks: Int

    init {
        val julyIndex = days.indexOfFirst { it.date.monthValue == 7 }
        val weeks = IntArray(2)
        listOf(0 until julyIndex, julyIndex until days.size).forEachIndexed { h, range ->
            val firstOffset = days[range.first].date.dayOfWeek.value - 1
            for (i in range) {
                val n = i - range.first + firstOffset
                columns[i] = n / 7
                rows[i] = n % 7
                halfOf[i] = h
            }
            weeks[h] = (range.last - range.first + firstOffset) / 7 + 1
        }
        maxWeeks = max(weeks[0], weeks[1])
    }
}

/** A legend key: a short line (lines) or a small rounded square (bars, cells), then the label. */
@Composable
internal fun LegendKey(label: String, color: Color, line: Boolean = false, dashed: Boolean = false, dot: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(width = if (line) 16.dp else 10.dp, height = 10.dp)) {
            if (line) {
                drawLine(
                    color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
                )
            } else if (dot) {
                drawCircle(color, size.minDimension / 2)
            } else {
                drawRoundRect(color, cornerRadius = CornerRadius(2.dp.toPx()))
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = OttershelfTheme.colors.mutedForeground, maxLines = 1)
    }
}

/**
 * The readout above a plot: the selected value (strong) and what it is (dim), or a dim hint when
 * nothing is selected. Fixed height, so selecting never moves the chart. [action] (optional) sits
 * at the end, such as "Open day".
 */
@Composable
internal fun Readout(value: String?, label: String, hint: String, action: (@Composable () -> Unit)? = null) {
    val colors = OttershelfTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
        if (value == null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else {
            Column(Modifier.weight(1f)) {
                Text(value, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(label, style = MaterialTheme.typography.labelSmall, color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            action?.invoke()
        }
    }
}

/**
 * Ranked horizontal bars, one per row: the label and its value on one line, a thin bar under them
 * scaled to the largest (bars are the whole row's hit target when [onClick] is given).
 */
@Composable
internal fun RankedBars(
    items: List<RankedItem>,
    valueLabel: @Composable (RankedItem) -> String,
    modifier: Modifier = Modifier,
    colorOf: (RankedItem, Int) -> Color = { _, _ -> Color.Unspecified },
    onClick: ((RankedItem) -> Unit)? = null,
    scaleMax: Double? = null,
) {
    val ink = rememberChartInk()
    val colors = OttershelfTheme.colors
    val track = colors.muted
    val max = scaleMax ?: (items.maxOfOrNull { it.value } ?: 1.0)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { index, item ->
            val c = colorOf(item, index).takeIf { it != Color.Unspecified } ?: if (item.other) ink.comparison else ink.series
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (onClick != null && !item.other) Modifier.clickable { onClick(item) } else Modifier),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.label, style = MaterialTheme.typography.bodySmall, color = colors.foreground,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(valueLabel(item), style = MaterialTheme.typography.labelMedium, color = colors.mutedForeground, maxLines = 1)
                }
                Spacer(Modifier.height(4.dp))
                val fraction = if (max > 0) (item.value / max).toFloat().coerceIn(0f, 1f) else 0f
                Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                    val r = CornerRadius(4.dp.toPx())
                    drawRoundRect(track, cornerRadius = r)
                    if (fraction > 0f) drawRoundRect(c, size = Size(max(size.height, size.width * fraction), size.height), cornerRadius = r)
                }
            }
        }
    }
}

/** A thin track with a fill, for the overview's small progress lines. */
@Composable
internal fun MeterBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val track = OttershelfTheme.colors.muted
    Box(modifier.fillMaxWidth().height(6.dp).background(track, RoundedCornerShape(3.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).background(color, RoundedCornerShape(3.dp)))
    }
}
