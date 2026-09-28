package io.github.ottershelf.feature.reader.annotations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import kotlin.math.roundToInt

/** What the selection popup can do. */
class SelectionPopupActions(
    val onColor: (String) -> Unit = {},
    val onStyle: (String) -> Unit = {},
    val onNote: () -> Unit = {},
    val onCopy: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onDismiss: () -> Unit = {},
    /** The selection in the Look up sheet (feature.reader.lookup); no button while null. */
    val onLookUp: (() -> Unit)? = null,
)

/**
 * The popup over a selection or a tapped highlight, placed above it (below when there's no room),
 * inside the page's area ([BoxScope] of the page, coordinates in dp as reader.js reports them). A
 * tapped highlight has no selection behind it, so a tap anywhere else dismisses the popup; over a
 * selection the page stays touchable (its handles are the WebView's).
 */
@Composable
fun BoxScope.SelectionPopupHost(state: SelectionPopupState, actions: SelectionPopupActions) {
    if (state.fromTap) {
        Box(
            Modifier
                .matchParentSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = actions.onDismiss),
        )
    }
    val density = LocalDensity.current
    Layout(
        content = { SelectionPopupCard(state, actions) },
        modifier = Modifier.matchParentSize(),
    ) { measurables, constraints ->
        val card = measurables.first().measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val margin = with(density) { PopupMargin.roundToPx() }
            val gap = with(density) { 10.dp.roundToPx() }
            // Android's selection handles hang below the text.
            val handles = with(density) { 30.dp.roundToPx() }
            val rect = state.rect
            val (x, y) = if (rect == null) {
                (constraints.maxWidth - card.width) / 2 to constraints.maxHeight - card.height - with(density) { 72.dp.roundToPx() }
            } else {
                val left = with(density) { rect.left.dp.toPx() }
                val right = with(density) { rect.right.dp.toPx() }
                val top = with(density) { rect.top.dp.toPx() }.roundToInt()
                val bottom = with(density) { rect.bottom.dp.toPx() }.roundToInt()
                val centre = ((left + right) / 2).roundToInt()
                val above = top - gap - card.height
                val below = bottom + (if (state.fromTap) gap else handles)
                val y = when {
                    above >= margin -> above
                    below + card.height <= constraints.maxHeight - margin -> below
                    else -> ((constraints.maxHeight - card.height) / 2)
                }
                centre - card.width / 2 to y
            }
            card.place(
                x.coerceIn(margin, (constraints.maxWidth - card.width - margin).coerceAtLeast(margin)),
                y.coerceIn(margin, (constraints.maxHeight - card.height - margin).coerceAtLeast(margin)),
            )
        }
    }
}

/**
 * The web's SelectionPopup in the app's card style: the ten colours (a tap highlights, or recolours
 * a highlight), the four styles, then Note, Look up, Copy, Share and, on a highlight, Delete; its
 * note below. Where that row of styles and buttons is wider than the page has room for (a larger
 * Display size, split screen), the buttons get a row of their own under the styles, so the last of
 * them (Delete) stays on screen instead of being squeezed to nothing.
 */
@Composable
fun SelectionPopupCard(state: SelectionPopupState, actions: SelectionPopupActions, modifier: Modifier = Modifier) = BoxWithConstraints(modifier) {
    val colors = OttershelfTheme.colors
    // What the host leaves: the page less its margin each side, less the card's padding.
    val available = (maxWidth - PopupMargin * 2 - CardPadding * 2).coerceAtLeast(0.dp)
    Surface(
        color = colors.popover,
        shape = RoundedCornerShape(OttershelfTheme.radii.lg),
        border = BorderStroke(1.dp, colors.border),
        shadowElevation = 10.dp,
    ) {
        if (!state.canHighlight) {
            // foliate couldn't place this selection in the book (no CFI): it can be copied and
            // shared, but not highlighted or noted, since the highlight would have no place.
            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                actions.onLookUp?.let { PopupAction("BookA", stringResource(R.string.reader_lookup_action), colors.foreground, it) }
                PopupAction("Copy", stringResource(R.string.reader_copy), colors.foreground, actions.onCopy)
                PopupAction("Share2", stringResource(R.string.reader_share), colors.foreground, actions.onShare)
            }
            return@Surface
        }
        // Look up makes the row one button longer: with Delete too, the popup widens to hold it
        // (the colours spread out); otherwise it keeps the colour row's width. A page too narrow
        // for that one row gets two (the styles, then the buttons) at the width there is.
        val buttons = 3 + (if (actions.onLookUp != null) 1 else 0) + (if (state.annotationId != null) 1 else 0)
        val oneRow = maxOf(IntrinsicWidth, actionRowWidth(buttons))
        val split = oneRow > available
        val actionButtons: @Composable () -> Unit = {
            PopupAction(
                icon = "StickyNote",
                label = stringResource(if (state.note.isNullOrBlank()) R.string.reader_note_add else R.string.reader_note_edit),
                tint = if (state.note.isNullOrBlank()) colors.foreground else colors.primary,
                onClick = actions.onNote,
            )
            actions.onLookUp?.let { PopupAction("BookA", stringResource(R.string.reader_lookup_action), colors.foreground, it) }
            PopupAction("Copy", stringResource(R.string.reader_copy), colors.foreground, actions.onCopy)
            PopupAction("Share2", stringResource(R.string.reader_share), colors.foreground, actions.onShare)
            if (state.annotationId != null) {
                PopupAction("Trash", stringResource(R.string.reader_highlight_delete), colors.destructive, actions.onDelete)
            }
        }
        Column(Modifier.padding(horizontal = CardPadding, vertical = 8.dp).width(if (split) available else oneRow)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val current = displayHex(state.color)
                HighlightColor.entries.forEach { c ->
                    ColorDot(c, selected = state.annotationId != null && c.hex.equals(current, ignoreCase = true)) { actions.onColor(c.hex) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), thickness = 1.dp, color = colors.border)
            Row(verticalAlignment = Alignment.CenterVertically) {
                HIGHLIGHT_STYLES.forEach { style ->
                    StyleChip(style, displayColor(state.color), selected = state.style == style) { actions.onStyle(style) }
                    Spacer(Modifier.width(4.dp))
                }
                if (!split) {
                    Spacer(Modifier.weight(1f, fill = false).width(8.dp))
                    actionButtons()
                }
            }
            if (split) {
                Row(
                    Modifier.padding(top = 6.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) { actionButtons() }
            }
            val note = state.note
            if (!note.isNullOrBlank()) {
                Row(
                    Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(OttershelfTheme.radii.md))
                        .background(colors.muted.copy(alpha = 0.5f))
                        .clickable(onClick = actions.onNote)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    LucideIcon("StickyNote", contentDescription = null, tint = colors.mutedForeground, size = 14.dp, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = colors.foreground,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The card's padding on each side. */
private val CardPadding = 8.dp

/** The least room between the popup and the page's edges ([SelectionPopupHost]). */
private val PopupMargin = 8.dp

/** The popup is as wide as its colour row; the note under it wraps to that width. */
private val IntrinsicWidth = 26.dp * 10 + 4.dp * 9

/** The style chips (32dp and a 4dp gap each), the 8dp gap, then [buttons] actions of 36dp. */
private fun actionRowWidth(buttons: Int) = (32.dp + 4.dp) * HIGHLIGHT_STYLES.size + 8.dp + 36.dp * buttons

@Composable
private fun ColorDot(color: HighlightColor, selected: Boolean, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = stringResource(colorLabel(color)), onClick = onClick)
            .then(if (selected) Modifier.border(2.dp, colors.foreground, CircleShape) else Modifier)
            .padding(if (selected) 4.dp else 2.dp)
            .background(color.color, CircleShape),
    )
}

@Composable
private fun StyleChip(style: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.sm)
    val label = stringResource(styleLabel(style))
    Box(
        Modifier
            .size(32.dp)
            .clip(shape)
            .background(if (selected) colors.accentTint else Color.Transparent)
            .border(1.dp, if (selected) colors.primary else colors.border, shape)
            .clickable(role = Role.RadioButton, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        StyleSample(style, color, if (selected) colors.primary else colors.foreground)
    }
}

/** "Ab" drawn the way [style] marks text, in [color]. */
@Composable
internal fun StyleSample(style: String, color: Color, textColor: Color, fontSize: Int = 14) {
    val base = MaterialTheme.typography.labelLarge.copy(fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, color = textColor)
    when (style) {
        STYLE_UNDERLINE -> Text("Ab", style = base.copy(textDecoration = TextDecoration.Underline))
        STYLE_STRIKETHROUGH -> Text("Ab", style = base.copy(textDecoration = TextDecoration.LineThrough))
        STYLE_SQUIGGLY -> Text(
            "Ab",
            style = base,
            modifier = Modifier.drawBehind {
                val step = 2.5.dp.toPx()
                val amp = 1.5.dp.toPx()
                val y = size.height - amp
                val path = Path().apply {
                    moveTo(0f, y)
                    var x = 0f
                    var up = true
                    while (x < size.width) {
                        x += step
                        lineTo(x, if (up) y - amp else y + amp)
                        up = !up
                    }
                }
                drawPath(path, color, style = Stroke(width = 1.2.dp.toPx()))
            },
        )
        else -> Text(
            "Ab",
            style = base,
            modifier = Modifier
                .clip(RoundedCornerShape(2.dp))
                .background(color.copy(alpha = 0.45f))
                .padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun PopupAction(icon: String, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(OttershelfTheme.radii.md))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(icon, contentDescription = label, tint = tint, size = 19.dp)
    }
}

internal fun colorLabel(color: HighlightColor): Int = when (color) {
    HighlightColor.YELLOW -> R.string.reader_color_yellow
    HighlightColor.GREEN -> R.string.reader_color_green
    HighlightColor.BLUE -> R.string.reader_color_blue
    HighlightColor.PINK -> R.string.reader_color_pink
    HighlightColor.ORANGE -> R.string.reader_color_orange
    HighlightColor.RED -> R.string.reader_color_red
    HighlightColor.OLIVE -> R.string.reader_color_olive
    HighlightColor.CYAN -> R.string.reader_color_cyan
    HighlightColor.PURPLE -> R.string.reader_color_purple
    HighlightColor.GRAY -> R.string.reader_color_gray
}

internal fun styleLabel(style: String): Int = when (style) {
    STYLE_UNDERLINE -> R.string.reader_style_underline
    STYLE_STRIKETHROUGH -> R.string.reader_style_strikethrough
    STYLE_SQUIGGLY -> R.string.reader_style_squiggly
    else -> R.string.reader_style_highlight
}

// --- dialogs ---------------------------------------------------------------------------------

/** The web's NoteDialog in the app's dialog style: the quote, then the note. Empty clears it. */
@Composable
fun NoteDialog(state: NoteDialogState, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = OttershelfTheme.colors
    var text by rememberSaveable(state) { mutableStateOf(state.initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.popover,
        title = { Text(stringResource(if (state.initial.isBlank()) R.string.reader_note_add else R.string.reader_note_edit)) },
        text = { NoteDialogBody(state.quote, text, { text = it }) },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.reader_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_cancel)) } },
    )
}

@Composable
internal fun NoteDialogBody(quote: String, text: String, onText: (String) -> Unit) {
    val colors = OttershelfTheme.colors
    Column {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(3.dp).height(IntrinsicQuoteBar).background(colors.primary.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Text(
                "“${quote.trim()}”",
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = colors.mutedForeground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { onText(it.take(NOTE_MAX)) },
            modifier = Modifier.padding(top = 14.dp).fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.reader_note_placeholder)) },
            minLines = 4,
            maxLines = 8,
            shape = RoundedCornerShape(OttershelfTheme.radii.md),
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
    }
}

private val IntrinsicQuoteBar = 40.dp
private const val NOTE_MAX = 10_000

@Composable
fun DeleteHighlightDialog(annotation: Annotation, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = OttershelfTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.popover,
        title = { Text(stringResource(R.string.reader_highlight_delete_question)) },
        text = {
            Column {
                Text(
                    "“${annotation.text.trim()}”",
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = colors.mutedForeground,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (annotation.hasNote) {
                    Text(
                        stringResource(R.string.reader_highlight_delete_note),
                        modifier = Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.reader_delete), color = colors.destructive) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_cancel)) } },
    )
}

/** The ribbon at the top corner of a bookmarked page (shown with the bars hidden too). */
@Composable
fun BookmarkRibbon(modifier: Modifier = Modifier) {
    val color = OttershelfTheme.colors.primary
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.align(Alignment.TopEnd).padding(end = 20.dp).size(width = 14.dp, height = 24.dp)) {
            val notch = size.height * 0.28f
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(size.width / 2, size.height - notch)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path, color.copy(alpha = 0.9f))
        }
    }
}
