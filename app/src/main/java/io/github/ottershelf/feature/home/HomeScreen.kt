package io.github.ottershelf.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import io.github.ottershelf.R
import io.github.ottershelf.core.format.BookFormats
import io.github.ottershelf.core.model.ReadingStreak
import io.github.ottershelf.feature.home.model.WidgetType
import io.github.ottershelf.feature.home.model.cardRows
import io.github.ottershelf.feature.home.model.isShelfKey
import io.github.ottershelf.feature.home.model.isWidgetKey
import io.github.ottershelf.feature.home.model.moved
import io.github.ottershelf.feature.home.model.rowWrapsContent
import io.github.ottershelf.feature.library.BookQuickViewModel
import io.github.ottershelf.feature.library.BookQuickViewSheet
import io.github.ottershelf.feature.scan.ScanForTimerAction
import io.github.ottershelf.feature.timer.RunningTimerViewModel
import io.github.ottershelf.feature.timer.TimerBars
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.CardRow
import io.github.ottershelf.ui.components.CardTitle
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.PillProgressBar
import io.github.ottershelf.ui.components.SectionHeader
import io.github.ottershelf.ui.components.SkeletonBox
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.ReaderRouter
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.TopBarActions
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme
import kotlin.math.roundToInt

/** From this width (dp), the widgets sit in two columns (landscape; the Nexus `WIDE_DP`). */
private const val WIDE_DP = 560
/** Below this width (dp, the web's `sm` breakpoint) a shelf shows two rows at most. */
private const val COMPACT_DP = 640
/** Every widget card's height (the web's `h-55` cards, the Nexus Currently Reading card). */
private val WIDGET_HEIGHT = 244.dp
/** A Currently Reading row (the cover and its padding), for the placeholder while it loads. */
private val READING_ROW_HEIGHT = 68.dp
/** A card's row while arranging: its title and the drag handle. */
private val ARRANGE_ROW_HEIGHT = 48.dp
private const val DAYS = 7

/**
 * The Dashboard (Nexus DashboardFragment, the web's DashboardView), the start screen and a root
 * list: the shell draws the toolbar, with Customise. The shown widgets and shelves in the one order
 * the user arranged (widgets one per row, two short ones side by side, in portrait; two columns in
 * landscape; a shelf on a row of its own); pull to refresh. A long press on a card's title
 * arranges the cards (drag them, then Done in the toolbar). Each load is reported to
 * [AppNavigator.dashboardLoaded], so the shell can fall back to Downloaded when the server is down.
 */
@Composable
fun HomeScreen(navigator: AppNavigator, contentPadding: PaddingValues) {
    val viewModel = appViewModel { HomeViewModel(it) }
    // A long press on a shelf's cover (feature.library's quick view).
    val quickView = appViewModel { BookQuickViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LifecycleStartEffect(viewModel) {
        viewModel.setVisible(true)
        onStopOrDispose { viewModel.setVisible(false) }
    }
    LaunchedEffect(viewModel, navigator) {
        viewModel.loads.collect { navigator.dashboardLoaded(it) }
    }
    val snackbar = remember { SnackbarHostState() }
    val orderFailed = stringResource(R.string.home_arrange_failed)
    LaunchedEffect(viewModel) {
        viewModel.orderFailures.collect { snackbar.showSnackbar(orderFailed) }
    }
    BackHandler(enabled = state.editing, onBack = viewModel::finishEditing)
    TopBarActions {
        if (state.editing) {
            TextButton(onClick = viewModel::finishEditing) {
                Text(
                    stringResource(R.string.home_arrange_done),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                    color = OttershelfTheme.colors.primary,
                )
            }
        } else {
            IconButton(onClick = viewModel::openCustomise) {
                LucideIcon("Settings2", contentDescription = stringResource(R.string.home_customise), tint = LocalContentColor.current, size = 22.dp)
            }
        }
    }
    // The reading timer (feature.timer): a bar at the top while one is on (or a stopped session
    // waits to be saved), a button on each reading row.
    val timer = appViewModel { RunningTimerViewModel(it) }
    val runningTimer by timer.active.collectAsStateWithLifecycle()
    val unsavedTimer by timer.unsaved.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        contentPadding = contentPadding,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retry,
        onOpenBook = {
            viewModel.opening(it)
            navigator.navigate(Route.BookDetail(it))
        },
        onRead = { bookId, fileId, format, title ->
            // At once, unless a CBR/CB7's downloaded CBZ has to be looked up on the disk first.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val (id, fileFormat) = viewModel.fileToRead(bookId, fileId, format)
                ReaderRouter.route(bookId, id, fileFormat, title)?.let(navigator::navigate)
            }
        },
        timerBookId = runningTimer?.bookId,
        timerBar = if (runningTimer == null && unsavedTimer == null) {
            null
        } else {
            {
                val now by timer.now.collectAsStateWithLifecycle()
                TimerBars(
                    running = runningTimer,
                    unsaved = unsavedTimer,
                    now = { now },
                    onTimer = { navigator.navigate(Route.Timer(it)) },
                    onUnsaved = { navigator.navigate(Route.TimerResult(it.bookId, it.sessionId)) },
                )
            }
        },
        onTimer = { navigator.navigate(Route.Timer(it)) },
        onScanForTimer = { navigator.navigate(Route.Scan(forTimer = true)) },
        widgetCover = viewModel::cover,
        onQueue = viewModel::queue,
        onEditGoal = { navigator.navigate(Route.ReadingGoals) },
        onNavigate = { navigator.navigate(it) },
        onCustomise = viewModel::openCustomise,
        onStartArranging = viewModel::startEditing,
        onArrange = viewModel::arrange,
        snackbar = snackbar,
        onQuickView = { quickView.open(it.book, it.cover) },
    )
    BookQuickViewSheet(quickView, navigator)
    val customise by viewModel.customise.collectAsStateWithLifecycle()
    customise?.let {
        CustomiseSheet(
            it,
            onEdit = viewModel::editCustomise,
            onSave = viewModel::saveCustomise,
            onClose = viewModel::closeCustomise,
            onRetryLibraries = viewModel::retryLibraries,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeUiState,
    contentPadding: PaddingValues = PaddingValues(),
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    onOpenBook: (Long) -> Unit = {},
    onRead: (bookId: Long, fileId: Long, format: String?, title: String) -> Unit = { _, _, _, _ -> },
    // The bookId of the timer that is on, if any (its row button is filled).
    timerBookId: Long? = null,
    // The running timer bar, shown above the cards.
    timerBar: (@Composable () -> Unit)? = null,
    onTimer: (bookId: Long) -> Unit = {},
    // "Scan a book" on the Currently Reading card: the scanner picks the book to time (feature.scan).
    onScanForTimer: (() -> Unit)? = null,
    // A widget's book cover (highlight, gem, long wait).
    widgetCover: (bookId: Long, hasCover: Boolean) -> Any? = { _, _ -> null },
    onQueue: (bookId: Long) -> Unit = {},
    onEditGoal: () -> Unit = {},
    onNavigate: (Route) -> Unit = {},
    onCustomise: () -> Unit = {},
    // A long press on a card's title (then [HomeUiState.editing]).
    onStartArranging: () -> Unit = {},
    // The cards on show in the order the user dragged them into (their keys).
    onArrange: (List<String>) -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    // For screenshots: the Reading Rhythm day picked to start with.
    rhythmDay: Int? = null,
    // A long press on a shelf's cover: the quick view.
    onQuickView: ((ShelfItem) -> Unit)? = null,
) {
    val direction = LocalLayoutDirection.current
    val haptics = LocalHapticFeedback.current
    val listDrag = remember { ReadingListDrag() }
    val allBooks = stringResource(R.string.nav_all_books)
    // Remembered, and each card given only its part of the state, so an update to one part
    // doesn't recompose every card.
    val actions = remember(onRetry, onOpenBook, onRead, onEditGoal, onNavigate, onQueue, widgetCover, allBooks) {
        WidgetActions(onRetry, onOpenBook, onRead, onEditGoal, onNavigate, onQueue, widgetCover, allBooks)
    }
    val canQueue = remember(state.unqueueable, state.reading) { state::canQueue }
    val cards = remember(state.layout, state.order, state.shelfConfigs, state.shelfBooks, state.failedShelves) { state.cards }
    val listState = rememberLazyListState()
    val pull = rememberPullToRefreshState()

    // Arranging: the cards as dragged so far, until the Dashboard's own order catches up.
    var dragged by remember { mutableStateOf<List<HomeCard>?>(null) }
    val arranging = dragged ?: cards
    val reorder = rememberReorderState(
        listState,
        canMove = { it is String && (isWidgetKey(it) || isShelfKey(it)) },
        onMove = { from, to ->
            val list = dragged ?: cards
            val i = list.indexOfFirst { it.key == from }
            val j = list.indexOfFirst { it.key == to }
            if (i >= 0 && j >= 0) dragged = list.moved(i, j)
        },
        onDrop = { dragged?.let { list -> onArrange(list.map { it.key }) } },
    )
    LaunchedEffect(cards, state.editing) { if (!reorder.isDragging) dragged = null }
    val moveBy: (String, Int) -> Unit = { key, delta ->
        val i = arranging.indexOfFirst { it.key == key }
        val moved = arranging.moved(i, i + delta)
        if (moved !== arranging) onArrange(moved.map { it.key })
    }

    // Switching between the Dashboard and arranging keeps the card the user pressed (or the first one
    // on screen) where it was.
    val anchor = remember { ModeAnchor(state.editing) }
    val arrangeLabel = stringResource(R.string.home_arrange)
    val onLongPress: (String) -> Unit = remember(onStartArranging) {
        { key ->
            anchor.pressed = key
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onStartArranging()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding())
            .pullToRefresh(state.refreshing, pull, enabled = !state.editing, onRefresh = onRefresh),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= WIDE_DP.dp
            val compact = maxWidth < COMPACT_DP.dp
            val hasShelves = state.shelfConfigs.isNotEmpty()
            val entries = remember(cards, arranging, state.editing, wide, timerBar != null, hasShelves) {
                dashboardItems(cards, arranging, state.editing, wide, timerBar != null, hasShelves)
            }
            if (anchor.editing != state.editing) {
                anchor.editing = state.editing
                anchor.target = Snapshot.withoutReadObservation { anchor.find(listState.layoutInfo) }
                anchor.pressed = null
            }
            anchor.items = entries
            LaunchedEffect(state.editing) {
                val (key, offset) = anchor.target ?: return@LaunchedEffect
                anchor.target = null
                val index = entries.indexOfFirst { it.holds(key) }
                if (index < 0) return@LaunchedEffect
                listState.scrollToItem(index)
                if (offset != 0) listState.scrollBy(-offset.toFloat())
            }
            LazyColumn(
                Modifier.fillMaxSize().nestedScroll(listDrag.page),
                state = listState,
                contentPadding = PaddingValues(
                    start = contentPadding.calculateStartPadding(direction) + 12.dp,
                    end = contentPadding.calculateEndPadding(direction) + 12.dp,
                    top = 12.dp,
                    bottom = contentPadding.calculateBottomPadding() + 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(if (state.editing) 8.dp else 12.dp),
            ) {
                items(entries, key = { it.key }, contentType = { it.contentType }) { item ->
                    when (item) {
                        DashboardItem.Timer -> timerBar?.invoke()
                        DashboardItem.ArrangeHint -> ArrangeHint()
                        DashboardItem.NoShelves -> NoShelves(onCustomise)
                        is DashboardItem.Arrange -> {
                            val index = arranging.indexOfFirst { it.key == item.card.key }
                            ArrangeCard(
                                item.card,
                                reorder,
                                canMoveUp = index > 0,
                                canMoveDown = index in 0 until arranging.lastIndex,
                                onMoveBy = moveBy,
                                modifier = reorderable(reorder, item.card.key),
                            )
                        }
                        is DashboardItem.Cards -> {
                            val header: (HomeCard) -> Modifier = { card ->
                                Modifier.arrangeOnLongPress(card.key, arrangeLabel, onLongPress)
                            }
                            val first = item.cards.first()
                            if (first is HomeCard.Shelf) {
                                ShelfCard(first.shelf, compact, onRetry, onOpenBook, headerModifier = header(first), onQuickView = onQuickView)
                            } else {
                                WidgetRow(
                                    item.cards.map { (it as HomeCard.Widget).type },
                                    wide = wide,
                                    header = { type -> header(HomeCard.Widget(type)) },
                                ) { type, wraps, modifier, headerModifier ->
                                    when (type) {
                                        WidgetType.CURRENTLY_READING -> ReadingCard(
                                            state.reading, state.readingFailed, onRetry, onOpenBook, onRead, timerBookId, onTimer,
                                            listDrag.list, wraps = wraps, header = headerModifier, modifier = modifier,
                                            onScanForTimer = onScanForTimer,
                                        )
                                        WidgetType.READING_STREAK ->
                                            StreakCard(state.streak, state.streakFailed, onRetry, fixedHeight = !wraps, header = headerModifier, modifier = modifier)
                                        else -> WidgetCard(
                                            type, state.widgetData[type], type in state.failedWidgets, state.queued, canQueue, actions, modifier, rhythmDay,
                                            headerModifier = headerModifier,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        PullToRefreshDefaults.Indicator(state = pull, isRefreshing = state.refreshing, modifier = Modifier.align(Alignment.TopCenter))
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding()))
    }
}

// --- the list's items ------------------------------------------------------------------------

/** What the Dashboard's list holds, keyed so each keeps its state (a shelf its scroll) as cards move. */
private sealed interface DashboardItem {
    val key: String
    val contentType: String

    data object Timer : DashboardItem {
        override val key = "timer"
        override val contentType = "timer"
    }

    data object ArrangeHint : DashboardItem {
        override val key = "arrange-hint"
        override val contentType = "hint"
    }

    data object NoShelves : DashboardItem {
        override val key = "no-shelves"
        override val contentType = "no-shelves"
    }

    /** A row: a shelf, or one or two widgets. Keyed by its first card, as that card is while arranging. */
    data class Cards(val cards: List<HomeCard>) : DashboardItem {
        override val key: String get() = cards.first().key
        override val contentType: String get() = if (cards.first() is HomeCard.Shelf) "shelf" else "widgets"
    }

    /** A card while arranging: its title and handle. */
    data class Arrange(val card: HomeCard) : DashboardItem {
        override val key: String get() = card.key
        override val contentType = "arrange"
    }

    fun holds(cardKey: String): Boolean = when (this) {
        is Cards -> cards.any { it.key == cardKey }
        is Arrange -> card.key == cardKey
        else -> false
    }

    val firstCard: String? get() = (this as? Cards)?.cards?.first()?.key ?: (this as? Arrange)?.card?.key
}

private fun dashboardItems(
    cards: List<HomeCard>,
    arranging: List<HomeCard>,
    editing: Boolean,
    wide: Boolean,
    timer: Boolean,
    hasShelves: Boolean,
): List<DashboardItem> = buildList {
    if (editing) {
        add(DashboardItem.ArrangeHint)
        arranging.forEach { add(DashboardItem.Arrange(it)) }
        return@buildList
    }
    if (timer) add(DashboardItem.Timer)
    cardRows(cards, twoColumns = wide) { (it as? HomeCard.Widget)?.type }.forEach { add(DashboardItem.Cards(it)) }
    if (hasShelves && cards.none { it is HomeCard.Shelf }) add(DashboardItem.NoShelves)
}

/**
 * Where the list was when the Dashboard switched to arranging or back: the card to keep in place
 * ([pressed], else the first on screen) and how far down the screen it was. Plain fields, read and
 * written while composing the switch.
 */
private class ModeAnchor(var editing: Boolean) {
    var pressed: String? = null
    var items: List<DashboardItem> = emptyList()
    var target: Pair<String, Int>? = null

    fun find(info: LazyListLayoutInfo): Pair<String, Int>? {
        val byKey = items.associateBy { it.key }
        val visible = info.visibleItemsInfo.mapNotNull { v -> byKey[v.key]?.let { it to v.offset } }
        pressed?.let { key -> visible.firstOrNull { it.first.holds(key) }?.let { return key to it.second } }
        val (item, offset) = visible.firstOrNull { it.first.firstCard != null } ?: return null
        return item.firstCard!! to offset
    }
}

/** A long press on a card's title arranges the cards (also offered to accessibility services). */
private fun Modifier.arrangeOnLongPress(key: String, label: String, onLongPress: (String) -> Unit): Modifier =
    fillMaxWidth()
        .pointerInput(key, onLongPress) { detectTapGestures(onLongPress = { onLongPress(key) }) }
        .semantics {
            onLongClick(label) {
                onLongPress(key)
                true
            }
        }

/**
 * A row of one or two widgets: every card the one height, except a Reading Streak or Currently
 * Reading stacked on a row of its own, which is as tall as its content up to that height (the
 * Nexus DashboardFragment.arrange). In two columns the last one on its own keeps its column's width.
 */
@Composable
private fun WidgetRow(
    row: List<WidgetType>,
    wide: Boolean,
    header: (WidgetType) -> Modifier,
    card: @Composable (type: WidgetType, wraps: Boolean, modifier: Modifier, header: Modifier) -> Unit,
) {
    val wraps = rowWrapsContent(row, twoColumns = wide)
    Row(
        Modifier.fillMaxWidth().then(if (wraps) Modifier.heightIn(max = WIDGET_HEIGHT) else Modifier.height(WIDGET_HEIGHT)),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        row.forEach { type ->
            card(type, wraps, if (wraps) Modifier.weight(1f) else Modifier.weight(1f).fillMaxHeight(), header(type))
        }
        if (wide && row.size == 1) Spacer(Modifier.weight(1f))
    }
}

// --- arranging -------------------------------------------------------------------------------

@Composable
private fun ArrangeHint() {
    Text(
        stringResource(R.string.home_arrange_hint),
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = OttershelfTheme.colors.mutedForeground,
    )
}

/**
 * A card while arranging: its title as on the Dashboard and a drag handle. The handle drags at
 * once; a long press on its title picks it up too. Picked up, it lifts (the card colour, a
 * shadow and an accent edge) above the others. Move up / down are offered to accessibility services.
 */
@Composable
private fun ArrangeCard(
    card: HomeCard,
    reorder: ReorderState,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveBy: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OttershelfTheme.colors
    val dragging = reorder.draggingKey == card.key
    val elevation by animateDpAsState(if (dragging) 12.dp else 0.dp, label = "arrange-lift")
    val shape = RoundedCornerShape(OttershelfTheme.radii.xl2)
    val up = stringResource(R.string.home_move_up)
    val down = stringResource(R.string.home_move_down)
    DashCard(
        modifier
            .fillMaxWidth()
            .shadow(elevation, shape, clip = false)
            .semantics {
                customActions = listOfNotNull(
                    CustomAccessibilityAction(up) { onMoveBy(card.key, -1); true }.takeIf { canMoveUp },
                    CustomAccessibilityAction(down) { onMoveBy(card.key, 1); true }.takeIf { canMoveDown },
                )
            },
        containerColor = if (dragging) colors.card else colors.dashCard,
        borderColor = if (dragging) colors.primary else colors.dashCardBorder,
        shape = shape,
        contentPadding = PaddingValues(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(ARRANGE_ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
            // A long press on the title picks the card up (not on the handle, which drags at once).
            Box(Modifier.weight(1f).fillMaxHeight().longPressDrag(reorder, card.key), contentAlignment = Alignment.CenterStart) {
                when (card) {
                    is HomeCard.Widget -> CardTitle(stringResource(widgetTitle(card.type)), icon = card.type.icon)
                    is HomeCard.Shelf -> SectionHeader(shelfTitle(card.shelf.config), icon = card.shelf.config.shelfType.icon)
                }
            }
            Box(
                Modifier.size(ARRANGE_ROW_HEIGHT).dragHandle(reorder, card.key),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon(
                    "GripVertical",
                    contentDescription = stringResource(R.string.home_drag_handle),
                    tint = if (dragging) colors.primary else colors.mutedForeground,
                    size = 20.dp,
                )
            }
        }
    }
}

// --- Currently Reading -----------------------------------------------------------------------

/**
 * A drag that scrolled the reading list back to its top doesn't go on to pull the page to refresh
 * (the Nexus `listDragged`): [list] notes that the list moved during the drag, and [page], between
 * the page's scroll and the pull to refresh, keeps the rest of that drag from reaching the pull.
 * The page itself still scrolls, and a drag that starts with the list at its top still pulls.
 */
private class ReadingListDrag {
    private var dragged = false

    val list = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && consumed.y != 0f) dragged = true
            return Offset.Zero
        }

        // The drag is over (a fling follows it, even a still one).
        override suspend fun onPreFling(available: Velocity): Velocity {
            dragged = false
            return Velocity.Zero
        }
    }

    val page = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (dragged && source == NestedScrollSource.UserInput && available.y > 0f) Offset(0f, available.y) else Offset.Zero
    }
}

/**
 * Currently Reading: up to 10 books, scrolling inside the card as on the web. [wraps]: stacked on
 * a row of its own, the card is as tall as its books (up to the card height, then they scroll),
 * so one book doesn't leave an empty card; side by side it has the card height.
 */
@Composable
private fun ReadingCard(
    rows: List<ReadingRow>?,
    failed: Boolean,
    onRetry: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onRead: (Long, Long, String?, String) -> Unit,
    timerBookId: Long?,
    onTimer: (Long) -> Unit,
    listScroll: NestedScrollConnection,
    wraps: Boolean,
    header: Modifier,
    modifier: Modifier,
    onScanForTimer: (() -> Unit)? = null,
) {
    DashCard(modifier) {
        if (onScanForTimer == null) {
            CardTitle(stringResource(R.string.home_currently_reading), header, icon = "BookOpen")
        } else {
            // A paper book not on the list yet: scan it to time it (feature.scan).
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CardTitle(stringResource(R.string.home_currently_reading), header.weight(1f), icon = "BookOpen")
                ScanForTimerAction(onScanForTimer, Modifier.padding(start = 8.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        val area = if (wraps) Modifier.fillMaxWidth() else Modifier.fillMaxSize()
        Box(Modifier.fillMaxWidth().weight(1f, fill = !wraps).nestedScroll(listScroll)) {
            when {
                rows != null && rows.isNotEmpty() -> {
                    // From the first book after each load.
                    val ids = remember(rows) { rows.map { it.book.bookId } }
                    LazyColumn(area, state = rememberDashboardListState(ids), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(rows, key = { it.book.bookId }) { row -> ReadingRowItem(row, onOpenBook, onRead, row.book.bookId == timerBookId, onTimer) }
                    }
                }
                rows != null ->
                    EmptyState(
                        stringResource(R.string.home_currently_reading_empty),
                        area,
                        icon = "BookOpen",
                        compact = true,
                        contentPadding = PaddingValues(8.dp),
                    )
                failed -> CardFailed(onRetry, area)
                // Loading: one book's row, the size the card most often is.
                else -> SkeletonBox(
                    Modifier.fillMaxWidth().height(READING_ROW_HEIGHT),
                    shape = RoundedCornerShape(OttershelfTheme.radii.lg),
                )
            }
        }
    }
}

@Composable
private fun ReadingRowItem(
    row: ReadingRow,
    onOpenBook: (Long) -> Unit,
    onRead: (Long, Long, String?, String) -> Unit,
    timing: Boolean,
    onTimer: (Long) -> Unit,
) {
    val colors = OttershelfTheme.colors
    val book = row.book
    val title = book.title ?: stringResource(R.string.components_untitled)
    val authors = book.authors.joinToString(", ")
    val percent = book.progress.roundToInt().coerceIn(0, 100)
    CardRow(onClick = { onOpenBook(book.bookId) }, modifier = Modifier.fillMaxWidth()) {
        BookCover(
            model = rememberCoverModel(row.cover),
            title = book.title,
            authors = authors,
            seed = book.title ?: book.bookId.toString(),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.size(36.dp, 56.dp),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (authors.isNotEmpty()) {
                Spacer(Modifier.height(1.dp))
                Text(authors, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillProgressBar(percent / 100f, Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.components_percent, percent),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = colors.mutedForeground,
                )
            }
        }
        // The reading timer for this book (filled while it is the one timing).
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (timing) colors.primary else colors.accentTint)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.timer_row_button)) { onTimer(book.bookId) },
            contentAlignment = Alignment.Center,
        ) {
            LucideIcon(
                "Timer",
                contentDescription = stringResource(R.string.timer_row_button),
                tint = if (timing) colors.onPrimary else colors.primary,
                size = 16.dp,
            )
        }
        // Straight into the reader, with the file the web would open, if it's one this app reads.
        val fileId = book.readFileId?.takeIf { BookFormats.isOpenable(book.readFileFormat) }
        if (fileId != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.primary)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.home_continue_reading)) {
                        onRead(book.bookId, fileId, book.readFileFormat, title)
                    },
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon(
                    "Play",
                    contentDescription = stringResource(R.string.home_continue_reading),
                    tint = colors.onPrimary,
                    size = 14.dp,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
    }
}

/** A [DailyCover] as a Coil request cached under its day key; any other model as it is. */
@Composable
internal fun rememberCoverModel(cover: Any?): Any? {
    if (cover !is DailyCover) return cover
    val context = LocalPlatformContext.current
    return remember(cover, context) {
        ImageRequest.Builder(context).data(cover.url).diskCacheKey(cover.key).memoryCacheKey(cover.key).build()
    }
}

// --- Reading Streak --------------------------------------------------------------------------

/**
 * [fixedHeight]: the card has the Currently Reading card's height (side by side), and the streak
 * is centred in what's left of it; otherwise (stacked) it's as tall as its content.
 */
@Composable
private fun StreakCard(streak: ReadingStreak?, failed: Boolean, onRetry: () -> Unit, fixedHeight: Boolean, header: Modifier, modifier: Modifier) {
    DashCard(modifier) {
        CardTitle(stringResource(R.string.home_reading_streak), header, icon = "Flame")
        if (fixedHeight) Spacer(Modifier.weight(1f))
        // As on the web: the empty state only for someone who has never read (a streak of 0 with a
        // best still shows the 0).
        val empty = streak != null && streak.currentStreak == 0 && streak.longestStreak == 0
        // The body is always laid out (invisible until it arrives, or behind a message), so the
        // card keeps its size.
        Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            StreakBody(streak ?: ReadingStreak(), Modifier.alpha(if (streak != null && !empty) 1f else 0f))
            when {
                empty -> EmptyState(
                    stringResource(R.string.home_reading_streak_empty),
                    icon = "Flame",
                    compact = true,
                    contentPadding = PaddingValues(0.dp),
                )
                streak == null && failed -> CardFailed(onRetry)
            }
        }
        if (fixedHeight) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun StreakBody(streak: ReadingStreak, modifier: Modifier) {
    val colors = OttershelfTheme.colors
    val dim = MaterialTheme.typography.bodySmall
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LucideIcon("Flame", contentDescription = null, tint = colors.flame, size = 20.dp)
            Spacer(Modifier.width(4.dp))
            Text(
                streak.currentStreak.toString(),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = MaterialTheme.typography.titleMedium.fontFamily,
                    fontSize = 30.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.sp,
                ),
                color = colors.foreground,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(stringResource(R.string.home_day_streak), style = dim, color = colors.mutedForeground)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LucideIcon("Trophy", contentDescription = null, tint = colors.mutedForeground, size = 12.dp)
            Spacer(Modifier.width(4.dp))
            Text(
                pluralStringResource(R.plurals.home_best_streak, streak.longestStreak, streak.longestStreak),
                style = dim,
                color = colors.mutedForeground,
            )
        }
        Spacer(Modifier.height(10.dp))
        // Seven dots, 6 days ago to today (the last one).
        val days = streak.lastSevenDays.takeLast(DAYS)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(DAYS) { i ->
                val active = days.getOrNull(i - (DAYS - days.size)) == true
                Box(Modifier.size(12.dp).background(if (active) colors.primary else colors.muted, CircleShape))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.home_last_seven_days), style = dim.copy(fontSize = 11.sp), color = colors.mutedForeground)
    }
}

/** A card's "couldn't load": dim text and Retry; the whole area retries when tapped. */
@Composable
internal fun CardFailed(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        ErrorState(
            onRetry = onRetry,
            message = stringResource(R.string.home_failed),
            compact = true,
            contentPadding = PaddingValues(12.dp),
        )
    }
}
