package io.github.ottershelf.feature.library

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.github.ottershelf.R
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.model.formatSeriesIndex
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.CoverProgressBar
import io.github.ottershelf.ui.components.FormatChip
import io.github.ottershelf.ui.components.PillProgressBar
import io.github.ottershelf.ui.components.StatusIcon
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a book grid lays out: [columns] cells (or columns of rows, in the list view) and each cover's width. */
@Immutable
data class BookGridLayout(val list: Boolean, val columns: Int, val coverWidth: Dp)

/** Each grid cell pads its content this much (BookGridItem's 6dp). */
private val CELL_PADDING = 6.dp

/** The list view's covers. */
private val ROW_COVER_WIDTH = 56.dp

/**
 * The grid's width inside its padding, for the view menu's stepper (which turns a column count into
 * the cover size to remember). Written by [BookGridFrame].
 */
@Stable
class GridMetrics {
    var widthDp by mutableFloatStateOf(0f)
        internal set
}

/**
 * A book grid's frame: works out the columns from [view] and the width (inside [horizontalPadding],
 * the grid's own content padding), and in the grid view lets the user pinch to change them. [content]
 * lays out the lazy grid with the [BookGridLayout] and puts the modifier it is given on it (the pinch
 * and the grid's live zoom while pinching).
 *
 * The pinch: the covers follow the fingers (the grid is drawn bigger or smaller), and a step to
 * one column fewer or more comes with a haptic tick ([PinchSteps]); the book under the fingers is
 * kept where it is. When the fingers lift, the new size is remembered ([onViewChange]).
 */
@Composable
fun BookGridFrame(
    view: ListView,
    onViewChange: (ListView) -> Unit,
    gridState: LazyGridState,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
    metrics: GridMetrics? = null,
    content: @Composable (layout: BookGridLayout, gridModifier: Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val inner = (maxWidth - horizontalPadding).coerceAtLeast(1.dp)
        val widthDp = inner.value
        val scope = rememberCoroutineScope()
        val pinch = remember { GridPinch(scope) }
        // Once the user's pinch is the saved view (or the menu changed it), that's what counts.
        LaunchedEffect(view) { pinch.liveColumns = null }
        val range = GridColumns.range(widthDp)
        val saved = GridColumns.count(widthDp, view.cellDp)
        val columns = when {
            view.isList -> GridColumns.listColumns(widthDp)
            else -> pinch.liveColumns?.coerceIn(range) ?: saved
        }
        val layout = remember(view.isList, columns, inner) {
            BookGridLayout(view.isList, columns, if (view.isList) ROW_COVER_WIDTH else (inner / columns - CELL_PADDING * 2).coerceAtLeast(1.dp))
        }
        val haptics = LocalHapticFeedback.current
        val density = LocalDensity.current
        SideEffect {
            pinch.gridState = gridState
            pinch.columns = columns
            pinch.range = range
            pinch.widthPx = with(density) { inner.toPx() }
            pinch.paddingPx = with(density) { CELL_PADDING.toPx() }
            pinch.haptics = haptics
            pinch.onCommit = { n ->
                if (n == saved) pinch.liveColumns = null else onViewChange(view.copy(cellDp = GridColumns.cellFor(widthDp, n)))
            }
            metrics?.widthDp = widthDp
        }
        // One modifier for good: a new one each time could restart the gesture in the middle of a pinch.
        val pinchModifier = remember(pinch) { Modifier.gridPinch(pinch) }
        content(layout, if (view.isList) Modifier else pinchModifier)
    }
}

/** The cells of a [BookGridLayout], remembered so the grid isn't handed a new one each time. */
@Composable
fun rememberGridCells(layout: BookGridLayout): GridCells = remember(layout.columns) { GridCells.Fixed(layout.columns) }

/**
 * A pinch in progress on a book grid: the live column count, and how the grid is drawn meanwhile
 * (zoomed [scale] times and moved by [offset]: a point of the grid is drawn at `point * scale +
 * offset`). The grid's measures come in on every composition ([BookGridFrame]).
 */
@Stable
internal class GridPinch(private val scope: CoroutineScope) {
    /** The pinch's column count until the saved view catches up. */
    var liveColumns by mutableStateOf<Int?>(null)

    /** Read only while drawing (the grid's layer), so a pinch only redraws. */
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    var gridState: LazyGridState? = null
    var columns = 1
    var range = 1..1
    var widthPx = 0f
    var paddingPx = 0f
    var haptics: HapticFeedback? = null
    var onCommit: (Int) -> Unit = {}

    private val steps = PinchSteps()
    private var settle: Job? = null

    /** Where the zoom is anchored between steps: the fingers when the pinch began, then at its last step. */
    private var anchor = Offset.Zero

    /** Where [point] of the grid is drawn now. */
    fun drawnAt(point: Offset): Offset = point * scale + offset

    /**
     * Two fingers are down with their middle at [focus]. A pinch that starts while the last one is
     * still springing back goes on from the size drawn now: nothing jumps.
     */
    fun begin(focus: Offset) {
        settle?.cancel()
        steps.start(scale)
        anchor = focus
    }

    fun zoom(factor: Float, focus: Offset) {
        val step = steps.onZoom(factor, columns, range)
        if (step != 0 && step(step, focus)) {
            // The book under the fingers is at [focus] in the new layout: what's left of the zoom is drawn about it.
            anchor = focus
            draw(steps.zoom, focus * (1f - steps.zoom))
        } else {
            zoomTo(steps.zoom)
        }
    }

    fun end(focus: Offset) {
        val zoom = steps.zoom
        val before = columns
        val step = steps.onEnd(columns, range)
        if (step != 0 && step(step, focus)) {
            val left = zoom / PinchSteps.ratio(before, columns)
            draw(left, focus * (1f - left))
        }
        liveColumns?.let(onCommit)
        // Back to the grid as laid out: the zoom to 1 and any offset to none, together.
        val fromScale = scale
        val fromOffset = offset
        settle = scope.launch {
            animate(0f, 1f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { t, _ ->
                draw(fromScale + (1f - fromScale) * t, fromOffset * (1f - t))
            }
        }
    }

    /** Zoomed to [next], keeping the point of the grid drawn at the [anchor] where it is. */
    private fun zoomTo(next: Float) {
        val k = next / scale.coerceAtLeast(MIN_SCALE)
        draw(next, anchor - (anchor - offset) * k)
    }

    private fun draw(nextScale: Float, nextOffset: Offset) {
        scale = nextScale
        offset = nextOffset
    }

    /** One column more or fewer; false when there's no such step. */
    private fun step(delta: Int, focus: Offset): Boolean {
        val from = columns
        val to = (from + delta).coerceIn(range)
        if (to == from) return false
        gridState?.let { keepInPlace(it, from, to, focus) }
        liveColumns = to
        columns = to
        haptics?.performHapticFeedback(HapticFeedbackType.SegmentTick)
        return true
    }

    /**
     * Scrolls so that the book under [focus] (as drawn now) is still under it once the grid has [to]
     * columns: its new top is worked out from its new height, and asked for with the change, so the
     * first frame at the new count already has it in place.
     */
    private fun keepInPlace(state: LazyGridState, from: Int, to: Int, focus: Offset) {
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        if (items.isEmpty() || widthPx <= 0f) return
        val before = info.beforeContentPadding.toFloat()
        val point = (focus - offset) / scale.coerceAtLeast(MIN_SCALE)
        val item = items.minBy { distance(point, it, before) }
        val top = item.offset.y + before
        val fraction = (point.y - top) / item.size.height.coerceAtLeast(1)
        val height = cellHeightAt(widthPx / to, widthPx / from, item.size.height.toFloat(), paddingPx)
        val newTop = anchoredTop(focus.y, fraction, height)
        state.requestScrollToItem(item.index, -(newTop - before).roundToInt())
    }

    private fun distance(point: Offset, item: LazyGridItemInfo, before: Float): Float {
        val top = item.offset.y + before
        val left = item.offset.x.toFloat()
        val dy = when {
            point.y < top -> top - point.y
            point.y > top + item.size.height -> point.y - top - item.size.height
            else -> 0f
        }
        val dx = abs(point.x - (left + item.size.width / 2f))
        // A row apart counts for far more than a cell apart along the row.
        return dy * 10f + dx
    }

    private companion object {
        const val MIN_SCALE = 0.01f
    }
}

/**
 * Two fingers on the grid pinch it ([GridPinch]); one finger scrolls and taps as before. The pinch
 * takes its events before the grid and the covers (the Initial pass) and consumes them, so the grid
 * doesn't scroll and no cover is tapped or long-pressed meanwhile.
 */
internal fun Modifier.gridPinch(pinch: GridPinch): Modifier = this
    .pointerInput(pinch) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var pinching = false
            var focus = Offset.Zero
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val down = event.changes.count { it.pressed }
                if (down == 0) break
                if (!pinching && down >= 2) {
                    pinching = true
                    // The middle of every finger down, the one landing now included (calculateCentroid
                    // leaves out a pointer that has only just gone down, which would anchor the zoom on
                    // the first finger rather than between them).
                    focus = pressedCentroid(event.changes)
                    pinch.begin(focus)
                }
                if (pinching) {
                    if (down >= 2) {
                        val c = event.calculateCentroid(useCurrent = true)
                        if (c.isSpecified) focus = c
                        val zoom = event.calculateZoom()
                        if (zoom != 1f) pinch.zoom(zoom, focus)
                    }
                    event.changes.forEach { it.consume() }
                }
            }
            if (pinching) pinch.end(focus)
        }
    }
    .clipToBounds()
    .graphicsLayer {
        // A point of the grid is drawn at point * scale + offset (GridPinch).
        val s = pinch.scale
        scaleX = s
        scaleY = s
        val t = pinch.offset
        translationX = t.x
        translationY = t.y
        transformOrigin = TransformOrigin(0f, 0f)
    }

/** The middle of the pointers that are down in [changes] (current positions, just landed or not). */
internal fun pressedCentroid(changes: List<PointerInputChange>): Offset {
    var sum = Offset.Zero
    var count = 0
    for (change in changes) {
        if (!change.pressed) continue
        sum += change.position
        count++
    }
    return if (count == 0) Offset.Zero else sum / count.toFloat()
}

// --- the list view's row ---------------------------------------------------------------------------

/**
 * A book as a row of the list view (the Nexus Currently Reading and request rows, on a card): a
 * small cover, the title (two lines), authors, the series and its number, then the formats, the
 * read status and the user's rating, and the progress. The row pads itself as a grid cell does, so the
 * gutters match the grid's.
 */
@Composable
fun BookRow(
    book: BookCard,
    cover: Any?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    status: ReadStatus? = ReadStatus.of(book.readStatus?.status),
    onLongClick: (() -> Unit)? = null,
) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.lg)
    val title = book.title ?: stringResource(R.string.components_untitled)
    val authors = remember(book.authors) { book.authors.joinToString(", ") }
    val formats = remember(book.files) { rowFormats(book.files) }
    val progress = ((book.readingProgress ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    val read = status == ReadStatus.READ
    Row(
        modifier
            .padding(horizontal = CELL_PADDING, vertical = 4.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.border, shape)
            .combinedClickable(
                interactionSource = null,
                indication = ripple(color = colors.primary),
                role = Role.Button,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        BookCover(
            model = cover,
            title = book.title,
            authors = authors,
            seed = book.title ?: book.id.toString(),
            modifier = Modifier.width(ROW_COVER_WIDTH),
            requestWidth = ROW_COVER_WIDTH,
        ) {
            CoverProgressBar(progress = progress, read = read, modifier = Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).heightIn(min = ROW_COVER_WIDTH * 1.5f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 19.sp),
                color = colors.foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (authors.isNotEmpty()) {
                Text(
                    text = authors,
                    modifier = Modifier.padding(top = 1.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = colors.mutedForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            book.seriesName?.takeIf { it.isNotBlank() }?.let { series ->
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    LucideIcon("BookCopy", contentDescription = null, tint = colors.mutedForeground, size = 12.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = book.seriesIndex?.let { stringResource(R.string.library_row_series, series, formatSeriesIndex(it)) } ?: series,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.mutedForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // The chips and progress sit at the bottom, level with the cover's foot when the text is short.
            Spacer(Modifier.height(6.dp))
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                formats.forEach { FormatChip(it) }
                if (status != null && status != ReadStatus.UNREAD) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIcon(status, size = 14.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = status.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.readStatus(status),
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                book.rating?.takeIf { it in 1..5 }?.let { RatingStars(it) }
            }
            if (progress > 0f) {
                Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillProgressBar(
                        progress = progress,
                        modifier = Modifier.weight(1f),
                        height = 4.dp,
                        color = if (read) colors.coverProgressRead else colors.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.library_row_percent, (progress * 100).roundToInt()),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = colors.mutedForeground,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The formats a row names: each file's once, the primary file's first, at most three. */
internal fun rowFormats(files: List<BookFile>): List<String> =
    files.sortedBy { if (it.role == "primary") 0 else 1 }
        .mapNotNull { it.format?.lowercase()?.takeIf { f -> f.isNotBlank() } }
        .distinct()
        .take(3)

/** The user's rating as five small stars, the given ones in the star colour (as History shows it). */
@Composable
internal fun RatingStars(rating: Int, modifier: Modifier = Modifier, size: Int = 12) {
    val colors = OttershelfTheme.colors
    val description = stringResource(R.string.library_rated, rating)
    Row(modifier.clearAndSetSemantics { contentDescription = description }) {
        Text(
            buildString { repeat(rating.coerceIn(0, 5)) { append('★') } },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = size.sp, lineHeight = (size + 3).sp, letterSpacing = 0.5.sp),
            color = colors.starHighlight,
            maxLines = 1,
        )
        Text(
            buildString { repeat(5 - rating.coerceIn(0, 5)) { append('☆') } },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = size.sp, lineHeight = (size + 3).sp, letterSpacing = 0.5.sp),
            color = colors.mutedForeground,
            maxLines = 1,
        )
    }
}

// --- the view menu -------------------------------------------------------------------------------

/**
 * The book grids' view action (toolbar, beside Sort and Search): the current view's icon, opening
 * a menu with Grid / List and, in the grid, the cover size as a stepper (for anyone who doesn't
 * pinch). Changes apply at once and are remembered for the list.
 */
@Composable
fun ViewMenuButton(view: ListView, metrics: GridMetrics, onChange: (ListView) -> Unit) {
    val colors = OttershelfTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            LucideIcon(
                if (view.isList) "LayoutList" else "LayoutGrid",
                contentDescription = stringResource(R.string.library_view_action),
                tint = LocalContentColor.current,
                size = 22.dp,
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = colors.popover,
            shape = RoundedCornerShape(OttershelfTheme.radii.lg),
        ) {
            ViewMenuContent(view, metrics.widthDp, onChange)
        }
    }
}

/** The view menu's content (stateless, screenshot-tested). [widthDp] is the grid's ([GridMetrics]). */
@Composable
fun ViewMenuContent(view: ListView, widthDp: Float, onChange: (ListView) -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    Column(modifier.width(252.dp)) {
        MenuLabel(stringResource(R.string.library_view_title))
        ModeRow("LayoutGrid", stringResource(R.string.library_view_grid), selected = !view.isList) { onChange(view.copy(mode = ViewMode.GRID)) }
        ModeRow("LayoutList", stringResource(R.string.library_view_list), selected = view.isList) { onChange(view.copy(mode = ViewMode.LIST)) }
        if (!view.isList && widthDp > 0f) {
            val columns = GridColumns.count(widthDp, view.cellDp)
            val range = GridColumns.range(widthDp)
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = colors.border)
            MenuLabel(stringResource(R.string.library_view_covers_per_row))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                // Fewer per row: bigger covers; more per row: smaller ones.
                StepButton("Minus", stringResource(R.string.library_view_fewer), enabled = columns > range.first) {
                    onChange(view.copy(cellDp = GridColumns.cellFor(widthDp, GridColumns.step(widthDp, columns, -1))))
                }
                Text(
                    columns.toString(),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                    textAlign = TextAlign.Center,
                )
                StepButton("Plus", stringResource(R.string.library_view_more), enabled = columns < range.last) {
                    onChange(view.copy(cellDp = GridColumns.cellFor(widthDp, GridColumns.step(widthDp, columns, 1))))
                }
            }
            Text(
                stringResource(R.string.library_view_pinch_hint),
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.mutedForeground,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
        color = OttershelfTheme.colors.mutedForeground,
    )
}

@Composable
private fun ModeRow(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(OttershelfTheme.radii.md))
            .background(if (selected) colors.accentTint else colors.popover)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon(icon, contentDescription = null, tint = if (selected) colors.primary else colors.mutedForeground, size = 18.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            color = if (selected) colors.primary else colors.foreground,
        )
        if (selected) LucideIcon("Check", contentDescription = null, tint = colors.primary, size = 18.dp)
    }
}

/** A 40dp square button with a border (the sort sheet's rows' look), dim while it can't go further. */
@Composable
private fun StepButton(icon: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.md)
    Box(
        Modifier
            .size(40.dp)
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.border, shape)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(icon, contentDescription = null, tint = if (enabled) colors.foreground else colors.mutedForeground.copy(alpha = 0.5f), size = 18.dp)
    }
}
