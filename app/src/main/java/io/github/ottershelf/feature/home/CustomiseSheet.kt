package io.github.ottershelf.feature.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.core.model.SmartScope
import io.github.ottershelf.feature.home.model.CustomiseDraft
import io.github.ottershelf.feature.home.model.CustomiseTab
import io.github.ottershelf.feature.home.model.MAX_SHELVES
import io.github.ottershelf.feature.home.model.SHELF_LIMIT_OPTIONS
import io.github.ottershelf.feature.home.model.SHELF_ROW_OPTIONS
import io.github.ottershelf.feature.home.model.ShelfConfig
import io.github.ottershelf.feature.home.model.ShelfType
import io.github.ottershelf.feature.home.model.WidgetConfig
import io.github.ottershelf.feature.home.model.moved
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import io.github.ottershelf.ui.components.belowStatusBar

typealias DraftEdit = ((CustomiseDraft) -> CustomiseDraft) -> Unit

/**
 * The toolbar's Customise, as a bottom sheet over the Dashboard (the web's DashboardSettingsSheet).
 * It stays up while a Save is under way (the close would be refused, leaving a hidden sheet over
 * the Dashboard), so a failed Save shows its error.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomiseSheet(state: CustomiseState, onEdit: DraftEdit, onSave: () -> Unit, onClose: () -> Unit, onRetryLibraries: () -> Unit = {}) {
    val saving by rememberUpdatedState(state.saving)
    ModalBottomSheet(
        modifier = Modifier.belowStatusBar(),
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !saving }),
        sheetGesturesEnabled = !state.saving,
        containerColor = OttershelfTheme.colors.card,
    ) {
        CustomiseContent(state, onEdit, onSave, onClose, Modifier.fillMaxWidth().fillMaxHeight(), onRetryLibraries)
    }
}

/**
 * The sheet's content (stateless, screenshot-tested): the title, Shelves and Widgets tabs, the
 * library scope (both tabs, as on the web), the tab's list, and Reset / Cancel / Save.
 */
@Composable
fun CustomiseContent(
    state: CustomiseState,
    onEdit: DraftEdit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryLibraries: () -> Unit = {},
) {
    val colors = OttershelfTheme.colors
    val draft = state.draft
    val valid = draft.validLibraries(state.libraries)
    Column(modifier) {
        Text(
            stringResource(R.string.home_customise_title),
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
            color = colors.foreground,
        )
        HorizontalDivider(color = colors.border)
        Tabs(draft.tab, onSelect = { tab -> onEdit { it.copy(tab = tab) } }, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        HorizontalDivider(color = colors.border)
        CustomiseList(state, valid, onEdit, onRetryLibraries, Modifier.weight(1f))
        HorizontalDivider(color = colors.border)
        if (state.error != null) {
            Text(
                state.error.ifBlank { stringResource(R.string.home_save_failed) },
                Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.destructive,
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(OttershelfTheme.radii.md))
                    .clickable(role = Role.Button) { onEdit { it.reset() } }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LucideIcon("RotateCcw", contentDescription = null, tint = colors.mutedForeground, size = 13.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.home_reset), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = colors.mutedForeground)
            }
            Spacer(Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.home_cancel), onClick = onCancel, enabled = !state.saving)
            Spacer(Modifier.width(8.dp))
            AccentButton(stringResource(R.string.home_save), onClick = onSave, enabled = !state.saving && valid)
        }
    }
}

/**
 * The library scope, then the tab's list: shelves or widgets, each dragged by its handle into
 * place (the list scrolls when one is held near an edge); Move up / down for accessibility services.
 */
@Composable
private fun CustomiseList(state: CustomiseState, valid: Boolean, onEdit: DraftEdit, onRetryLibraries: () -> Unit, modifier: Modifier) {
    val draft = state.draft
    val tab = draft.tab
    val keys = if (tab == CustomiseTab.SHELVES) draft.shelfKeys else draft.widgetKeys
    val listState = rememberLazyListState()
    // The entries as dragged so far, until the draft catches up with the drop.
    var dragged by remember(tab) { mutableStateOf<List<String>?>(null) }
    val shown = dragged ?: keys
    val reorder = rememberReorderState(
        listState,
        canMove = { it is String && it in keys },
        onMove = { from, to ->
            val list = dragged ?: keys
            val i = list.indexOf(from)
            val j = list.indexOf(to)
            if (i >= 0 && j >= 0) dragged = list.moved(i, j)
        },
        onDrop = {
            dragged?.let { order ->
                onEdit { if (tab == CustomiseTab.SHELVES) it.arrangeShelves(order) else it.arrangeWidgets(order) }
            }
        },
    )
    LaunchedEffect(keys) { if (!reorder.isDragging) dragged = null }
    val move: (Int, Int) -> Unit = { from, to ->
        onEdit { if (tab == CustomiseTab.SHELVES) it.moveShelf(from, to) else it.moveWidget(from, to) }
    }
    val colors = OttershelfTheme.colors
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        item(key = "scope", contentType = "scope") {
            Column(Modifier.padding(bottom = 16.dp)) { LibraryScope(draft, state, valid, onEdit, onRetryLibraries) }
        }
        item(key = "hint:$tab", contentType = "hint") {
            Column(Modifier.padding(bottom = 14.dp)) {
                Text(
                    stringResource(if (tab == CustomiseTab.SHELVES) R.string.home_reorder_shelves else R.string.home_reorder_widgets),
                    style = labelStyle,
                    color = colors.mutedForeground,
                )
                if (tab == CustomiseTab.SHELVES) {
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.home_rows_hint), style = labelStyle, color = colors.mutedForeground)
                }
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.home_arrange_tip), style = labelStyle, color = colors.mutedForeground)
            }
        }
        items(shown, key = { it }, contentType = { tab }) { key ->
            val index = keys.indexOf(key)
            val entry = reorderable(reorder, key).padding(bottom = 8.dp)
            val handle = Modifier.dragHandle(reorder, key)
            val moves = Moves(index, keys.size, move)
            val dragging = reorder.draggingKey == key
            when (tab) {
                CustomiseTab.SHELVES -> draft.shelves.getOrNull(index)?.let { shelf ->
                    ShelfEntry(index, shelf, draft, state.smartScopes, onEdit, handle, dragging, moves, entry)
                }
                CustomiseTab.WIDGETS -> draft.widgets.getOrNull(index)?.let { widget ->
                    WidgetEntry(index, widget, onEdit, handle, dragging, moves, entry)
                }
            }
        }
        if (tab == CustomiseTab.SHELVES) {
            item(key = "add", contentType = "add") { AddShelf(draft, onEdit, Modifier.padding(top = 4.dp)) }
        }
    }
}

/** An entry's place and the accessibility actions that move it. */
private class Moves(val index: Int, val count: Int, val move: (Int, Int) -> Unit)

// --- pieces ----------------------------------------------------------------------------------

private val labelStyle: TextStyle @Composable get() = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)

@Composable
private fun Tabs(selected: CustomiseTab, onSelect: (CustomiseTab) -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val outer = RoundedCornerShape(OttershelfTheme.radii.lg)
    val inner = RoundedCornerShape(OttershelfTheme.radii.md)
    Row(modifier.fillMaxWidth().clip(outer).background(colors.muted).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(CustomiseTab.SHELVES to R.string.home_tab_shelves, CustomiseTab.WIDGETS to R.string.home_tab_widgets).forEach { (tab, label) ->
            val on = tab == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(inner)
                    .background(if (on) colors.background else colors.muted)
                    .clickable(role = Role.Tab) { onSelect(tab) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) colors.foreground else colors.mutedForeground,
                )
            }
        }
    }
}

/**
 * A bordered row card, as the web's list entries. [dragging]: picked up by its handle, it lifts
 * (a shadow and an accent edge) above the others. [moves]: Move up / down for accessibility services.
 */
@Composable
private fun EntryCard(modifier: Modifier = Modifier, dragging: Boolean = false, moves: Moves? = null, content: @Composable () -> Unit) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.lg)
    val elevation by animateDpAsState(if (dragging) 10.dp else 0.dp, label = "entry-lift")
    val up = stringResource(R.string.home_move_up)
    val down = stringResource(R.string.home_move_down)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(elevation, shape, clip = false)
            .clip(shape)
            .background(colors.card)
            .border(1.dp, if (dragging) colors.primary else colors.border, shape)
            .then(
                if (moves == null) {
                    Modifier
                } else {
                    Modifier.semantics {
                        customActions = listOfNotNull(
                            CustomAccessibilityAction(up) { moves.move(moves.index, moves.index - 1); true }.takeIf { moves.index > 0 },
                            CustomAccessibilityAction(down) { moves.move(moves.index, moves.index + 1); true }.takeIf { moves.index < moves.count - 1 },
                        )
                    }
                },
            ),
    ) { content() }
}

/** The grip an entry is dragged by (in place of the web's up and down arrows). */
@Composable
private fun Handle(modifier: Modifier, dragging: Boolean) {
    val colors = OttershelfTheme.colors
    Box(modifier.size(32.dp, 40.dp), contentAlignment = Alignment.Center) {
        LucideIcon(
            "GripVertical",
            contentDescription = stringResource(R.string.home_drag_handle),
            tint = if (dragging) colors.primary else colors.mutedForeground,
            size = 18.dp,
        )
    }
}

/**
 * [CustomiseState.libraries] (every one, podcasts too) decides what a saved scope holds; the
 * checkboxes are [CustomiseState.bookLibraries]. While the list is unknown (loading, or it failed:
 * a tap asks again) the scope stays as it is.
 */
@Composable
private fun LibraryScope(draft: CustomiseDraft, state: CustomiseState, valid: Boolean, onEdit: DraftEdit, onRetry: () -> Unit) {
    val colors = OttershelfTheme.colors
    val all = state.libraries
    val libraries = state.bookLibraries
    val selected = draft.selectedLibraries(all)
    val summary = when {
        selected == null -> stringResource(R.string.home_scope_all)
        selected.isEmpty() -> stringResource(R.string.home_scope_none)
        selected.size > 3 || all == null -> pluralStringResource(R.plurals.home_scope_count, selected.size, selected.size)
        else -> all.filter { it.id in selected }.joinToString(", ") { it.name }
    }
    EntryCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onEdit { it.copy(libraryScopeOpen = !it.libraryScopeOpen) } }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_scope_title), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = colors.foreground)
                Text(summary, style = labelStyle, color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            LucideIcon(
                "ChevronDown",
                contentDescription = null,
                tint = colors.mutedForeground,
                size = 16.dp,
                modifier = Modifier.rotate(if (draft.libraryScopeOpen) 180f else 0f),
            )
        }
        if (draft.libraryScopeOpen) {
            HorizontalDivider(color = colors.border)
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)) {
                Text(stringResource(R.string.home_scope_description), style = labelStyle, color = colors.mutedForeground)
                Spacer(Modifier.height(6.dp))
                if (libraries == null && state.librariesFailed) {
                    Text(
                        stringResource(R.string.home_scope_failed),
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(OttershelfTheme.radii.md))
                            .clickable(role = Role.Button, onClick = onRetry)
                            .padding(vertical = 8.dp),
                        style = labelStyle,
                        color = colors.primary,
                    )
                } else if (libraries == null) {
                    Text(stringResource(R.string.home_scope_loading), style = labelStyle, color = colors.mutedForeground)
                } else {
                    CheckRow(stringResource(R.string.home_scope_all), draft.libraryIds == null, bold = true) { all ->
                        onEdit { it.setAllLibraries(all, libraries) }
                    }
                    if (draft.libraryIds != null) {
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            Box(Modifier.padding(start = 20.dp).width(1.dp).fillMaxHeight().background(colors.border))
                            Column(Modifier.padding(start = 8.dp)) {
                                libraries.forEach { library ->
                                    CheckRow(library.name, library.id in draft.libraryIds) { onEdit { it.toggleLibrary(library.id, libraries) } }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!valid) {
            HorizontalDivider(color = colors.border)
            Text(
                stringResource(R.string.home_scope_required),
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = labelStyle,
                color = colors.destructive,
            )
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, bold: Boolean = false, onChange: (Boolean) -> Unit) {
    val colors = OttershelfTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(OttershelfTheme.radii.md))
            .clickable(role = Role.Checkbox) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.padding(horizontal = 8.dp),
            colors = CheckboxDefaults.colors(checkedColor = colors.primary, checkmarkColor = colors.onPrimary, uncheckedColor = colors.input),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal), color = colors.foreground)
    }
}

@Composable
private fun Toggle(checked: Boolean, onChange: () -> Unit) {
    Switch(checked = checked, onCheckedChange = { onChange() }, modifier = Modifier.scale(0.8f))
}

// --- Shelves ---------------------------------------------------------------------------------

/** The web's "Add shelf (n/8)": a Recently Added shelf at the end. */
@Composable
private fun AddShelf(draft: CustomiseDraft, onEdit: DraftEdit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.lg)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .border(BorderStroke(1.dp, colors.border), shape)
            .clickable(enabled = draft.canAddShelf, role = Role.Button) { onEdit { it.addShelf() } }
            .alpha(if (draft.canAddShelf) 1f else 0.4f)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon("Plus", contentDescription = null, tint = colors.mutedForeground, size = 15.dp)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.home_add_shelf, draft.shelves.size, MAX_SHELVES), style = MaterialTheme.typography.bodyMedium, color = colors.mutedForeground)
    }
}

@Composable
private fun ShelfEntry(
    index: Int,
    shelf: ShelfConfig,
    draft: CustomiseDraft,
    scopes: List<SmartScope>?,
    onEdit: DraftEdit,
    handle: Modifier,
    dragging: Boolean,
    moves: Moves,
    modifier: Modifier,
) {
    val colors = OttershelfTheme.colors
    EntryCard(modifier, dragging, moves) {
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Handle(handle, dragging)
            Toggle(shelf.enabled) { onEdit { it.toggleShelf(index) } }
            Spacer(Modifier.width(4.dp))
            Picker(
                label = stringResource(shelfName(shelf.shelfType)),
                options = ShelfType.entries,
                optionLabel = { stringResource(shelfName(it)) },
                onPick = { type -> onEdit { it.setShelfType(index, type, scopes.orEmpty()) } },
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(OttershelfTheme.radii.md))
                    .clickable(enabled = draft.canRemoveShelf, role = Role.Button, onClickLabel = stringResource(R.string.home_remove_shelf)) {
                        onEdit { it.removeShelf(index) }
                    }
                    .alpha(if (draft.canRemoveShelf) 1f else 0.3f),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon("Trash", contentDescription = stringResource(R.string.home_remove_shelf), tint = colors.mutedForeground, size = 15.dp)
            }
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OptionRow(stringResource(R.string.home_rows), SHELF_ROW_OPTIONS, shelf.rows) { rows -> onEdit { it.setShelfRows(index, rows) } }
            OptionRow(stringResource(R.string.home_books_per_row), SHELF_LIMIT_OPTIONS, shelf.limit) { limit -> onEdit { it.setShelfLimit(index, limit) } }
        }
        if (shelf.shelfType == ShelfType.SMART_SCOPE) {
            HorizontalDivider(color = colors.border.copy(alpha = 0.5f))
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.home_smart_scope), style = labelStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium), color = colors.mutedForeground)
                Spacer(Modifier.height(4.dp))
                if (scopes.isNullOrEmpty()) {
                    Text(stringResource(R.string.home_no_smart_scopes), style = labelStyle, color = colors.mutedForeground)
                } else {
                    Picker(
                        label = scopes.firstOrNull { it.id == shelf.smartScopeId }?.name ?: shelf.label,
                        options = scopes,
                        optionLabel = { it.name },
                        onPick = { scope -> onEdit { it.setShelfScope(index, scope) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, options: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    val colors = OttershelfTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = labelStyle, color = colors.mutedForeground)
        Segmented(options, selected, onPick)
    }
}

/** The web's rows buttons: one box of options, the chosen one in the accent. */
@Composable
private fun Segmented(options: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.md)
    Row(Modifier.clip(shape).border(1.dp, colors.input, shape)) {
        options.forEachIndexed { i, option ->
            val on = option == selected
            if (i > 0) Box(Modifier.width(1.dp).height(30.dp).background(colors.input))
            Box(
                Modifier
                    .size(34.dp, 30.dp)
                    .background(if (on) colors.primary else colors.card)
                    .clickable(role = Role.RadioButton) { onPick(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) colors.onPrimary else colors.mutedForeground,
                )
            }
        }
    }
}

/** A bordered select (the web's `<select>`): the current choice and a menu of the others. */
@Composable
private fun <T> Picker(label: String, options: List<T>, optionLabel: @Composable (T) -> String, onPick: (T) -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.md)
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(shape)
                .background(colors.background)
                .border(1.dp, colors.input, shape)
                .clickable { open = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LucideIcon("ChevronDown", contentDescription = null, tint = colors.mutedForeground, size = 14.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.popover) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option), style = MaterialTheme.typography.bodyMedium, color = colors.foreground) },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

// --- Widgets ---------------------------------------------------------------------------------

@Composable
private fun WidgetEntry(index: Int, widget: WidgetConfig, onEdit: DraftEdit, handle: Modifier, dragging: Boolean, moves: Moves, modifier: Modifier) {
    val colors = OttershelfTheme.colors
    EntryCard(modifier, dragging, moves) {
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Handle(handle, dragging)
            Toggle(widget.enabled) { onEdit { it.toggleWidget(index) } }
            Spacer(Modifier.width(8.dp))
            LucideIcon(widget.type.icon, contentDescription = null, tint = if (widget.enabled) colors.primary else colors.mutedForeground, size = 16.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(widgetTitle(widget.type)),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = if (widget.enabled) colors.foreground else colors.mutedForeground,
            )
        }
    }
}
