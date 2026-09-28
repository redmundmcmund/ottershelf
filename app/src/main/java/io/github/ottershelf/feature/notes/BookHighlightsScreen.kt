package io.github.ottershelf.feature.notes

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ottershelf.R
import io.github.ottershelf.core.settings.NotesPrefs
import io.github.ottershelf.feature.notes.model.Annotation
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.DetailTopBar
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.components.SectionHeader
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.feature.quotes.QuotePhotoDialog
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.io.File

/** One book's highlights (pushed from the book page's Highlights & notes card). */
@Composable
fun BookHighlightsScreen(route: Route.BookHighlights, navigator: AppNavigator) {
    val appContext = LocalContext.current.applicationContext
    val viewModel = appViewModel { BookHighlightsViewModel(it, route.bookId, route.title, appContext) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var sharingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var photo by remember { mutableStateOf<Pair<Long, File>?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                is NotesMessage.Failed ->
                    snackbar.showSnackbar(resources.getString(R.string.notes_action_failed, message.detail), duration = SnackbarDuration.Long)
                NotesMessage.LikesFull ->
                    snackbar.showSnackbar(resources.getString(R.string.notes_likes_full, NotesPrefs.MAX_ENTRIES), duration = SnackbarDuration.Long)
                is NotesMessage.Export -> runCatching {
                    NotesShare.shareMarkdown(
                        context, message.markdown, message.fileName,
                        subject = resources.getString(R.string.notes_export_subject, state.title),
                        chooserTitle = resources.getString(R.string.notes_export),
                    )
                }.onFailure { snackbar.showSnackbar(resources.getString(R.string.notes_action_failed, it.message ?: "")) }
            }
        }
    }
    val actions = remember(viewModel) {
        HighlightActions(
            onOpenReader = { note ->
                val s = viewModel.state.value
                s.readerRoute(note)?.let(navigator::navigate)
            },
            onLike = viewModel::toggleLike,
            onShareImage = { sharingId = it.id },
            onSaveNote = viewModel::saveNote,
            onColor = viewModel::setColor,
            onStyle = viewModel::setStyle,
            onDelete = viewModel::delete,
            onViewPhoto = { note -> photo = viewModel.photo(note)?.let { note.id to it } },
            onDraftShown = viewModel::draftShown,
        )
    }
    BookHighlightsContent(
        state = state,
        actions = actions,
        snackbar = snackbar,
        onBack = { navigator.back() },
        onExport = viewModel::export,
        onMemorize = { navigator.navigate(Route.Memorize(bookId = route.bookId)) },
        onRefresh = viewModel::refresh,
        onRetry = viewModel::load,
        onLoadMore = viewModel::loadMore,
    )
    sharingId?.let { id ->
        state.items.firstOrNull { it.id == id }?.let { note ->
            QuoteCardDialog(note, state.title, state.author, state.cover, onDismiss = { sharingId = null })
        }
    }
    photo?.let { (id, file) -> QuotePhotoDialog(file, onDismiss = { photo = null }, onRemove = { viewModel.removePhoto(id); photo = null }) }
}

/**
 * The summary card (count, notes, chapters, the colours), then each chapter as a Nexus section
 * (its title and count) over its highlight cards, in reading order. Delete asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookHighlightsContent(
    state: BookHighlightsUiState,
    actions: HighlightActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit = {},
    onExport: () -> Unit = {},
    onMemorize: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    onLoadMore: () -> Unit = {},
) {
    val colors = OttershelfTheme.colors
    val direction = LocalLayoutDirection.current
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    val groups = remember(state.items, state.stats.chapterBreakdown) { NotesLogic.chapterGroups(state.items, state.stats.chapterBreakdown) }
    val confirmDelete = remember(actions) {
        HighlightActions(
            onOpenReader = actions.onOpenReader,
            onLike = actions.onLike,
            onShareImage = actions.onShareImage,
            onSaveNote = actions.onSaveNote,
            onColor = actions.onColor,
            onStyle = actions.onStyle,
            onDelete = { deleting = it.id },
            onViewPhoto = actions.onViewPhoto,
            onDraftShown = actions.onDraftShown,
        )
    }
    Scaffold(
        topBar = {
            DetailTopBar(
                title = stringResource(R.string.notes_highlights_title),
                subtitle = state.title,
                onBack = onBack,
                actions = {
                    if (state.items.isNotEmpty()) {
                        IconButton(onClick = onMemorize) {
                            LucideIcon("Brain", contentDescription = stringResource(R.string.notes_memorize), tint = LocalContentColor.current, size = 22.dp)
                        }
                        IconButton(onClick = onExport, enabled = !state.exporting) {
                            LucideIcon("Share2", contentDescription = stringResource(R.string.notes_export), tint = LocalContentColor.current, size = 22.dp)
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val pullState = rememberPullToRefreshState()
        val listState = rememberLazyListState()
        LaunchedEffect(listState, state.items.size) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .collect { last -> if (last >= listState.layoutInfo.totalItemsCount - 4) onLoadMore() }
        }
        val outer = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())
        when {
            state.loading && state.items.isEmpty() -> LoadingState(outer)
            state.error != null && state.items.isEmpty() ->
                ErrorState(onRetry, outer, message = stringResource(R.string.notes_load_failed), detail = state.error)
            state.items.isEmpty() -> EmptyState(stringResource(R.string.notes_book_empty), outer, icon = "Highlighter")
            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                state = pullState,
                modifier = outer,
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
                        start = padding.calculateStartPadding(direction) + 12.dp,
                        end = padding.calculateEndPadding(direction) + 12.dp,
                        top = 12.dp,
                        bottom = padding.calculateBottomPadding() + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "summary") { BookSummary(state) }
                    groups.forEachIndexed { index, group ->
                        item(key = "chapter-$index-${group.title}") {
                            SectionHeader(
                                title = group.title ?: stringResource(R.string.notes_no_chapter),
                                icon = "TableOfContents",
                                count = group.count,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                        }
                        items(group.items, key = { it.id }) { note ->
                            HighlightCard(
                                note = note,
                                liked = note.id in state.liked,
                                canOpenReader = state.readerRoute(note) != null,
                                saving = note.id in state.saving,
                                actions = confirmDelete,
                                photo = note.id in state.photos,
                                draft = state.drafts[note.id],
                            )
                        }
                    }
                    if (state.loadingMore) item(key = "more") { LoadingState(Modifier.fillMaxWidth().padding(vertical = 12.dp)) }
                }
            }
        }
    }
    deleting?.let { id ->
        val note = state.items.firstOrNull { it.id == id }
        if (note == null) {
            deleting = null
        } else {
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text(stringResource(R.string.notes_delete_title)) },
                text = { Text(stringResource(R.string.notes_delete_text)) },
                confirmButton = {
                    TextButton(onClick = { deleting = null; actions.onDelete?.invoke(note) }) {
                        Text(stringResource(R.string.notes_delete), color = colors.destructive)
                    }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.notes_cancel)) } },
                containerColor = colors.card,
            )
        }
    }
}

@Composable
private fun BookSummary(state: BookHighlightsUiState) {
    val colors = OttershelfTheme.colors
    val stats = state.stats
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(colors.primary.copy(alpha = 0.15f), RoundedCornerShape(OttershelfTheme.radii.lg)), contentAlignment = Alignment.Center) {
                LucideIcon("Highlighter", contentDescription = null, tint = colors.primary, size = 22.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pluralStringResource(R.plurals.notes_highlights_count, stats.totalHighlights, stats.totalHighlights),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                )
                Text(
                    listOf(
                        pluralStringResource(R.plurals.notes_notes_count, stats.highlightsWithNotes, stats.highlightsWithNotes),
                        pluralStringResource(R.plurals.notes_chapters_count, stats.chaptersWithHighlights, stats.chaptersWithHighlights),
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedForeground,
                )
            }
        }
        ColorBreakdownBar(stats.colorBreakdown, Modifier.padding(top = 14.dp))
        ColorLegend(stats.colorBreakdown, Modifier.padding(top = 8.dp), max = 10)
    }
}
