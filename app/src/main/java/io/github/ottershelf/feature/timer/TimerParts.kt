package io.github.ottershelf.feature.timer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.core.tracking.ActiveTimer
import io.github.ottershelf.core.tracking.TimerNotifications
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import kotlin.math.roundToInt

/** "12:34" / "1:02:03": elapsed counting up; remaining (rounded up) counting down, "+2:13" past it. */
internal fun clockText(timer: ActiveTimer, now: Long): String {
    val remaining = timer.remainingMs(now)
    return when {
        remaining == null -> TimerNotifications.clock(timer.activeMs(now))
        remaining >= 0 -> TimerNotifications.clock(remaining + 999)
        else -> "+" + TimerNotifications.clock(-remaining)
    }
}

/** A countdown that has passed its target. */
internal fun ActiveTimer.overtime(now: Long): Boolean = (remainingMs(now) ?: 1) < 0

/** "45 s", "25 min", "1 h 5 min". */
@Composable
internal fun durationText(seconds: Long): String {
    if (seconds < 60) return stringResource(R.string.timer_seconds, seconds.toInt())
    val minutes = (seconds / 60.0).roundToInt()
    return if (minutes < 60) stringResource(R.string.timer_minutes, minutes)
    else stringResource(R.string.timer_hours_minutes, minutes / 60, minutes % 60)
}

/**
 * Two or more choices in a muted track (the web's tabs): the chosen one on the card colour with
 * a border, the others dim.
 */
@Composable
internal fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val radius = OttershelfTheme.radii.md
    val outer = RoundedCornerShape(radius)
    val inner = RoundedCornerShape((radius - 3.dp).coerceAtLeast(0.dp))
    Row(
        modifier
            .clip(outer)
            .background(colors.muted.copy(alpha = 0.55f))
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(inner)
                    .then(if (on) Modifier.background(colors.card).border(1.dp, colors.border, inner) else Modifier)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) colors.foreground else colors.mutedForeground,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A round button with a Lucide icon: accent fill ([filled]) or the card colour with a border. */
@Composable
internal fun RoundIconButton(
    icon: String,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp,
    filled: Boolean = false,
    enabled: Boolean = true,
    // Nudges an icon that looks off centre (a play triangle) to the right.
    iconOffset: Dp = 0.dp,
) {
    val colors = OttershelfTheme.colors
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) colors.primary else colors.card)
            .then(if (filled) Modifier else Modifier.border(1.dp, colors.border, CircleShape))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(
            icon,
            contentDescription = contentDescription,
            tint = when {
                !enabled -> colors.mutedForeground
                filled -> colors.onPrimary
                else -> colors.foreground
            },
            size = iconSize,
            modifier = Modifier.padding(start = iconOffset),
        )
    }
}

/** A small pill choice (quick countdown lengths): accent tint when chosen. */
@Composable
internal fun ChoicePill(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) colors.accentTint else colors.card.copy(alpha = 0f))
            .border(1.dp, if (selected) colors.accentLine else colors.border, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.primary else colors.mutedForeground,
        )
    }
}

/** "p. 130 / 464", "p. 130", "43%", or null when nothing is known. */
@Composable
internal fun pageLabel(page: Int?, total: Int?, percent: Double?): String? = when {
    page != null && total != null -> stringResource(R.string.timer_page_of, page, total)
    page != null -> stringResource(R.string.timer_page, page)
    percent != null -> stringResource(R.string.timer_percent, percent.roundToInt())
    else -> null
}

/**
 * The strip under the timer (Bookmory's): cover, title, author and where the book stands.
 * [book] null while it loads (or offline): [fallbackTitle] (the timer's) and no page.
 */
@Composable
internal fun BookStrip(book: TimerBook?, fallbackTitle: String?, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val title = book?.title ?: fallbackTitle?.ifBlank { null } ?: stringResource(R.string.components_untitled)
    DashCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BookCover(
                model = book?.cover,
                title = title,
                authors = book?.authors,
                seed = title,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.size(44.dp, 66.dp),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                book?.authors?.takeIf { it.isNotEmpty() }?.let { authors ->
                    Text(
                        authors,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.mutedForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val page = book?.let { pageLabel(it.page, it.pageTotal, it.percent) }
                if (page != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LucideIcon("BookOpen", contentDescription = null, tint = colors.primary, size = 14.dp)
                        Text(
                            page,
                            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                            color = colors.foreground,
                        )
                    }
                }
            }
        }
    }
}

/** The big clock's style: large, tabular digits, so the numbers don't jiggle as they change. */
@Composable
internal fun clockStyle(size: Int = 76) = MaterialTheme.typography.titleLarge.copy(
    fontSize = size.sp,
    lineHeight = (size + 6).sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = (-1).sp,
    fontFeatureSettings = "tnum",
)

/** A stat on the result screen: accent icon, the value large, a dim label. */
@Composable
internal fun StatTile(icon: String, value: String, label: String, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    DashCard(modifier, contentPadding = PaddingValues(14.dp)) {
        LucideIcon(icon, contentDescription = null, tint = colors.primary, size = 18.dp)
        Spacer(Modifier.height(10.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A fixed-width gap, for rows. */
@Composable
internal fun Gap(width: Dp) = Spacer(Modifier.width(width))
