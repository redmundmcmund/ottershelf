package io.github.ottershelf.feature.library

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import io.github.ottershelf.ui.components.belowStatusBar

// --- labels ----------------------------------------------------------------------------------

@StringRes
internal fun SortField.label(): Int = when (this) {
    SortField.TITLE -> R.string.library_sort_title
    SortField.AUTHOR -> R.string.library_sort_author
    SortField.SERIES -> R.string.library_sort_series
    SortField.SERIES_ORDER -> R.string.library_sort_series_order
    SortField.READ_STATUS -> R.string.library_sort_read_status
    SortField.ADDED -> R.string.library_sort_added
    SortField.LAST_READ -> R.string.library_sort_last_read
    SortField.PUBLISHED, SortField.PUBLISHED_YEAR -> R.string.library_sort_published
}

internal val SortField.icon: String
    get() = when (this) {
        SortField.TITLE -> "ALargeSmall"
        SortField.AUTHOR -> "Users"
        SortField.SERIES -> "BookCopy"
        SortField.SERIES_ORDER -> "ListOrdered"
        SortField.READ_STATUS -> "BookCheck"
        SortField.ADDED -> "CalendarDays"
        SortField.LAST_READ -> "Clock"
        SortField.PUBLISHED, SortField.PUBLISHED_YEAR -> "CalendarRange"
    }

/** What ascending ([descending] false) or descending means for this field, in words. */
@StringRes
internal fun SortField.directionLabel(descending: Boolean): Int = when (this) {
    SortField.TITLE, SortField.AUTHOR, SortField.SERIES ->
        if (descending) R.string.library_order_z_a else R.string.library_order_a_z
    SortField.SERIES_ORDER -> if (descending) R.string.library_order_last_first else R.string.library_order_first_last
    SortField.READ_STATUS -> if (descending) R.string.library_order_reading_last else R.string.library_order_reading_first
    SortField.LAST_READ -> if (descending) R.string.library_order_recent_first else R.string.library_order_recent_last
    SortField.ADDED, SortField.PUBLISHED, SortField.PUBLISHED_YEAR ->
        if (descending) R.string.library_order_newest else R.string.library_order_oldest
}

// --- the toolbar action ------------------------------------------------------------------------

/** The grid's toolbar action: opens the sort sheet; a dot while the list isn't in its default order. */
@Composable
fun SortButton(active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box {
            LucideIcon("ArrowUpDown", contentDescription = stringResource(R.string.library_sort_action), tint = LocalContentColor.current, size = 22.dp)
            if (active) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(8.dp)
                        .background(OttershelfTheme.colors.primary, CircleShape),
                )
            }
        }
    }
}

// --- the chip row under the toolbar ------------------------------------------------------------

/**
 * What's different from the list's default, as chips under the toolbar: the sort ("Author · Z to
 * A"), each filter, and Clear, which puts the default back in one tap. Tapping a chip opens the sheet.
 */
@Composable
fun SortChips(sort: ListSort, kind: ListKind, onOpen: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val default = kind.default
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sort.field != default.field || sort.descending != default.descending) {
            SortChip(
                label = stringResource(R.string.library_chip_sort, stringResource(sort.field.label()), stringResource(sort.field.directionLabel(sort.descending))),
                icon = "ArrowUpDown",
                active = true,
                onClick = onOpen,
            )
        }
        if (sort.readingOnly) SortChip(stringResource(R.string.library_filter_reading_only), "BookOpen", active = true, onClick = onOpen)
        if (sort.hideRead) SortChip(stringResource(R.string.library_filter_hide_read), "EyeOff", active = true, onClick = onOpen)
        if (sort.hideUnread) SortChip(stringResource(R.string.library_filter_hide_unread), "EyeOff", active = true, onClick = onOpen)
        SortChip(stringResource(R.string.library_chip_clear), "X", active = false, onClick = onClear)
    }
}

/** A 32dp chip: the accent tint and edge when [active], else the card with a border (the Notes filter chips). */
@Composable
private fun SortChip(label: String, icon: String, active: Boolean, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.lg)
    Row(
        Modifier
            .height(32.dp)
            .clip(shape)
            .background(if (active) colors.accentTint else colors.card)
            .border(1.dp, if (active) colors.primary.copy(alpha = 0.5f) else colors.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LucideIcon(icon, contentDescription = null, tint = if (active) colors.primary else colors.mutedForeground, size = 14.dp)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) colors.primary else colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp),
        )
    }
}

// --- the sheet -------------------------------------------------------------------------------

/** The sort and filter sheet. Every change applies at once (the grid reloads behind it). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortSheet(sort: ListSort, kind: ListKind, onChange: (ListSort) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        modifier = Modifier.belowStatusBar(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OttershelfTheme.colors.card,
    ) {
        SortSheetContent(sort, kind, onChange, onDone = onDismiss, modifier = Modifier.fillMaxWidth().navigationBarsPadding())
    }
}

/**
 * The sheet's content (stateless, screenshot-tested): Sort by (the fields [kind] offers), the
 * order in words that suit the field, then the filters; Reset and Done.
 */
@Composable
fun SortSheetContent(sort: ListSort, kind: ListKind, onChange: (ListSort) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    Column(modifier) {
        Text(
            stringResource(R.string.library_sort_sheet_title),
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
            color = colors.foreground,
        )
        HorizontalDivider(color = colors.border)
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            SectionLabel(R.string.library_sort_by)
            EntryCard {
                kind.fields.forEachIndexed { index, field ->
                    if (index > 0) HorizontalDivider(color = colors.border)
                    FieldRow(field, selected = field == sort.field, onClick = { onChange(sort.withField(field)) })
                }
            }
            Spacer(Modifier.height(16.dp))
            SectionLabel(R.string.library_order)
            OrderTabs(sort, onChange)
            Spacer(Modifier.height(16.dp))
            SectionLabel(R.string.library_show)
            EntryCard {
                ToggleRow(
                    title = R.string.library_filter_reading_only,
                    detail = R.string.library_filter_reading_only_detail,
                    checked = sort.readingOnly,
                    onChange = { onChange(sort.copy(readingOnly = !sort.readingOnly)) },
                )
                HorizontalDivider(color = colors.border)
                ToggleRow(
                    title = R.string.library_filter_hide_read,
                    detail = R.string.library_filter_hide_read_detail,
                    checked = sort.hideRead,
                    onChange = { onChange(sort.copy(hideRead = !sort.hideRead)) },
                )
                HorizontalDivider(color = colors.border)
                ToggleRow(
                    title = R.string.library_filter_hide_unread,
                    detail = R.string.library_filter_hide_unread_detail,
                    checked = sort.hideUnread,
                    onChange = { onChange(sort.copy(hideUnread = !sort.hideUnread)) },
                )
            }
        }
        HorizontalDivider(color = colors.border)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val canReset = sort != kind.default
            Row(
                Modifier
                    .clip(RoundedCornerShape(OttershelfTheme.radii.md))
                    .clickable(enabled = canReset, role = Role.Button) { onChange(kind.default) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (canReset) colors.mutedForeground else colors.mutedForeground.copy(alpha = 0.5f)
                LucideIcon("RotateCcw", contentDescription = null, tint = tint, size = 13.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.library_sort_reset), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = tint)
            }
            Spacer(Modifier.weight(1f))
            AccentButton(stringResource(R.string.library_sort_done), onClick = onDone)
        }
    }
}

@Composable
private fun SectionLabel(@StringRes text: Int) {
    Text(
        stringResource(text),
        Modifier.padding(bottom = 8.dp),
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
        color = OttershelfTheme.colors.mutedForeground,
    )
}

/** A bordered card of rows, as the web's list entries (the Customise sheet's). */
@Composable
private fun EntryCard(content: @Composable () -> Unit) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.lg)
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.card).border(1.dp, colors.border, shape)) { content() }
}

@Composable
private fun FieldRow(field: SortField, selected: Boolean, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .background(if (selected) colors.accentTint else colors.card)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon(field.icon, contentDescription = null, tint = if (selected) colors.primary else colors.mutedForeground, size = 18.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(field.label()),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            color = if (selected) colors.primary else colors.foreground,
        )
        if (selected) LucideIcon("Check", contentDescription = null, tint = colors.primary, size = 18.dp)
    }
}

/** Ascending / descending as two tabs (the Customise sheet's tabs), in words for the field. */
@Composable
private fun OrderTabs(sort: ListSort, onChange: (ListSort) -> Unit) {
    val colors = OttershelfTheme.colors
    val outer = RoundedCornerShape(OttershelfTheme.radii.lg)
    val inner = RoundedCornerShape(OttershelfTheme.radii.md)
    Row(Modifier.fillMaxWidth().clip(outer).background(colors.muted).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(false, true).forEach { descending ->
            val on = sort.descending == descending
            Box(
                Modifier
                    .weight(1f)
                    .clip(inner)
                    .background(if (on) colors.background else colors.muted)
                    .clickable(role = Role.Tab) { onChange(sort.copy(descending = descending)) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(sort.field.directionLabel(descending)),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) colors.foreground else colors.mutedForeground,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(@StringRes title: Int, @StringRes detail: Int, checked: Boolean, onChange: () -> Unit) {
    val colors = OttershelfTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch, onClick = onChange)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = colors.foreground)
            Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
        }
        Switch(checked = checked, onCheckedChange = { onChange() }, modifier = Modifier.scale(0.8f))
    }
}
