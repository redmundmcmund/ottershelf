package io.github.ottershelf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme

/**
 * A dashboard card as the web and the Nexus app draw it: the card colour faintly over the page
 * (`bg-card/30`), a 1dp accent edge (`border-primary/40`) and the 2xl radius (`rounded-2xl`).
 *
 * For a plain card (the Nexus series cards, settings groups) pass
 * `containerColor = colors.card, borderColor = colors.border, shape = radii.lgShape`.
 */
@Composable
fun DashCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = OttershelfTheme.colors.dashCard,
    borderColor: Color = OttershelfTheme.colors.dashCardBorder,
    shape: Shape = RoundedCornerShape(OttershelfTheme.radii.xl2),
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .border(1.dp, borderColor, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * A shelf's header (the Nexus Recently Added card, the web's DashboardScroller): a small framed
 * icon, the title in bold and an optional count pill, with [trailing] content at the end.
 * [icon] is a Lucide name (or pass [iconVector]).
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    iconVector: ImageVector? = null,
    count: Int? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = OttershelfTheme.colors
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null || iconVector != null) {
            val boxShape = RoundedCornerShape(OttershelfTheme.radii.md)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(colors.iconBox, boxShape)
                    .border(1.dp, colors.border, boxShape),
                contentAlignment = Alignment.Center,
            ) {
                if (iconVector != null) {
                    Icon(iconVector, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.foreground)
                } else {
                    LucideIcon(icon, contentDescription = null, tint = colors.foreground, size = 14.dp)
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        // The title and pill take all the room [trailing] leaves (a title beside a weighted spacer
        // was cut off at half the width).
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (count != null) {
                Spacer(Modifier.width(8.dp))
                CountPill(count.toString())
            }
        }
        trailing()
    }
}

/**
 * A dashboard card's title (the Nexus `DashTitle`, the web's widget header): a 16dp accent icon
 * and the title. [icon] is a Lucide name.
 */
@Composable
fun CardTitle(title: String, modifier: Modifier = Modifier, icon: String? = null) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) LucideIcon(icon, contentDescription = null, tint = OttershelfTheme.colors.primary, size = 16.dp)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = OttershelfTheme.colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A row inside a card (a Currently Reading book): `bg-muted/20`, `bg-muted/40` while pressed, the
 * lg radius and 6dp padding (the Nexus `dash_row_bg`).
 */
@Composable
fun CardRow(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(6.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val colors = OttershelfTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(OttershelfTheme.radii.lg)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (pressed) colors.cardRowPressed else colors.cardRow)
            .then(if (onClick != null) Modifier.clickable(interaction, indication = null, onClick = onClick) else Modifier)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
