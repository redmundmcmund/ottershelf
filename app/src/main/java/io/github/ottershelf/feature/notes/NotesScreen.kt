package io.github.ottershelf.feature.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ottershelf.R
import io.github.ottershelf.core.settings.NotesPrefs
import io.github.ottershelf.feature.notes.model.Annotation
import io.github.ottershelf.feature.notes.model.BookFacet
import io.github.ottershelf.feature.notes.model.HighlightColors
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.feature.quotes.AddQuoteAction
import io.github.ottershelf.feature.quotes.QuotePhotoDialog
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.TopBarActions
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.io.File

/** Notes: a root list (the drawer's Tracking > Notes) under the shell's toolbar and its search. */
@Composable
fun NotesScreen(route: Route.Notes, navigator: AppNavigator, contentPadding: PaddingValues) {
    val appContext = LocalContext.current.applicationContext
    val viewModel = appViewModel { NotesViewModel(it, route.query, appContext) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sharing by remember { mutableStateOf<Annotation?>(null) }
    var photo by remember { mutableStateOf<Pair<Long, File>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            if (message == NotesMessage.LikesFull) {
                snackbar.showSnackbar(resources.getString(R.string.notes_likes_full, NotesPrefs.MAX_ENTRIES), duration = SnackbarDuration.Long)
            }
        }
    }
    TopBarActions { AddQuoteAction(navigator, state.filter.bookId, state.filter.bookTitle) }
    val actions = remember(viewModel) {
        HighlightActions(
            onOpenReader = { note ->
                readerRoute(note)?.let(navigator::navigate)
            },
            onLike = viewModel::toggleLike,
            onShareImage = { sharing = it },
            onOpenBook = { navigator.navigate(Route.BookDetail(it.bookId)) },
            onViewPhoto = { note -> photo = viewModel.photo(note)?.let { note.id to it } },
        )
    }
    NotesContent(
        state = state,
        coverOf = viewModel::cover,
        actions = actions,
        onBook = viewModel::setBook,
        onColor = viewModel::setColor,
        onHasNote = viewModel::toggleHasNote,
        onLiked = viewModel::toggleLikedFilter,
        onShuffle = viewModel::shuffle,
        onCloseRandom = viewModel::closeRandom,
        onMemorize = { navigator.navigate(Route.Memorize(bookId = state.filter.bookId, liked = state.filter.liked)) },
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        contentPadding = contentPadding,
        snackbar = snackbar,
    )
    sharing?.let { note ->
        QuoteCardDialog(note, note.bookTitle, note.author, viewModel.cover(note.bookId), onDismiss = { sharing = null })
    }
    photo?.let { (id, file) -> QuotePhotoDialog(file, onDismiss = { photo = null }, onRemove = { viewModel.removePhoto(id); photo = null }) }
}

/**
 * The summary card (how many highlights, notes and books; Random note and Memorize), the filter
 * chips (book, colour, notes only, liked), then every highlight newest first as a card with its
 * book. Pull to refresh; more load near the end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesContent(
    state: NotesUiState,
    coverOf: (Long) -> Any?,
    actions: HighlightActions,
    onBook: (BookFacet?) -> Unit = {},
    onColor: (String?) -> Unit = {},
    onHasNote: () -> Unit = {},
    onLiked: () -> Unit = {},
    onShuffle: () -> Unit = {},
    onCloseRandom: () -> Unit = {},
    onMemorize: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val colors = OttershelfTheme.colors
    val direction = LocalLayoutDirection.current
    val pullState = rememberPullToRefreshState()
    val listState = rememberLazyListState()
    LaunchedEffect(listState, state.items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= listState.layoutInfo.totalItemsCount - 4) onLoadMore() }
    }
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding()),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = state.refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = colors.card,
                color = colors.primary,
            )
        },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(direction) + 12.dp,
                end = contentPadding.calculateEndPadding(direction) + 12.dp,
                top = 12.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "summary") { SummaryCard(state, onShuffle, onMemorize) }
            item(key = "filters") { FilterRow(state, onBook, onColor, onHasNote, onLiked) }
            when {
                state.loading && state.items.isEmpty() -> item(key = "loading") { LoadingState(Modifier.fillMaxWidth().padding(top = 40.dp)) }
                state.error != null && state.items.isEmpty() -> item(key = "error") {
                    ErrorState(onRetry, Modifier.fillMaxWidth(), message = stringResource(R.string.notes_load_failed), detail = state.error)
                }
                state.items.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        stringResource(
                            when {
                                state.filter.liked && state.liked.isEmpty() -> R.string.notes_empty_liked
                                state.filter.narrowed -> R.string.notes_none_match
                                else -> R.string.notes_empty
                            },
                        ),
                        icon = if (state.filter.liked) "Heart" else "Highlighter",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    items(state.items, key = { it.id }) { note ->
                        FeedNoteCard(note, coverOf(note.bookId), note.id in state.liked, actions, photo = note.id in state.photos)
                    }
                    if (state.loadingMore) item(key = "more") { LoadingState(Modifier.fillMaxWidth().padding(vertical = 12.dp)) }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding()))
    }
    if (state.randomOpen) {
        ModalBottomSheet(
            onDismissRequest = onCloseRandom,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.card,
        ) {
            RandomSheet(state, coverOf, actions, onShuffle)
        }
    }
}

@Composable
private fun SummaryCard(state: NotesUiState, onShuffle: () -> Unit, onMemorize: () -> Unit) {
    val colors = OttershelfTheme.colors
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(colors.primary.copy(alpha = 0.15f), RoundedCornerShape(OttershelfTheme.radii.lg)), contentAlignment = Alignment.Center) {
                LucideIcon("NotebookPen", contentDescription = null, tint = colors.primary, size = 22.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pluralStringResource(R.plurals.notes_highlights_count, state.total, state.total),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                )
                Text(
                    listOf(
                        pluralStringResource(R.plurals.notes_notes_count, state.withNotes, state.withNotes),
                        pluralStringResource(R.plurals.notes_books_count, state.books, state.books),
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedForeground,
                )
            }
        }
        state.filter.query?.let {
            Text(
                stringResource(R.string.notes_results_for, it),
                style = MaterialTheme.typography.labelMedium,
                color = colors.primary,
                modifier = Modifier.padding(top = 10.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.overview?.colorBreakdown?.takeIf { it.isNotEmpty() && !state.filter.narrowed }?.let {
            ColorBreakdownBar(it, Modifier.padding(top = 12.dp), height = 6.dp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.notes_random), onClick = onShuffle, icon = "Shuffle", modifier = Modifier.weight(1f), enabled = state.total > 0)
            SecondaryButton(stringResource(R.string.notes_memorize), onClick = onMemorize, icon = "Brain", modifier = Modifier.weight(1f), enabled = state.total > 0)
        }
    }
}

@Composable
private fun FilterRow(
    state: NotesUiState,
    onBook: (BookFacet?) -> Unit,
    onColor: (String?) -> Unit,
    onHasNote: () -> Unit,
    onLiked: () -> Unit,
) {
    val filter = state.filter
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var bookOpen by rememberSaveable { mutableStateOf(false) }
        FilterChipButton(
            label = filter.bookTitle ?: stringResource(R.string.notes_filter_book),
            icon = "BookOpen",
            active = filter.bookId != null,
            onClick = { bookOpen = true },
            chevron = true,
        ) {
            NotesMenu(bookOpen, { bookOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.notes_all_books)) }, onClick = { bookOpen = false; onBook(null) })
                state.bookOptions.forEach { book ->
                    DropdownMenuItem(
                        text = { MenuLine(book.bookTitle ?: stringResource(R.string.notes_untitled), book.count.toString()) },
                        onClick = { bookOpen = false; onBook(book) },
                    )
                }
            }
        }
        var colorOpen by rememberSaveable { mutableStateOf(false) }
        FilterChipButton(
            label = filter.color?.let { colorLabel(it) } ?: stringResource(R.string.notes_filter_colour),
            icon = "Palette",
            active = filter.color != null,
            onClick = { colorOpen = true },
            dot = filter.color?.let { highlightColor(it) },
            chevron = true,
        ) {
            NotesMenu(colorOpen, { colorOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.notes_all_colours)) }, onClick = { colorOpen = false; onColor(null) })
                val options = state.overview?.colorBreakdown?.takeIf { it.isNotEmpty() }
                    ?: HighlightColors.all.map { io.github.ottershelf.feature.notes.model.ColorCount(it.second, 0) }
                options.forEach { option ->
                    DropdownMenuItem(
                        leadingIcon = { Box(Modifier.size(14.dp).background(highlightColor(option.color), CircleShape)) },
                        text = { MenuLine(colorLabel(option.color), option.count.takeIf { it > 0 }?.toString()) },
                        onClick = { colorOpen = false; onColor(option.color) },
                    )
                }
            }
        }
        FilterChipButton(stringResource(R.string.notes_filter_notes), icon = "NotebookPen", active = filter.hasNote, onClick = onHasNote)
        FilterChipButton(
            stringResource(R.string.notes_filter_liked),
            icon = "Heart",
            active = filter.liked,
            onClick = onLiked,
            iconVector = if (filter.liked) HeartFilled else null,
        )
    }
}

@Composable
private fun MenuLine(label: String, count: String?) {
    val colors = OttershelfTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false), color = colors.foreground)
        count?.let {
            Spacer(Modifier.width(10.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = colors.mutedForeground)
        }
    }
}

/** A colour's name (Yellow...) for the web's ten, else its hex. */
@Composable
internal fun colorLabel(hex: String): String = when (HighlightColors.nameOf(hex)) {
    "yellow" -> stringResource(R.string.notes_colour_yellow)
    "green" -> stringResource(R.string.notes_colour_green)
    "blue" -> stringResource(R.string.notes_colour_blue)
    "pink" -> stringResource(R.string.notes_colour_pink)
    "orange" -> stringResource(R.string.notes_colour_orange)
    "red" -> stringResource(R.string.notes_colour_red)
    "olive" -> stringResource(R.string.notes_colour_olive)
    "cyan" -> stringResource(R.string.notes_colour_cyan)
    "purple" -> stringResource(R.string.notes_colour_purple)
    "gray" -> stringResource(R.string.notes_colour_gray)
    else -> hex.uppercase()
}

/** The random note: the card, and Another. */
@Composable
private fun RandomSheet(state: NotesUiState, coverOf: (Long) -> Any?, actions: HighlightActions, onShuffle: () -> Unit) {
    val colors = OttershelfTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            LucideIcon("Dices", contentDescription = null, tint = colors.primary, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.notes_random_title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = colors.foreground)
        }
        val note = state.random
        when {
            state.randomLoading && note == null -> LoadingState(Modifier.fillMaxWidth().height(160.dp))
            state.randomFailed -> Text(stringResource(R.string.notes_random_failed), style = MaterialTheme.typography.bodyMedium, color = colors.mutedForeground, modifier = Modifier.padding(vertical = 24.dp))
            note == null -> Text(stringResource(R.string.notes_none_match), style = MaterialTheme.typography.bodyMedium, color = colors.mutedForeground, modifier = Modifier.padding(vertical = 24.dp))
            else -> FeedNoteCard(note, coverOf(note.bookId), note.id in state.liked, actions, textLines = 16, photo = note.id in state.photos)
        }
        AccentButton(
            stringResource(R.string.notes_random_another),
            onClick = onShuffle,
            icon = "Shuffle",
            enabled = !state.randomLoading,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        )
    }
}
