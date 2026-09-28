package io.github.ottershelf.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfFonts
import io.github.ottershelf.ui.theme.OttershelfTheme
import io.github.ottershelf.ui.theme.atAlpha

// The web's settings building blocks (main.css `.settings-*`), drawn with the Nexus card style so
// Settings and Appearance look like the rest of the app.

/** `.settings-group-label`: 12sp semibold capitals, spaced out, dim, 8dp above its card. */
@Composable
internal fun SettingsGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 2.dp, bottom = 8.dp),
        style = TextStyle(
            fontFamily = OttershelfFonts.Sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 0.05.em,
            color = OttershelfTheme.colors.mutedForeground,
        ),
    )
}

/** `.settings-card`: the card colour, a 1dp border, the lg radius; rows are split by [SettingsDivider]. */
@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = OttershelfTheme.colors
    val shape = OttershelfTheme.radii.lgShape
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.border, shape),
        content = content,
    )
}

@Composable
internal fun SettingsDivider() = HorizontalDivider(thickness = 1.dp, color = OttershelfTheme.colors.border)

/** `.settings-label`: 14sp medium. */
@Composable
internal fun SettingsLabel(text: String, modifier: Modifier = Modifier, color: Color = OttershelfTheme.colors.foreground) {
    Text(text, modifier = modifier, style = TextStyle(fontFamily = OttershelfFonts.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp), color = color)
}

/** `.settings-hint`: 12sp dim, 2dp under its label. */
@Composable
internal fun SettingsHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 2.dp),
        style = TextStyle(fontFamily = OttershelfFonts.Sans, fontSize = 12.sp, lineHeight = 17.sp),
        color = OttershelfTheme.colors.mutedForeground,
    )
}

/** `.settings-value`: 14sp medium, tabular figures. */
@Composable
internal fun SettingsValue(text: String) {
    Text(
        text,
        style = TextStyle(
            fontFamily = OttershelfFonts.Sans,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            fontFeatureSettings = "tnum",
        ),
        color = OttershelfTheme.colors.foreground,
    )
}

/** A settings row's padding (`px-4 py-3.5`). */
internal val SettingsRowPadding = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)

/**
 * A tappable row in a card: a 32dp icon box ([icon] is a Lucide name), a label and a dim summary,
 * and a chevron when it opens another screen. [danger] draws it in the destructive colour.
 */
@Composable
internal fun SettingsNavRow(
    icon: String,
    title: String,
    summary: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    chevron: Boolean = true,
    enabled: Boolean = true,
) {
    val colors = OttershelfTheme.colors
    val accent = if (danger) colors.destructive else colors.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .then(SettingsRowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(32.dp).background(accent.atAlpha(0.15f), OttershelfTheme.radii.lgShape),
            contentAlignment = Alignment.Center,
        ) {
            LucideIcon(icon, contentDescription = null, tint = accent, size = 16.dp)
        }
        Column(Modifier.weight(1f)) {
            SettingsLabel(title, color = if (danger) colors.destructive else colors.foreground)
            if (!summary.isNullOrEmpty()) {
                Text(
                    summary,
                    modifier = Modifier.padding(top = 2.dp),
                    style = TextStyle(fontFamily = OttershelfFonts.Sans, fontSize = 12.sp, lineHeight = 17.sp),
                    color = colors.mutedForeground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (chevron) {
            LucideIcon("ChevronRight", contentDescription = null, tint = colors.mutedForeground, size = 18.dp)
        }
    }
}

/** A [SettingsNavRow] with a switch in place of the chevron; the whole row toggles it. */
@Composable
internal fun SettingsSwitchRow(
    icon: String,
    title: String,
    summary: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OttershelfTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .then(SettingsRowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(32.dp).background(colors.primary.atAlpha(0.15f), OttershelfTheme.radii.lgShape),
            contentAlignment = Alignment.Center,
        ) {
            LucideIcon(icon, contentDescription = null, tint = colors.primary, size = 16.dp)
        }
        Column(Modifier.weight(1f)) {
            SettingsLabel(title)
            if (!summary.isNullOrEmpty()) {
                Text(
                    summary,
                    modifier = Modifier.padding(top = 2.dp),
                    style = TextStyle(fontFamily = OttershelfFonts.Sans, fontSize = 12.sp, lineHeight = 17.sp),
                    color = colors.mutedForeground,
                )
            }
        }
        // The row does the toggling (and says it's a switch), so the switch itself only shows it.
        Switch(checked = checked, onCheckedChange = null)
    }
}
