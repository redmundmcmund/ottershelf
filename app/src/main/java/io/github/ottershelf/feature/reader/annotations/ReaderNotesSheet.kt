package io.github.ottershelf.feature.reader.annotations

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.core.util.IsoTime
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.components.PillProgressBar
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

enum class NotesTab { Highlights, Bookmarks, Search }

/** What the notes sheet can do. */
class NotesSheetActions(
    val onJump: (String) -> Unit = {},
    val onDeleteBookmark: (Long) -> Unit = {},
    val onSearch: (String) -> Unit = {},
    val onClearSearch: () -> Unit = {},
)

/** The reader's notes (from the top bar, next to Contents): Highlights, Bookmarks and Search tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderNotesSheet(state: ReaderNotesUiState, initialTab: NotesTab, actions: NotesSheetActions, onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OttershelfTheme.colors.card,
    ) {
        ReaderNotesContent(state, tab, { tab = it }, actions, Modifier.fillMaxHeight(0.9f))
    }
}

/** The sheet's content (stateless apart from the filter and the typed query; screenshot-tested). */
@Composable
fun ReaderNotesContent(
    state: ReaderNotesUiState,
    tab: NotesTab,
    onTab: (NotesTab) -> Unit,
    actions: NotesSheetActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        NotesTabs(tab, state.annotations.size, state.bookmarks.size, onTab)
        when (tab) {
            NotesTab.Highlights -> HighlightsTab(state, actions)
            NotesTab.Bookmarks -> BookmarksTab(state, actions)
            NotesTab.Search -> SearchTab(state.search, actions)
        }
    }
}

@Composable
private fun NotesTabs(tab: NotesTab, highlights: Int, bookmarks: Int, onTab: (NotesTab) -> Unit) {
    val colors = OttershelfTheme.colors
    val radii = OttershelfTheme.radii
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(radii.md))
            .background(colors.muted)
            .padding(3.dp),
    ) {
        NotesTab.entries.forEach { t ->
            val selected = t == tab
            val (label, icon, count) = when (t) {
                NotesTab.Highlights -> Triple(stringResource(R.string.reader_notes_highlights), "Highlighter", highlights)
                NotesTab.Bookmarks -> Triple(stringResource(R.string.reader_notes_bookmarks), "Bookmark", bookmarks)
                NotesTab.Search -> Triple(stringResource(R.string.reader_notes_search), "Search", -1)
            }
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(radii.sm))
                    .background(if (selected) colors.card else Color.Transparent)
                    .clickable(role = Role.Tab, onClick = { onTab(t) })
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (selected) colors.foreground else colors.mutedForeground
                LucideIcon(icon, contentDescription = null, tint = if (selected) colors.primary else tint, size = 15.dp)
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), color = tint, maxLines = 1)
                if (count > 0) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        count.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                        color = if (selected) colors.primary else colors.mutedForeground,
                    )
                }
            }
        }
    }
}

// --- Highlights ------------------------------------------------------------------------------

@Composable
private fun ColumnScope.HighlightsTab(state: ReaderNotesUiState, actions: NotesSheetActions) {
    if (!state.loaded) {
        LoadingState(Modifier.fillMaxWidth().weight(1f))
        return
    }
    if (state.annotations.isEmpty()) {
        EmptyState(stringResource(R.string.reader_notes_no_highlights), icon = "Highlighter", modifier = Modifier.fillMaxWidth().weight(1f))
        return
    }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val counts = remember(state.annotations) { highlightColorCounts(state.annotations) }
    if (filter != null && counts.none { it.first == filter }) filter = null
    val groups = remember(state.annotations, filter) { highlightGroups(state.annotations, filter) }
    val format = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    if (counts.size > 1) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(bottom = 6.dp),
        ) {
            item {
                FilterPill(selected = filter == null, onClick = { filter = null }) {
                    Text(stringResource(R.string.reader_notes_all, state.annotations.size), style = pillText(), color = pillColor(filter == null))
                }
            }
            items(counts, key = { it.first }) { (hex, count) ->
                FilterPill(selected = filter == hex, onClick = { filter = if (filter == hex) null else hex }) {
                    Box(Modifier.size(12.dp).background(parseHex(hex) ?: HighlightColor.YELLOW.color, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(count.toString(), style = pillText(), color = pillColor(filter == hex))
                }
            }
        }
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
        groups.forEachIndexed { index, group ->
            item(key = "chapter-$index") { ChapterHeader(group.chapter ?: stringResource(R.string.reader_notes_no_chapter)) }
            items(group.items, key = { it.id }) { a ->
                HighlightRow(a, format) { a.cfi?.let(actions.onJump) }
            }
        }
    }
}

@Composable
private fun pillText() = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp)

@Composable
private fun pillColor(selected: Boolean) = if (selected) OttershelfTheme.colors.primary else OttershelfTheme.colors.foreground

@Composable
private fun FilterPill(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.md)
    Row(
        Modifier
            .clip(shape)
            .background(if (selected) colors.accentTint else colors.card)
            .border(1.dp, if (selected) colors.primary.copy(alpha = 0.6f) else colors.border, shape)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun ChapterHeader(title: String) {
    Text(
        title,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        color = OttershelfTheme.colors.mutedForeground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun HighlightRow(a: Annotation, format: DateFormat, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(OttershelfTheme.radii.lg))
            .background(colors.cardRow)
            .clickable(onClick = onClick)
            .height(IntrinsicSize.Min)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(displayColor(a.color)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                a.text.trim(),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = colors.foreground,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            val note = a.note
            if (!note.isNullOrBlank()) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                    LucideIcon("StickyNote", contentDescription = null, tint = colors.primary, size = 13.dp, modifier = Modifier.padding(top = 3.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        note.trim(),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                        color = colors.mutedForeground,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            MetaLine(dateOf(a.highlightedAt ?: a.createdAt, format), a.local, a.origin.takeIf { it != "web" })
        }
    }
}

@Composable
private fun MetaLine(date: String?, local: Boolean, origin: String? = null) {
    val colors = OttershelfTheme.colors
    val parts = listOfNotNull(date, origin?.let { originLabel(it) })
    if (parts.isEmpty() && !local) return
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (parts.isNotEmpty()) {
            Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = colors.mutedForeground)
        }
        if (local) {
            if (parts.isNotEmpty()) Spacer(Modifier.width(8.dp))
            LucideIcon("CloudOff", contentDescription = null, tint = colors.mutedForeground, size = 12.dp)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.reader_notes_waiting), style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = colors.mutedForeground)
        }
    }
}

@Composable
private fun originLabel(origin: String): String = when (origin) {
    "koreader" -> "KOReader"
    "kobo" -> "Kobo"
    else -> origin
}

private fun dateOf(iso: String?, format: DateFormat): String? = IsoTime.parse(iso)?.let { format.format(Date(it)) }

// --- Bookmarks -------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.BookmarksTab(state: ReaderNotesUiState, actions: NotesSheetActions) {
    if (!state.loaded) {
        LoadingState(Modifier.fillMaxWidth().weight(1f))
        return
    }
    if (state.bookmarks.isEmpty()) {
        EmptyState(stringResource(R.string.reader_notes_no_bookmarks), icon = "Bookmark", modifier = Modifier.fillMaxWidth().weight(1f))
        return
    }
    val format = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    var confirm by remember { mutableStateOf<Bookmark?>(null) }
    Text(
        stringResource(R.string.reader_notes_bookmarks_hint),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
        color = OttershelfTheme.colors.mutedForeground,
    )
    LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(state.bookmarks, key = { it.id }) { b ->
            BookmarkRow(b, format, onClick = { b.cfi?.let(actions.onJump) }, onLongClick = { confirm = b }, onSwiped = { actions.onDeleteBookmark(b.id) })
        }
    }
    confirm?.let { b ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = OttershelfTheme.colors.popover,
            title = { Text(stringResource(R.string.reader_bookmark_delete_question)) },
            text = { Text(b.title) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    actions.onDeleteBookmark(b.id)
                }) { Text(stringResource(R.string.reader_delete), color = OttershelfTheme.colors.destructive) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.reader_cancel)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkRow(b: Bookmark, format: DateFormat, onClick: () -> Unit, onLongClick: () -> Unit, onSwiped: () -> Unit) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.lg)
    val swipe = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = swipe,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onSwiped() },
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(shape).background(colors.destructive.copy(alpha = 0.85f)).padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                LucideIcon("Trash", contentDescription = stringResource(R.string.reader_delete), tint = Color.White, size = 20.dp)
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.card)
                .background(colors.cardRow)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LucideIcon("Bookmark", contentDescription = null, tint = colors.primary, size = 18.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    b.title.ifBlank { "-" },
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                    color = colors.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                MetaLine(dateOf(b.createdAt, format), b.local)
            }
        }
    }
}

// --- Search ----------------------------------------------------------------------------------

@Composable
private fun ColumnScope.SearchTab(search: SearchState, actions: NotesSheetActions) {
    val colors = OttershelfTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf(search.query) }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        placeholder = { Text(stringResource(R.string.reader_search_placeholder)) },
        leadingIcon = { LucideIcon("Search", contentDescription = null, tint = colors.mutedForeground, size = 18.dp) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = {
                    query = ""
                    actions.onClearSearch()
                }) { LucideIcon("X", contentDescription = stringResource(R.string.reader_search_clear), tint = colors.mutedForeground, size = 18.dp) }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(OttershelfTheme.radii.md),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            keyboard?.hide()
            actions.onSearch(query)
        }),
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        val status = when {
            search.searching -> stringResource(R.string.reader_search_searching, (search.progress * 100).roundToInt())
            search.error != null -> stringResource(R.string.reader_search_failed)
            search.done && search.hits.isEmpty() -> stringResource(R.string.reader_search_none, search.query)
            search.done -> pluralStringResource(R.plurals.reader_search_results, search.hits.size, search.hits.size)
            else -> null
        }
        if (search.searching) PillProgressBar(search.progress, Modifier.padding(bottom = 8.dp), height = 3.dp)
        if (status != null) {
            Text(status, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = colors.mutedForeground)
        }
    }
    if (search.query.isEmpty() && search.hits.isEmpty()) {
        EmptyState(stringResource(R.string.reader_search_hint), icon = "TextSearch", modifier = Modifier.fillMaxWidth().weight(1f))
        return
    }
    val groups = remember(search.hits) { searchGroups(search.hits) }
    LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
        groups.forEachIndexed { g, (section, hits) ->
            item(key = "s-$g") { ChapterHeader(section.ifBlank { stringResource(R.string.reader_notes_no_chapter) }) }
            hits.forEachIndexed { i, hit ->
                item(key = "h-$g-$i") { SearchRow(hit) { actions.onJump(hit.cfi) } }
            }
        }
    }
}

@Composable
private fun SearchRow(hit: SearchHit, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    val text = excerpt(hit, colors.foreground, colors.primary.copy(alpha = 0.22f))
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(OttershelfTheme.radii.lg))
            .background(colors.cardRow)
            .clickable(enabled = hit.cfi.isNotEmpty(), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        color = colors.mutedForeground,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/** "…before **match** after…", the match in the text colour on an accent wash. */
internal fun excerpt(hit: SearchHit, matchColor: Color, matchBackground: Color): AnnotatedString = buildAnnotatedString {
    val pre = hit.pre.replace(WHITESPACE, " ").trimStart()
    if (pre.length < hit.pre.length || pre.isNotEmpty()) append("…")
    append(pre.takeLast(PRE_CHARS))
    withStyle(SpanStyle(color = matchColor, fontWeight = FontWeight.SemiBold, background = matchBackground)) { append(hit.match) }
    append(hit.post.replace(WHITESPACE, " ").trimEnd())
    append("…")
}

private val WHITESPACE = Regex("\\s+")
private const val PRE_CHARS = 60
