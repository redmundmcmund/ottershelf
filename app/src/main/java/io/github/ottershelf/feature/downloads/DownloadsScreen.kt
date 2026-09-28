package io.github.ottershelf.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ottershelf.R
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.feature.library.BookGridFrame
import io.github.ottershelf.feature.library.BookQuickViewModel
import io.github.ottershelf.feature.library.BookQuickViewSheet
import io.github.ottershelf.feature.library.BookRow
import io.github.ottershelf.feature.library.GridMetrics
import io.github.ottershelf.feature.library.ListView
import io.github.ottershelf.feature.library.ViewMenuButton
import io.github.ottershelf.feature.library.rememberGridCells
import io.github.ottershelf.ui.components.BOOK_GRID_SPACING
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.BookGridItem
import io.github.ottershelf.ui.components.CoverProgressBar
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.FullScreenLoading
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.TopBarActions
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme
import kotlin.math.roundToInt

/**
 * The drawer's Downloaded (Nexus BookGridFragment on the Downloaded source), a root list under the
 * shell's toolbar: the same book grid as every other list (grid or list, pinch for the cover size,
 * the toolbar's view menu), of the books on this device. A tap opens the book's page; a long press
 * the quick view (feature.library: Read, straight into the reader and offline; the book page;
 * Remove download, asked first; the status). Downloads still on their way come first, with their
 * progress. Needs no connection. [Route.Downloads.query] is the toolbar's search.
 */
@Composable
fun DownloadsScreen(route: Route.Downloads, navigator: AppNavigator, contentPadding: PaddingValues) {
    val viewModel = appViewModel { DownloadsViewModel(it, route.query) }
    val quickView = appViewModel { BookQuickViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val metrics = remember { GridMetrics() }
    // Positions and statuses change while a book is open on top: read the list again on return.
    LifecycleStartEffect(viewModel) {
        viewModel.refresh()
        onStopOrDispose {}
    }
    TopBarActions {
        if (!state.loading) ViewMenuButton(state.view, metrics, onChange = viewModel::setView)
    }
    DownloadsContent(
        state = state,
        contentPadding = contentPadding,
        metrics = metrics,
        actions = DownloadsActions(
            onOpen = { navigator.navigate(Route.BookDetail(it.card.id)) },
            onQuickView = { quickView.open(it.card, it.cover) },
            onCancel = { viewModel.cancel(it.bookId) },
            onRefresh = viewModel::pullToRefresh,
            onViewChange = viewModel::setView,
        ),
    )
    BookQuickViewSheet(quickView, navigator)
}

class DownloadsActions(
    val onOpen: (DownloadedItem) -> Unit = {},
    val onQuickView: (DownloadedItem) -> Unit = {},
    val onCancel: (ActiveDownload) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onViewChange: (ListView) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsContent(
    state: DownloadsUiState,
    contentPadding: PaddingValues = PaddingValues(),
    actions: DownloadsActions = DownloadsActions(),
    gridState: LazyGridState = rememberLazyGridState(),
    metrics: GridMetrics? = null,
) {
    var stopping by remember { mutableStateOf<ActiveDownload?>(null) }

    // Pull to refresh reads the list again, as the Nexus grid did on Downloaded. The grid is always
    // there (empty or not) so the pull reaches the box; the loading and empty states sit over it.
    val colors = OttershelfTheme.colors
    val direction = LocalLayoutDirection.current
    val pullState = rememberPullToRefreshState()
    val padding = PaddingValues(
        start = contentPadding.calculateStartPadding(direction),
        end = contentPadding.calculateEndPadding(direction),
        bottom = contentPadding.calculateBottomPadding(),
    )
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = actions.onRefresh,
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
        when {
            state.loading -> FullScreenLoading(Modifier.padding(padding))
            state.isEmpty -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    message = stringResource(if (state.query.isNullOrBlank()) R.string.downloads_empty else R.string.downloads_no_match),
                    icon = "HardDriveDownload",
                )
            }
        }
        val horizontal = padding.calculateStartPadding(direction) + padding.calculateEndPadding(direction) + BOOK_GRID_SPACING * 2
        BookGridFrame(state.view, actions.onViewChange, gridState, horizontal, metrics = metrics) { layout, gridModifier ->
            LazyVerticalGrid(
                columns = rememberGridCells(layout),
                state = gridState,
                modifier = Modifier.fillMaxSize().then(gridModifier),
                contentPadding = padding.plus(BOOK_GRID_SPACING),
            ) {
                items(state.active, key = { "active:${it.bookId}" }, contentType = { if (layout.list) "active-row" else "active" }) { item ->
                    if (layout.list) {
                        DownloadingRow(item, onClick = { stopping = item }, modifier = Modifier.animateItem())
                    } else {
                        DownloadingGridItem(item, onClick = { stopping = item }, coverWidth = layout.coverWidth, modifier = Modifier.animateItem())
                    }
                }
                items(state.books, key = { it.card.id }, contentType = { if (layout.list) "row" else "cover" }) { item ->
                    val status = ReadStatus.of(state.statusOverrides[item.card.id] ?: item.card.readStatus?.status)
                    if (layout.list) {
                        BookRow(
                            book = item.card,
                            cover = item.cover,
                            onClick = { actions.onOpen(item) },
                            status = status,
                            onLongClick = { actions.onQuickView(item) },
                            modifier = Modifier.animateItem(),
                        )
                    } else {
                        BookGridItem(
                            book = item.card,
                            cover = item.cover,
                            onClick = { actions.onOpen(item) },
                            status = status,
                            onLongClick = { actions.onQuickView(item) },
                            coverWidth = layout.coverWidth,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    stopping?.let { item ->
        AlertDialog(
            onDismissRequest = { stopping = null },
            containerColor = OttershelfTheme.colors.card,
            text = { Text(stringResource(R.string.downloads_stop_message)) },
            confirmButton = {
                TextButton(onClick = { stopping = null; actions.onCancel(item) }) {
                    Text(stringResource(R.string.downloads_stop), color = OttershelfTheme.colors.destructive)
                }
            },
            dismissButton = { TextButton(onClick = { stopping = null }) { Text(stringResource(R.string.downloads_keep_going)) } },
        )
    }
}

/**
 * A download on its way, laid out like [BookGridItem] (6dp padding, cover, title, a dim second
 * line): the cover dimmed with the progress over it, and "Downloading 45%" (or "Waiting…") under
 * the title. A tap offers to stop it.
 */
@Composable
private fun DownloadingGridItem(item: ActiveDownload, onClick: () -> Unit, coverWidth: Dp?, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val title = item.title ?: stringResource(R.string.components_untitled)
    Column(
        modifier
            .clip(RoundedCornerShape(OttershelfTheme.radii.md.coerceAtMost(8.dp)))
            .clickable(interactionSource = null, indication = ripple(color = colors.primary), role = Role.Button, onClick = onClick)
            .padding(6.dp),
    ) {
        DownloadingCover(item, Modifier.fillMaxWidth(), coverWidth, indicator = 44.dp)
        Spacer(Modifier.height(7.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(1.dp))
        DownloadingLine(item)
    }
}

/** A download on its way as a row of the list view, laid out like feature.library's BookRow. */
@Composable
private fun DownloadingRow(item: ActiveDownload, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.lg)
    Row(
        modifier
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.border, shape)
            .clickable(interactionSource = null, indication = ripple(color = colors.primary), role = Role.Button, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DownloadingCover(item, Modifier.width(56.dp), 56.dp, indicator = 28.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title ?: stringResource(R.string.components_untitled),
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 19.sp),
                color = colors.foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.authors.isNotEmpty()) {
                Text(
                    text = item.authors.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = colors.mutedForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            DownloadingLine(item)
        }
    }
}

/** The cover dimmed, with the download's progress over it and along its foot. */
@Composable
private fun DownloadingCover(item: ActiveDownload, modifier: Modifier, coverWidth: Dp?, indicator: Dp) {
    val colors = OttershelfTheme.colors
    val fraction = item.fraction
    BookCover(
        model = item.cover,
        title = item.title,
        authors = item.authors.joinToString(", "),
        seed = item.title ?: item.bookId.toString(),
        modifier = modifier,
        requestWidth = coverWidth,
    ) {
        Box(Modifier.matchParentSize().background(colors.overlayPill), contentAlignment = Alignment.Center) {
            val size = Modifier.size(indicator)
            val track = Color.White.copy(alpha = 0.25f)
            val stroke = if (indicator < 36.dp) 3.dp else 4.dp
            when {
                fraction != null && !item.waiting ->
                    CircularProgressIndicator(progress = { fraction }, modifier = size, color = colors.primary, trackColor = track, strokeWidth = stroke)
                LocalInspectionMode.current ->
                    CircularProgressIndicator(progress = { 0.3f }, modifier = size, color = colors.primary, trackColor = track, strokeWidth = stroke)
                else -> CircularProgressIndicator(modifier = size, color = colors.primary, trackColor = track, strokeWidth = stroke)
            }
        }
        CoverProgressBar(progress = fraction ?: 0f, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** "Downloading 45%", "Downloading…" or "Waiting…", in the accent. */
@Composable
private fun DownloadingLine(item: ActiveDownload) {
    val fraction = item.fraction
    Text(
        text = when {
            item.waiting -> stringResource(R.string.downloads_waiting)
            fraction != null -> stringResource(R.string.downloads_downloading_percent, (fraction * 100).roundToInt())
            else -> stringResource(R.string.downloads_downloading)
        },
        style = MaterialTheme.typography.bodySmall,
        color = OttershelfTheme.colors.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** [this] with [all] added on every side (the grid's own spacing inside the shell's padding). */
@Composable
private fun PaddingValues.plus(all: Dp): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction) + all,
        top = calculateTopPadding() + all,
        end = calculateEndPadding(direction) + all,
        bottom = calculateBottomPadding() + all,
    )
}
