package io.github.ottershelf.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import io.github.ottershelf.R
import io.github.ottershelf.core.model.AuthorSummary
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.model.SeriesSummary
import io.github.ottershelf.ui.components.BOOK_GRID_SPACING
import io.github.ottershelf.ui.components.BookGridItem
import io.github.ottershelf.ui.components.DetailTopBar
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Chrome
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.TopBarActions
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme

/** Nexus AuthorsFragment.minCellDp and SeriesFragment.minCellDp. */
internal val AUTHOR_MIN_CELL: Dp = 104.dp
internal val SERIES_MIN_CELL: Dp = 170.dp

/** Nexus: books page in 4 rows ahead of the end, authors and series 3. */
private const val BOOK_PREFETCH_ROWS = 4
private const val LIST_PREFETCH_ROWS = 3

/**
 * One source's books (Nexus BookGridFragment). Picked from the drawer (All books, a library, scope
 * or collection: `route.chrome == Chrome.Root`) the shell draws the toolbar and this applies
 * [contentPadding]. An author's or a series' books (`author:` / `series:`) are pushed on top
 * (Nexus BooksActivity: title, subtitle Author or Series, Back) with their own top bar.
 * [Route.BookList.query] is the toolbar's search, when set.
 */
@Composable
fun BookListScreen(route: Route.BookList, navigator: AppNavigator, contentPadding: PaddingValues) {
    val viewModel = appViewModel { BookListViewModel(it, route.sourceKey, route.title, route.query) }
    val quickView = appViewModel { BookQuickViewModel(it) }
    // Books edited on this phone (feature.bookedit) come edited in the pages (BookListEdits).
    val state by viewModel.pager.state.collectAsStateWithLifecycle()
    val overrides by viewModel.statusOverrides.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val view by viewModel.view.collectAsStateWithLifecycle()
    // Saved with the entry, so coming back from a book lands where the grid was.
    val gridState = rememberLazyGridState()
    val metrics = remember { GridMetrics() }
    val snackbar = remember { SnackbarHostState() }
    RefreshFailureSnackbars(viewModel.pager.refreshFailures, snackbar)
    LaunchedEffect(viewModel, gridState) {
        viewModel.resets.collect { gridState.scrollToItem(0) }
    }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val current = sort
    val changed = current != null && current != viewModel.kind.default
    val actions: @Composable RowScope.() -> Unit = {
        view?.let { ViewMenuButton(it, metrics, onChange = viewModel::setView) }
        SortButton(active = changed, onClick = { sheetOpen = true })
    }
    if (route.chrome == Chrome.Root) TopBarActions(actions)

    val content: @Composable (PaddingValues) -> Unit = { padding ->
        SortableList(sort = current, kind = viewModel.kind, padding = padding, onOpenSort = { sheetOpen = true }, onSort = viewModel::setSort) { gridPadding ->
            BookGridContent(
                // A status changed on a book page since the list loaded hides the book at once
                // when the filters leave it out (the server would on the next load).
                state = if (current?.filtered == true) state.shownBy(current) { overrides[it.id] ?: it.readStatus?.status } else state,
                query = route.query,
                coverOf = viewModel::cover,
                statusOf = { book -> ReadStatus.of(overrides[book.id] ?: book.readStatus?.status) },
                onOpen = { book ->
                    viewModel.opening(book)
                    navigator.navigate(Route.BookDetail(book.id))
                },
                onQuickView = { book -> quickView.open(book, viewModel.cover(book)) },
                onLoadMore = viewModel.pager::loadMore,
                onRefresh = viewModel.pager::refresh,
                onRetry = viewModel.pager::retry,
                contentPadding = gridPadding,
                gridState = gridState,
                snackbar = snackbar,
                filters = current,
                onClearFilters = { current?.let { viewModel.setSort(it.withoutFilters()) } },
                view = view ?: ListView(),
                onViewChange = viewModel::setView,
                metrics = metrics,
            )
        }
    }
    if (route.chrome == Chrome.Root) {
        content(contentPadding)
    } else {
        val subtitle = stringResource(
            if (route.sourceKey.startsWith("author:")) R.string.library_subtitle_author else R.string.library_subtitle_series,
        )
        Scaffold(topBar = { DetailTopBar(title = route.title, subtitle = subtitle, onBack = { navigator.back() }, actions = actions) }) { padding ->
            content(padding)
        }
    }
    if (sheetOpen && current != null) {
        SortSheet(sort = current, kind = viewModel.kind, onChange = viewModel::setSort, onDismiss = { sheetOpen = false })
    }
    BookQuickViewSheet(quickView, navigator)
}

/**
 * A book grid under its sort chips: while [sort] isn't [kind]'s default the chip row sits under the
 * toolbar (taking [padding]'s top) and [grid] gets the rest of the padding; otherwise the grid
 * gets all of it.
 */
@Composable
fun SortableList(
    sort: ListSort?,
    kind: ListKind,
    padding: PaddingValues,
    onOpenSort: () -> Unit,
    onSort: (ListSort) -> Unit,
    grid: @Composable (PaddingValues) -> Unit,
) {
    val direction = LocalLayoutDirection.current
    if (sort == null || sort == kind.default) {
        grid(padding)
        return
    }
    val start = padding.calculateStartPadding(direction)
    val end = padding.calculateEndPadding(direction)
    Column(Modifier.fillMaxSize()) {
        SortChips(
            sort = sort,
            kind = kind,
            onOpen = onOpenSort,
            onClear = { onSort(kind.default) },
            modifier = Modifier.padding(start = start, top = padding.calculateTopPadding(), end = end),
        )
        grid(PaddingValues(start = start, end = end, bottom = padding.calculateBottomPadding()))
    }
}

/** [PagedState] without the books [ListSort] leaves out by their current status ([statusOf]). */
internal fun PagedState<BookCard>.shownBy(sort: ListSort, statusOf: (BookCard) -> String?): PagedState<BookCard> {
    val shown = items.filter { sort.shows(statusOf(it)) }
    if (shown.size == items.size) return this
    return copy(items = shown, total = if (total >= 0) total - (items.size - shown.size) else total)
}

/**
 * Every author (Nexus AuthorsFragment), a root list under the shell's toolbar: applies
 * [contentPadding]. Picking one pushes `Route.BookList("author:<id>", name)`.
 */
@Composable
fun AuthorsScreen(route: Route.Authors, navigator: AppNavigator, contentPadding: PaddingValues) {
    val viewModel = appViewModel { AuthorsViewModel(it, route.query) }
    val state by viewModel.pager.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val snackbar = remember { SnackbarHostState() }
    RefreshFailureSnackbars(viewModel.pager.refreshFailures, snackbar)
    AuthorsContent(
        state = state,
        query = route.query,
        portraitOf = viewModel::portrait,
        onOpen = { navigator.navigate(Route.BookList("author:${it.id}", it.name)) },
        onLoadMore = viewModel.pager::loadMore,
        onRefresh = viewModel.pager::refresh,
        onRetry = viewModel.pager::retry,
        contentPadding = contentPadding,
        gridState = gridState,
        snackbar = snackbar,
    )
}

/**
 * Every series (Nexus SeriesFragment), a root list under the shell's toolbar: applies
 * [contentPadding]. Picking one pushes `Route.BookList("series:<id>", name)`.
 */
@Composable
fun SeriesScreen(route: Route.Series, navigator: AppNavigator, contentPadding: PaddingValues) {
    val viewModel = appViewModel { SeriesViewModel(it, route.query) }
    val state by viewModel.pager.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val snackbar = remember { SnackbarHostState() }
    RefreshFailureSnackbars(viewModel.pager.refreshFailures, snackbar)
    SeriesContent(
        state = state,
        query = route.query,
        coverOf = viewModel::cover,
        onOpen = { navigator.navigate(Route.BookList("series:${it.id}", it.name)) },
        onLoadMore = viewModel.pager::loadMore,
        onRefresh = viewModel.pager::refresh,
        onRetry = viewModel.pager::retry,
        contentPadding = contentPadding,
        gridState = gridState,
        snackbar = snackbar,
    )
}

// --- stateless content (screenshot-tested) ---------------------------------------------------

@Composable
fun BookGridContent(
    state: PagedState<BookCard>,
    coverOf: (BookCard) -> Any?,
    modifier: Modifier = Modifier,
    query: String? = null,
    statusOf: (BookCard) -> ReadStatus? = { ReadStatus.of(it.readStatus?.status) },
    onOpen: (BookCard) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    gridState: LazyGridState = rememberLazyGridState(),
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    filters: ListSort? = null,
    onClearFilters: () -> Unit = {},
    // The quick view on a long press (grid or list); none without it.
    onQuickView: ((BookCard) -> Unit)? = null,
    view: ListView = ListView(),
    onViewChange: (ListView) -> Unit = {},
    metrics: GridMetrics? = null,
) {
    val filtered = filters?.filtered == true
    val direction = LocalLayoutDirection.current
    val horizontal = contentPadding.calculateStartPadding(direction) + contentPadding.calculateEndPadding(direction) + BOOK_GRID_SPACING * 2
    BookGridFrame(view, onViewChange, gridState, horizontal, modifier, metrics) { layout, gridModifier ->
        PagedGrid(
            state = state,
            columns = rememberGridCells(layout),
            gridPadding = BOOK_GRID_SPACING,
            prefetchRows = if (layout.list) BOOK_PREFETCH_ROWS * 2 else BOOK_PREFETCH_ROWS,
            emptyText = stringResource(
                when {
                    filters?.hideRead == true && filters.hideUnread && !filters.readingOnly -> R.string.library_no_books_both_hidden
                    filtered -> R.string.library_no_books_filtered
                    query.isNullOrBlank() -> R.string.library_empty_books
                    else -> R.string.library_no_books_match
                },
            ),
            emptyIcon = if (filtered) "EyeOff" else "Library",
            emptyAction = if (filtered) ({ SecondaryButton(stringResource(R.string.library_clear_filters), onClick = onClearFilters, icon = "X") }) else null,
            errorText = stringResource(R.string.library_books_failed),
            key = { it.id },
            contentType = { if (layout.list) "row" else "cover" },
            onLoadMore = onLoadMore,
            onRefresh = onRefresh,
            onRetry = onRetry,
            contentPadding = contentPadding,
            gridState = gridState,
            snackbar = snackbar,
            gridModifier = gridModifier,
        ) { book ->
            val longPress = onQuickView?.let { { it(book) } }
            // The same model instance on every pass: a new URL string each time (the cells take
            // it as Any?, compared by identity) would keep every cell from skipping whenever the
            // list's state changes (each page arriving). Keyed on the card alone: an edited book
            // is a new card (a new updatedAt, so a new URL).
            val cover = remember(book) { coverOf(book) }
            if (layout.list) {
                BookRow(book = book, cover = cover, onClick = { onOpen(book) }, status = statusOf(book), onLongClick = longPress)
            } else {
                BookGridItem(
                    book = book,
                    cover = cover,
                    onClick = { onOpen(book) },
                    status = statusOf(book),
                    onLongClick = longPress,
                    coverWidth = layout.coverWidth,
                )
            }
        }
    }
}

@Composable
fun AuthorsContent(
    state: PagedState<AuthorSummary>,
    portraitOf: (AuthorSummary) -> Any?,
    modifier: Modifier = Modifier,
    query: String? = null,
    onOpen: (AuthorSummary) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    gridState: LazyGridState = rememberLazyGridState(),
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    PagedGrid(
        state = state,
        columns = remember { GridCells.Adaptive(AUTHOR_MIN_CELL) },
        gridPadding = BOOK_GRID_SPACING,
        prefetchRows = LIST_PREFETCH_ROWS,
        emptyText = stringResource(if (query.isNullOrBlank()) R.string.library_empty_authors else R.string.library_no_authors_match),
        emptyIcon = "Users",
        errorText = stringResource(R.string.library_list_failed),
        key = { it.id },
        onLoadMore = onLoadMore,
        onRefresh = onRefresh,
        onRetry = onRetry,
        contentPadding = contentPadding,
        gridState = gridState,
        snackbar = snackbar,
        modifier = modifier,
    ) { author ->
        // One model instance per author, so the tile skips when a page arrives (see BookGridContent).
        val portrait = remember(author) { portraitOf(author) }
        AuthorTile(author = author, portrait = portrait, onClick = { onOpen(author) })
    }
}

@Composable
fun SeriesContent(
    state: PagedState<SeriesSummary>,
    coverOf: (Long) -> Any?,
    modifier: Modifier = Modifier,
    query: String? = null,
    onOpen: (SeriesSummary) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    gridState: LazyGridState = rememberLazyGridState(),
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    PagedGrid(
        state = state,
        columns = remember { GridCells.Adaptive(SERIES_MIN_CELL) },
        // Cards carry their own 5dp margins; less padding round the grid keeps the gutters even.
        gridPadding = 3.dp,
        prefetchRows = LIST_PREFETCH_ROWS,
        emptyText = stringResource(if (query.isNullOrBlank()) R.string.library_empty_series else R.string.library_no_series_match),
        emptyIcon = "BookCopy",
        errorText = stringResource(R.string.library_list_failed),
        key = { it.id },
        onLoadMore = onLoadMore,
        onRefresh = onRefresh,
        onRetry = onRetry,
        contentPadding = contentPadding,
        gridState = gridState,
        snackbar = snackbar,
        modifier = modifier,
    ) { series ->
        SeriesCard(series = series, coverOf = coverOf, onClick = { onOpen(series) })
    }
}

/**
 * The grid all three lists share (Nexus fragment_book_grid.xml): pull to refresh round a grid of
 * [columns] (for Authors and Series, as many as the width takes; for books, the list's view),
 * paging in [prefetchRows] rows before the end, the message (empty, or failed with Retry)
 * centred, and "Couldn't refresh" as a snackbar. [gridModifier] goes on the lazy grid itself (the
 * book grids' pinch).
 *
 * [contentPadding] is the frame's (toolbar, system bars): the refresh indicator sits under the
 * top bar, and the grid scrolls behind the navigation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> PagedGrid(
    state: PagedState<T>,
    columns: GridCells,
    gridPadding: Dp,
    prefetchRows: Int,
    emptyText: String,
    emptyIcon: String,
    emptyAction: (@Composable () -> Unit)? = null,
    errorText: String,
    key: (T) -> Any,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    gridState: LazyGridState,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    gridModifier: Modifier = Modifier,
    contentType: (T) -> Any? = { null },
    item: @Composable LazyGridItemScope.(T) -> Unit,
) {
    val direction = LocalLayoutDirection.current
    val colors = OttershelfTheme.colors
    val loadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(gridState, prefetchRows) {
        // Re-checked whenever the count changes too, so a page that still leaves the end in view
        // asks for the next one.
        snapshotFlow {
            val info = gridState.layoutInfo
            info.nearEnd(prefetchRows) to info.totalItemsCount
        }.distinctUntilChanged().collect { (near, _) -> if (near) loadMore() }
    }
    // And on every forward scroll near the end (the Nexus onScrolled with dy > 0): a page that
    // failed is asked for again as the user keeps scrolling, rather than the grid just stopping.
    val forwardScroll = remember(gridState, prefetchRows) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y < 0f && gridState.layoutInfo.nearEnd(prefetchRows)) loadMore()
                return Offset.Zero
            }
        }
    }

    val pullState = rememberPullToRefreshState()
    Box(modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        PullToRefreshBox(
            isRefreshing = state.showRefresh,
            onRefresh = onRefresh,
            state = pullState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.showRefresh,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = colors.card,
                    color = colors.primary,
                )
            },
        ) {
            LazyVerticalGrid(
                columns = columns,
                state = gridState,
                modifier = Modifier.fillMaxSize().then(gridModifier).nestedScroll(forwardScroll),
                contentPadding = PaddingValues(
                    start = contentPadding.calculateStartPadding(direction) + gridPadding,
                    top = gridPadding,
                    end = contentPadding.calculateEndPadding(direction) + gridPadding,
                    bottom = contentPadding.calculateBottomPadding() + gridPadding,
                ),
            ) {
                items(state.items, key = key, contentType = contentType) { item(it) }
            }
            val bottom = Modifier.padding(bottom = contentPadding.calculateBottomPadding())
            when {
                state.error != null && state.items.isEmpty() -> ErrorState(
                    onRetry = onRetry,
                    message = errorText,
                    detail = state.error.takeIf { it.isNotBlank() },
                    modifier = bottom.align(Alignment.Center),
                )
                state.empty -> EmptyState(
                    message = emptyText,
                    icon = emptyIcon,
                    action = emptyAction,
                    modifier = bottom.align(Alignment.Center),
                )
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding()),
        )
    }
}

/** The last visible item is within [prefetchRows] rows of the end. */
private fun LazyGridLayoutInfo.nearEnd(prefetchRows: Int): Boolean {
    val last = visibleItemsInfo.lastOrNull()?.index ?: -1
    val columns = maxSpan.coerceAtLeast(1)
    return totalItemsCount > 0 && last >= totalItemsCount - prefetchRows * columns
}

@Composable
private fun RefreshFailureSnackbars(failures: Flow<String>, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    LaunchedEffect(failures) {
        failures.collect { message -> snackbar.showSnackbar(resources.getString(R.string.library_refresh_failed, message)) }
    }
}
