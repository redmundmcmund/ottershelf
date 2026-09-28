package io.github.ottershelf.feature.achievements

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ottershelf.R
import io.github.ottershelf.feature.achievements.model.Achievement
import io.github.ottershelf.feature.calendar.GoalRing
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToLong

/**
 * A badge's medallion: the icon in a disc inside a ring. Earned: the ring full and the icon in the
 * rarity colour. Not yet: the icon dim, and the ring shows progress toward the threshold in the
 * accent (an empty track when nothing is tracked). A secret shows a lock.
 */
@Composable
fun BadgeMedallion(
    icon: String,
    rarity: String,
    earned: Boolean,
    fraction: Float?,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    val colors = OttershelfTheme.colors
    val tint = rarityColor(rarity)
    val stroke = if (size >= 80.dp) 5.dp else 3.5.dp
    GoalRing(
        progress = if (earned) 1f else fraction ?: 0f,
        size = size,
        stroke = stroke,
        color = if (earned) tint else colors.primary,
        modifier = modifier,
    ) {
        Box(
            Modifier
                .size(size - stroke * 2 - 6.dp)
                .background(if (earned) tint.copy(alpha = 0.16f) else colors.muted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            LucideIcon(
                icon,
                contentDescription = null,
                tint = if (earned) tint else colors.mutedForeground,
                size = size * 0.42f,
                fallback = "Trophy",
                modifier = if (earned) Modifier else Modifier.alpha(0.8f),
            )
        }
    }
}

/** One dot per tier: filled in the tier's rarity colour once earned, an outline before. */
@Composable
fun TierPips(tiers: List<Achievement>, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        tiers.forEach { tier ->
            val c = rarityColor(tier.rarity)
            Box(
                Modifier
                    .size(7.dp)
                    .then(if (tier.earned) Modifier.background(c, CircleShape) else Modifier.border(1.dp, colors.mutedForeground.copy(alpha = 0.6f), CircleShape)),
            )
        }
    }
}

/** The rarity written out beside a dot of its colour (never colour alone). */
@Composable
fun RarityPill(rarity: String, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    Row(
        modifier.background(colors.muted, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(7.dp).background(rarityColor(rarity), CircleShape))
        Text(rarityLabel(rarity), style = MaterialTheme.typography.labelSmall, color = colors.foreground, maxLines = 1)
    }
}

@Composable
@ReadOnlyComposable
fun rarityLabel(rarity: String): String = stringResource(
    when (rarity) {
        "rare" -> R.string.achievements_rarity_rare
        "epic" -> R.string.achievements_rarity_epic
        "legendary" -> R.string.achievements_rarity_legendary
        else -> R.string.achievements_rarity_common
    },
)

/** "12 Mar 2026" in the phone's zone, or null. */
fun awardedText(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String? =
    AchievementLogic.awardedDate(iso, zone)?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** 7.0 -> "7", 12400.0 -> "12,400", 7.5 -> "7.5" (the phone's locale). */
fun number(value: Double): String =
    if (value % 1.0 == 0.0) java.text.NumberFormat.getIntegerInstance().format(value.roundToLong()) else "%.1f".format(value)

/**
 * A badge card in the grid: the medallion (with tier pips), the name, rarity, the description,
 * and at the bottom when it was earned, or the progress toward it, and the book it came with.
 * Earned cards take a 1dp edge in the rarity colour; locked ones are faded with a plain edge.
 */
@Composable
fun BadgeCard(badge: Badge, onClick: () -> Unit, onOpenBook: (Long) -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val radii = OttershelfTheme.radii
    val tint = rarityColor(badge.rarity)
    DashCard(
        modifier = modifier,
        onClick = onClick,
        containerColor = if (badge.earned) colors.card else colors.card.copy(alpha = 0.55f),
        borderColor = if (badge.earned) tint.copy(alpha = 0.6f) else colors.border,
        shape = RoundedCornerShape(radii.xl),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                BadgeMedallion(badge.icon, badge.rarity, badge.earned, badge.fraction)
                if (badge.tiered) {
                    Spacer(Modifier.height(6.dp))
                    TierPips(badge.tiers)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (badge.secret) stringResource(R.string.achievements_secret) else badge.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (badge.earned) colors.foreground else colors.foreground.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!badge.secret) {
                    Spacer(Modifier.height(5.dp))
                    RarityPill(badge.rarity)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (badge.secret) stringResource(R.string.achievements_secret_hint) else badge.description.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedForeground,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (badge.complete) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(20.dp).background(colors.success.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { LucideIcon("Check", contentDescription = stringResource(R.string.achievements_earned), tint = colors.success, size = 12.dp) }
            }
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(8.dp))
        BadgeFooter(badge, onOpenBook)
    }
}

@Composable
private fun BadgeFooter(badge: Badge, onOpenBook: (Long) -> Unit) {
    val colors = OttershelfTheme.colors
    val progress = badge.fraction
    val earnedOn = awardedText(badge.awardedAt)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (progress != null && badge.threshold != null) {
            Text(
                stringResource(R.string.achievements_progress, number(badge.progress ?: 0.0), number(badge.threshold)),
                style = MaterialTheme.typography.labelMedium,
                color = colors.foreground,
                maxLines = 1,
            )
        }
        if (earnedOn != null) {
            Text(stringResource(R.string.achievements_earned_on, earnedOn), style = MaterialTheme.typography.labelSmall, color = colors.mutedForeground, maxLines = 1)
        }
        val bookId = badge.contextBookId
        val title = badge.contextBookTitle
        if (title != null) {
            Row(
                Modifier
                    .then(if (bookId != null) Modifier.clickable { onOpenBook(bookId) } else Modifier)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LucideIcon("BookOpen", contentDescription = null, tint = if (bookId != null) colors.primary else colors.mutedForeground, size = 12.dp)
                Spacer(Modifier.width(4.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bookId != null) colors.primary else colors.mutedForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Two badge cards side by side, the same height. */
@Composable
fun BadgeRow(left: Badge, right: Badge?, onBadge: (Badge) -> Unit, onOpenBook: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BadgeCard(left, onClick = { onBadge(left) }, onOpenBook = onOpenBook, modifier = Modifier.weight(1f).fillMaxHeight())
        if (right != null) {
            BadgeCard(right, onClick = { onBadge(right) }, onOpenBook = onOpenBook, modifier = Modifier.weight(1f).fillMaxHeight())
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

/** The web's segmented control (as on Statistics): a muted track, the picked option on the card. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val radii = OttershelfTheme.radii
    Row(modifier.background(colors.muted, RoundedCornerShape(radii.lg)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEach { (value, label) ->
            val picked = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(if (picked) colors.card else colors.muted, RoundedCornerShape(radii.md))
                    .then(if (picked) Modifier.border(1.dp, colors.border, RoundedCornerShape(radii.md)) else Modifier)
                    .clickable { onSelect(value) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (picked) colors.foreground else colors.mutedForeground, maxLines = 1)
            }
        }
    }
}

/** An icon on a tinted tile (the Nexus stat tiles' icon box). */
@Composable
fun IconTile(icon: String, tint: Color, size: Dp = 44.dp, iconSize: Dp = 22.dp) {
    Box(Modifier.size(size).background(tint.copy(alpha = 0.15f), RoundedCornerShape(OttershelfTheme.radii.lg)), contentAlignment = Alignment.Center) {
        LucideIcon(icon, contentDescription = null, tint = tint, size = iconSize)
    }
}
