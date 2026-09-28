package io.github.ottershelf.feature.calendar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import kotlin.math.roundToInt

/** "45 s", "25 min", "1 h 5 min", "3 h". */
@Composable
internal fun durationLabel(seconds: Long): String {
    if (seconds in 1..59) return stringResource(R.string.calendar_seconds, seconds.toInt())
    val minutes = (seconds / 60.0).roundToInt()
    return when {
        minutes < 60 -> stringResource(R.string.calendar_minutes, minutes)
        minutes % 60 == 0 -> stringResource(R.string.calendar_hours, minutes / 60)
        else -> stringResource(R.string.calendar_hours_minutes, minutes / 60, minutes % 60)
    }
}

@Composable
internal fun booksLabel(count: Int): String = pluralStringResource(R.plurals.calendar_books, count, count)

/** The big numbers of the tracker's cards (the Dashboard streak's style). */
@Composable
internal fun bigNumberStyle(size: Int = 30): TextStyle = MaterialTheme.typography.headlineLarge.copy(
    fontFamily = MaterialTheme.typography.titleMedium.fontFamily,
    fontSize = size.sp,
    lineHeight = (size + 2).sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.sp,
)

/**
 * A progress ring: a muted track and an accent arc from 12 o'clock ([progress] 0..1; over 1 the
 * ring is full), in [color], with [content] in the middle.
 */
@Composable
internal fun GoalRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
    stroke: Dp = 7.dp,
    color: Color = OttershelfTheme.colors.primary,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val track = OttershelfTheme.colors.muted
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = stroke.toPx()
            val inset = width / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - width, this.size.height - width)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width))
            val sweep = 360f * progress.coerceIn(0f, 1f)
            if (sweep > 0f) {
                drawArc(color, -90f, sweep, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

/** A small stat: a framed accent icon over a big value and a dim label. */
@Composable
internal fun StatTile(icon: String, value: String, label: String, modifier: Modifier = Modifier, tint: Color = OttershelfTheme.colors.primary) {
    val colors = OttershelfTheme.colors
    DashCard(modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Box(Modifier.size(30.dp).background(tint.copy(alpha = 0.15f), RoundedCornerShape(OttershelfTheme.radii.lg)), contentAlignment = Alignment.Center) {
            LucideIcon(icon, contentDescription = null, tint = tint, size = 16.dp)
        }
        Spacer(Modifier.height(10.dp))
        Text(value, style = bigNumberStyle(24), color = colors.foreground, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground, maxLines = 2)
    }
}

/** A chevron pointing left or right (the built-in ChevronRight, turned for left). */
@Composable
internal fun Chevron(left: Boolean, tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    LucideIcon("ChevronRight", contentDescription = null, tint = tint, size = size, modifier = if (left) modifier.rotate(180f) else modifier)
}

/** A pill of text on a tinted background (the web's status pills). */
@Composable
internal fun Pill(text: String, color: Color, modifier: Modifier = Modifier, icon: String? = null) {
    Row(
        modifier.background(color.copy(alpha = 0.15f), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) LucideIcon(icon, contentDescription = null, tint = color, size = 13.dp)
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/** A column of a label over a value, centred (used under rings). */
@Composable
internal fun RingCaption(value: String, caption: String) {
    val colors = OttershelfTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = bigNumberStyle(17), color = colors.foreground, maxLines = 1)
        Text(caption, style = MaterialTheme.typography.labelSmall, color = colors.mutedForeground, maxLines = 1)
    }
}
