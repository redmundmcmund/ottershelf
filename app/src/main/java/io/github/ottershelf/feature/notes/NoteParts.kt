package io.github.ottershelf.feature.notes

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.core.util.IsoTime
import io.github.ottershelf.feature.notes.model.Annotation
import io.github.ottershelf.feature.notes.model.ColorCount
import io.github.ottershelf.feature.notes.model.HIGHLIGHT_STYLES
import io.github.ottershelf.feature.notes.model.HighlightColors
import io.github.ottershelf.feature.notes.model.styleIcon
import io.github.ottershelf.feature.quotes.QuotePosition
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.ReaderRouter
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.theme.OttershelfFonts
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A highlight's colour. */
internal fun highlightColor(hex: String?): Color = Color(HighlightColors.argb(hex))

/** Lucide's heart, filled: a liked note. */
internal val HeartFilled: ImageVector by lazy {
    ImageVector.Builder("HeartFilled", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = addPathNodes(
                "M 2 9.5 a 5.5 5.5 0 0 1 9.591 -3.676 .56 .56 0 0 0 .818 0 A 5.49 5.49 0 0 1 22 9.5 c 0 2.29 -1.5 4 -3 5.5 " +
                    "l -5.492 5.313 a 2 2 0 0 1 -3 .019 L 5 15 c -1.5 -1.5 -3 -3.2 -3 -5.5",
            ),
            fill = SolidColor(Color.Black),
        )
        .build()
}

/** The quote's text face: the serif, as the web's reader and quote blocks. */
@Composable
internal fun quoteStyle(size: Int = 16) = MaterialTheme.typography.bodyLarge.copy(
    fontFamily = OttershelfFonts.Serif,
    fontSize = size.sp,
    lineHeight = (size * 1.5).sp,
)

/** "12 Mar 2026" in the phone's zone, or null. */
internal fun dateText(iso: String?): String? {
    val ms = IsoTime.parse(iso) ?: return null
    return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}

/** The colours of a set of highlights as one rounded bar, biggest share first. */
@Composable
internal fun ColorBreakdownBar(breakdown: List<ColorCount>, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val shares = remember(breakdown) { NotesLogic.shares(breakdown) }
    val colors = OttershelfTheme.colors
    Row(
        modifier.fillMaxWidth().height(height).clip(CircleShape).background(colors.muted),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        shares.forEach { (hex, share) ->
            Box(Modifier.weight(share.coerceAtLeast(0.01f)).fillMaxHeight().background(highlightColor(hex)))
        }
    }
}

/** A colour's dot with its count ("● 12"), for the breakdown legend. */
@Composable
internal fun ColorLegend(breakdown: List<ColorCount>, modifier: Modifier = Modifier, max: Int = 6) {
    val colors = OttershelfTheme.colors
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        breakdown.sortedByDescending { it.count }.take(max).forEach {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(highlightColor(it.color), CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(it.count.toString(), style = MaterialTheme.typography.labelSmall, color = colors.mutedForeground)
            }
        }
    }
}

/** The highlighted text beside a bar in its colour (the web's highlight mark), up to [maxLines]. */
@Composable
internal fun QuoteText(note: Annotation, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, size: Int = 16) {
    val colors = OttershelfTheme.colors
    Row(modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(highlightColor(note.color), CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(
            note.text,
            style = quoteStyle(size),
            color = colors.foreground,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The user's note under a highlight: italic and dim behind a muted rule (the web's note line). */
@Composable
internal fun NoteLine(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val colors = OttershelfTheme.colors
    Row(modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(colors.border))
        Spacer(Modifier.width(10.dp))
        LucideIcon("NotebookPen", contentDescription = null, tint = colors.mutedForeground, size = 13.dp, modifier = Modifier.padding(top = 3.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            color = colors.mutedForeground,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A small icon button in a card's footer. */
@Composable
internal fun CardIcon(icon: String, description: String, onClick: () -> Unit, tint: Color = OttershelfTheme.colors.mutedForeground, vector: ImageVector? = null) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (vector != null) Icon(vector, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        else LucideIcon(icon, contentDescription = null, tint = tint, size = 18.dp)
    }
}

/** The heart: filled in the destructive red once liked. */
@Composable
internal fun LikeIcon(liked: Boolean, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    CardIcon(
        icon = "Heart",
        description = stringResource(if (liked) R.string.notes_unlike else R.string.notes_like),
        onClick = onClick,
        tint = if (liked) colors.destructive else colors.mutedForeground,
        vector = if (liked) HeartFilled else null,
    )
}

/** The style's icon in the highlight's colour on a muted tile (the web's book-mode marker). */
@Composable
internal fun StyleTile(note: Annotation, size: Dp = 26.dp) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.sm)
    Box(Modifier.size(size).background(colors.muted, shape).border(1.dp, colors.border, shape), contentAlignment = Alignment.Center) {
        LucideIcon(styleIcon(note.style), contentDescription = null, tint = highlightColor(note.color), size = size * 0.6f)
    }
}

/** "KOReader" / "Kobo" for highlights synced from a device. */
@Composable
internal fun OriginPill(origin: String) {
    val label = when (origin) {
        "koreader" -> "KOReader"
        "kobo" -> "Kobo"
        else -> return
    }
    val colors = OttershelfTheme.colors
    Text(
        label,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
        color = colors.mutedForeground,
        modifier = Modifier.background(colors.muted, CircleShape).padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

/** What a highlight card can do; null hides the action. */
internal class HighlightActions(
    val onOpenReader: ((Annotation) -> Unit)? = null,
    val onLike: ((Annotation) -> Unit)? = null,
    val onShareImage: ((Annotation) -> Unit)? = null,
    val onSaveNote: ((Annotation, String?) -> Unit)? = null,
    val onColor: ((Annotation, String) -> Unit)? = null,
    val onStyle: ((Annotation, String) -> Unit)? = null,
    val onDelete: ((Annotation) -> Unit)? = null,
    val onOpenBook: ((Annotation) -> Unit)? = null,
    /** A quote's kept photo (`QuotePhotos`); shown only on cards passed `photo = true`. */
    val onViewPhoto: ((Annotation) -> Unit)? = null,
    /** The card took a failed note's text (its `draft`) back into its editor. */
    val onDraftShown: ((Annotation) -> Unit)? = null,
)

/**
 * A highlight on the book's Highlights screen: style tile, the text, the user's note (edited in place),
 * the colour and style panel, then date and the actions (reader, note, colour, image, like, delete).
 */
@Composable
internal fun HighlightCard(
    note: Annotation,
    liked: Boolean,
    canOpenReader: Boolean,
    saving: Boolean,
    actions: HighlightActions,
    modifier: Modifier = Modifier,
    photo: Boolean = false,
    /** The user's text from a note save that failed: the editor opens with it again. */
    draft: String? = null,
) {
    val colors = OttershelfTheme.colors
    var editing by rememberSaveable(note.id) { mutableStateOf(false) }
    var styling by rememberSaveable(note.id) { mutableStateOf(false) }
    var expanded by rememberSaveable(note.id) { mutableStateOf(false) }
    var restored by rememberSaveable(note.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(draft) {
        if (draft != null) {
            restored = draft
            editing = true
            actions.onDraftShown?.invoke(note)
        }
    }
    DashCard(modifier.fillMaxWidth().animateContentSize(), contentPadding = PaddingValues(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp)) {
        Row(Modifier.padding(end = 8.dp)) {
            StyleTile(note)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    note.text,
                    style = quoteStyle(),
                    color = colors.foreground,
                    maxLines = if (expanded) Int.MAX_VALUE else 10,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(role = Role.Button) { expanded = !expanded },
                )
                if (editing && actions.onSaveNote != null) {
                    NoteEditor(
                        initial = restored ?: note.note.orEmpty(),
                        saving = saving,
                        onSave = { actions.onSaveNote.invoke(note, it); editing = false; restored = null },
                        onCancel = { editing = false; restored = null },
                        modifier = Modifier.padding(top = 10.dp),
                    )
                } else if (note.hasNote) {
                    NoteLine(note.note.orEmpty(), Modifier.padding(top = 10.dp).clickable(enabled = actions.onSaveNote != null) { editing = true })
                }
                if (styling && actions.onColor != null) {
                    StylePanel(note, onColor = { actions.onColor.invoke(note, it) }, onStyle = { actions.onStyle?.invoke(note, it) }, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).padding(start = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                dateText(note.highlightedAt ?: note.createdAt)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = colors.mutedForeground, maxLines = 1)
                }
                OriginPill(note.origin)
                if (saving) Text(stringResource(R.string.notes_saving), style = MaterialTheme.typography.labelSmall, color = colors.primary)
            }
            if (canOpenReader && actions.onOpenReader != null) CardIcon("BookOpen", stringResource(R.string.notes_open_reader), { actions.onOpenReader.invoke(note) })
            if (actions.onSaveNote != null) {
                CardIcon("NotebookPen", stringResource(if (note.hasNote) R.string.notes_edit_note else R.string.notes_add_note), { editing = !editing }, tint = if (editing) colors.primary else colors.mutedForeground)
            }
            if (actions.onColor != null) CardIcon("Palette", stringResource(R.string.notes_colour_style), { styling = !styling }, tint = if (styling) colors.primary else colors.mutedForeground)
            if (photo) actions.onViewPhoto?.let { CardIcon("ScanLine", stringResource(R.string.notes_view_photo), { it(note) }) }
            actions.onShareImage?.let { CardIcon("Image", stringResource(R.string.notes_share_image), { it(note) }) }
            actions.onLike?.let { LikeIcon(liked) { it(note) } }
            actions.onDelete?.let { CardIcon("Trash", stringResource(R.string.notes_delete), { it(note) }) }
        }
    }
}

/**
 * A note in the Notes feed: the book (cover, title, author, chapter) on top, then the highlight and
 * the user's note, then date and the actions (image, reader, like). The book row opens the book.
 */
@Composable
internal fun FeedNoteCard(
    note: Annotation,
    cover: Any?,
    liked: Boolean,
    actions: HighlightActions,
    modifier: Modifier = Modifier,
    textLines: Int = 12,
    photo: Boolean = false,
) {
    val colors = OttershelfTheme.colors
    var expanded by rememberSaveable(note.id) { mutableStateOf(false) }
    DashCard(modifier.fillMaxWidth().animateContentSize(), contentPadding = PaddingValues(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 2.dp)) {
        Row(
            Modifier
                .padding(end = 8.dp)
                .clip(RoundedCornerShape(OttershelfTheme.radii.md))
                .then(if (actions.onOpenBook != null) Modifier.clickable { actions.onOpenBook.invoke(note) } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(cover, note.bookTitle, Modifier.width(34.dp), authors = note.author, seed = note.bookId.toString())
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    note.bookTitle ?: stringResource(R.string.notes_untitled),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = listOfNotNull(note.author, note.chapterTitle?.takeIf { it.isNotBlank() }).joinToString("  ·  ")
                if (sub.isNotEmpty()) {
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column(
            Modifier
                .padding(top = 12.dp, end = 8.dp)
                .clickable { expanded = !expanded },
        ) {
            QuoteText(note, maxLines = if (expanded) Int.MAX_VALUE else textLines)
            if (note.hasNote) NoteLine(note.note.orEmpty(), Modifier.padding(top = 10.dp), maxLines = if (expanded) Int.MAX_VALUE else 4)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LucideIcon(styleIcon(note.style), contentDescription = null, tint = highlightColor(note.color), size = 13.dp)
                dateText(note.highlightedAt ?: note.createdAt)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = colors.mutedForeground, maxLines = 1)
                }
                OriginPill(note.origin)
            }
            if (photo) actions.onViewPhoto?.let { CardIcon("ScanLine", stringResource(R.string.notes_view_photo), { it(note) }) }
            actions.onShareImage?.let { CardIcon("Image", stringResource(R.string.notes_share_image), { it(note) }) }
            actions.onOpenReader?.let { open -> if (canOpenInReader(note)) CardIcon("BookOpen", stringResource(R.string.notes_open_reader), { open(note) }) }
            actions.onLike?.let { LikeIcon(liked) { it(note) } }
        }
    }
}

/**
 * Where a hub highlight opens: its jump file in the reader for that format (a CFI in the foliate
 * reader, a page in the PDF reader; ui.nav.ReaderRouter.annotation), or null (a quote has no place
 * in the book, and some positions no reader here can show).
 */
internal fun readerRoute(note: Annotation): Route? {
    if (QuotePosition.isQuote(note.cfi)) return null
    val file = note.jumpFileId ?: return null
    return ReaderRouter.annotation(note.bookId, file, note.jumpFileFormat, note.bookTitle.orEmpty(), note.cfi, note.pageno)
}

/** A hub highlight a reader here can open ([readerRoute]). */
internal fun canOpenInReader(note: Annotation): Boolean = readerRoute(note) != null

/** The user's note, edited in place: the field, Cancel and Save (blank removes the note). */
@Composable
internal fun NoteEditor(initial: String, saving: Boolean, onSave: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf(initial) }
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(NOTE_MAX) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
            placeholder = { Text(stringResource(R.string.notes_note_hint)) },
            minLines = 2,
            maxLines = 8,
            shape = RoundedCornerShape(OttershelfTheme.radii.md),
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.notes_cancel)) }
            Spacer(Modifier.width(6.dp))
            AccentButton(stringResource(R.string.notes_save), onClick = { onSave(text) }, enabled = !saving)
        }
    }
}

private const val NOTE_MAX = 10_000

/** The web's colour and style panel: ten colour dots, then the five styles. */
@Composable
internal fun StylePanel(note: Annotation, onColor: (String) -> Unit, onStyle: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val radii = OttershelfTheme.radii
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.background, RoundedCornerShape(radii.md))
            .border(1.dp, colors.border, RoundedCornerShape(radii.md))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HighlightColors.all.forEach { (name, hex) ->
                val picked = note.color.equals(hex, ignoreCase = true)
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(highlightColor(hex))
                        .border(2.dp, if (picked) colors.foreground else Color.Transparent, CircleShape)
                        .clickable(role = Role.Button) { if (!picked) onColor(hex) }
                        .semantics { contentDescription = name },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HIGHLIGHT_STYLES.forEach { style ->
                val picked = note.style == style
                val shape = RoundedCornerShape(radii.sm)
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(shape)
                        .background(if (picked) colors.accentTint else Color.Transparent)
                        .border(1.dp, if (picked) colors.primary else colors.border, shape)
                        .clickable(role = Role.Button) { if (!picked) onStyle(style) }
                        .semantics { contentDescription = style },
                    contentAlignment = Alignment.Center,
                ) {
                    LucideIcon(styleIcon(style), contentDescription = null, tint = if (picked) highlightColor(note.color) else colors.mutedForeground, size = 16.dp)
                }
            }
        }
    }
}

/** A filter chip in the stats filter row's look: 36dp, card or accent tint, an icon, a label and a chevron. */
@Composable
internal fun FilterChipButton(
    label: String,
    icon: String?,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    chevron: Boolean = false,
    iconVector: ImageVector? = null,
    menu: @Composable () -> Unit = {},
) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.lg)
    Box(modifier) {
        Row(
            Modifier
                .height(36.dp)
                .clip(shape)
                .background(if (active) colors.accentTint else colors.card)
                .border(1.dp, if (active) colors.primary.copy(alpha = 0.5f) else colors.border, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val tint = if (active) colors.primary else colors.mutedForeground
            when {
                dot != null -> Box(Modifier.size(12.dp).background(dot, CircleShape))
                iconVector != null -> Icon(iconVector, contentDescription = null, tint = colors.destructive, modifier = Modifier.size(16.dp))
                icon != null -> LucideIcon(icon, contentDescription = null, tint = tint, size = 16.dp)
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) colors.primary else colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
            if (chevron) LucideIcon("ChevronDown", contentDescription = null, tint = colors.mutedForeground, size = 14.dp)
        }
        menu()
    }
}

/** A dropdown in the app's popover colour. */
@Composable
internal fun NotesMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = OttershelfTheme.colors.popover, content = content)
}
