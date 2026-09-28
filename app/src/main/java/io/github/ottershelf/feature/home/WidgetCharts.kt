package io.github.ottershelf.feature.home

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ottershelf.feature.home.model.RhythmDay
import io.github.ottershelf.ui.theme.OttershelfTheme
import kotlin.math.max

/*
 * The Dashboard's two drawn charts, with the dataviz rules adapted to the BookOrbit tokens: a single
 * series each, so one hue (the user's accent) and no legend; the unfilled part a lighter step of the
 * same hue (goal ring) or the muted token (a day with no reading); thin bars capped at 24dp with a
 * 4dp rounded data end, square at the baseline, and at least a 2dp surface gap between them; a
 * recessive 1dp baseline. Shapes are built once per size and data (drawWithCache); a frame only
 * paints them. Labels and values are text tokens, never the accent.
 */

/**
 * The Reading Goal ring (the web's ECharts pie, radius 78%..100%): [done] of [goal] as an accent arc
 * from the top, clockwise, over a faint accent track.
 */
@Composable
internal fun GoalRing(done: Int, goal: Int, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val arc = colors.primary
    val track = colors.primary.copy(alpha = 0.16f)
    val fraction = if (goal > 0) (done.toFloat() / goal).coerceIn(0f, 1f) else 0f
    Box(
        modifier.drawWithCache {
            val thickness = size.minDimension / 2f * 0.22f
            val arcSize = Size(size.minDimension - thickness, size.minDimension - thickness)
            val topLeft = Offset((size.width - arcSize.width) / 2f, (size.height - arcSize.height) / 2f)
            val trackStroke = Stroke(width = thickness)
            val arcStroke = Stroke(width = thickness, cap = if (fraction >= 1f) StrokeCap.Butt else StrokeCap.Round)
            onDrawBehind {
                drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = trackStroke)
                if (fraction > 0f) drawArc(arc, -90f, 360f * fraction, useCenter = false, topLeft = topLeft, size = arcSize, style = arcStroke)
            }
        },
    )
}

/**
 * Reading Rhythm's columns: one per day (14, oldest first), height by reading time against the
 * busiest day; a day without reading is a 3dp muted stub. Tapping a column (or dragging across
 * them) picks it for the readout above ([onSelect]); tapping the picked one again lets go. The
 * picked column is full accent, the others 70%.
 */
@Composable
internal fun RhythmChart(
    days: List<RhythmDay>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = OttershelfTheme.colors
    val active = colors.primary.copy(alpha = 0.7f)
    val picked = colors.primary
    val empty = colors.muted.copy(alpha = 0.5f)
    val baseline = colors.border
    val current by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    val peak = remember(days) { days.maxOfOrNull { it.readingSeconds }?.coerceAtLeast(1.0) ?: 1.0 }
    val count = days.size

    fun indexAt(x: Float, width: Int): Int? =
        if (count == 0 || width <= 0) null else (x / width * count).toInt().coerceIn(0, count - 1)

    Box(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(count) {
                detectTapGestures { offset ->
                    val i = indexAt(offset.x, size.width)
                    select(if (i == current) null else i)
                }
            }
            .pointerInput(count) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> select(indexAt(offset.x, size.width)) },
                ) { change, _ ->
                    change.consume()
                    select(indexAt(change.position.x, size.width))
                }
            }
            .drawWithCache {
                val slot = if (count > 0) size.width / count else size.width
                val gap = 3.dp.toPx()
                val barWidth = (slot - gap).coerceIn(1f, 24.dp.toPx())
                val stub = 3.dp.toPx()
                val corner = CornerRadius(minOf(4.dp.toPx(), barWidth / 2f))
                val bars = days.mapIndexed { i, day ->
                    val height = max(stub, (day.readingSeconds / peak * size.height).toFloat())
                    val left = i * slot + (slot - barWidth) / 2f
                    Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = left,
                                top = size.height - height,
                                right = left + barWidth,
                                bottom = size.height,
                                topLeftCornerRadius = corner,
                                topRightCornerRadius = corner,
                                bottomRightCornerRadius = CornerRadius.Zero,
                                bottomLeftCornerRadius = CornerRadius.Zero,
                            ),
                        )
                    }
                }
                val read = days.map { it.readingSeconds > 0 }
                val hairline = 1.dp.toPx()
                onDrawBehind {
                    val pickedIndex = current
                    bars.forEachIndexed { i, path ->
                        drawPath(path, if (i == pickedIndex) picked else if (read[i]) active else empty)
                    }
                    drawLine(baseline, Offset(0f, size.height - hairline / 2f), Offset(size.width, size.height - hairline / 2f), hairline)
                }
            },
    )
}
