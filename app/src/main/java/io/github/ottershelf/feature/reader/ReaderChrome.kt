package io.github.ottershelf.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import io.github.ottershelf.R
import io.github.ottershelf.core.sync.ProgressStore.Position
import io.github.ottershelf.feature.reader.annotations.BookmarkRibbon
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.icons.AppIcons
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** What the reader's chrome can do. */
class ReaderActions(
    val onBack: () -> Unit = {},
    val onToc: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onSeek: (Float) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onBookmark: () -> Unit = {},
    val onNotes: () -> Unit = {},
    /** The footer was tapped: the next of page, time left in the chapter, time left in the book. */
    val onFooter: () -> Unit = {},
)

/**
 * The reader's layout (activity_reader.xml): [page] (the WebView) filling the screen on the book's
 * page colour, kept clear of the display cutout; over it, while [ReaderUiState.chromeVisible], the
 * top bar (Back, title, bookmark, highlights and bookmarks, Contents, Aa) and the bottom bar
 * (chapter, position slider, page and percentage). A spinner while the book opens, and the error
 * with Retry in a card if it can't. [overlay] (the selection popup) sits in the page's area, in its
 * coordinates; a [bookmarked] page shows a ribbon at the top.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderContent(
    state: ReaderUiState,
    actions: ReaderActions = ReaderActions(),
    snackbars: SnackbarHostState = remember { SnackbarHostState() },
    bookmarked: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
    page: @Composable (Modifier) -> Unit,
) {
    val colors = OttershelfTheme.colors
    val background = pageBackground(state.prefs)
    val focus = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(background)
            // Volume keys turn pages (when the WebView doesn't have the focus itself).
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.VolumeDown, Key.VolumeUp -> {
                        if (event.type == KeyEventType.KeyDown) {
                            if (event.key == Key.VolumeDown) actions.onNext() else actions.onPrevious()
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        page(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout))
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
            if (bookmarked && state.error == null && !state.loading) BookmarkRibbon()
            if (state.chapterEnd && state.prefs.scrolled && !state.chromeVisible && state.error == null && !state.loading) {
                NextChapterHint(pageColors(state.prefs).foreground, Modifier.align(Alignment.BottomCenter))
            }
            overlay()
        }

        if (state.error != null) {
            ReaderErrorCard(state.error, actions.onRetry, Modifier.align(Alignment.Center))
        } else if (state.loading) {
            LoadingState(Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(
            visible = state.chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            ReaderTopBar(state, actions, bookmarked)
        }
        AnimatedVisibility(
            visible = state.chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            ReaderBottomBar(state, actions.onSeek, actions.onFooter)
        }
        SnackbarHost(
            snackbars,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (state.chromeVisible) 120.dp else 16.dp),
        )
    }
    if (!LocalInspectionMode.current) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

/** The bars' fill: the app toolbar's (shell surface over the page colour). */
@Composable
private fun barColor(): Color {
    val colors = OttershelfTheme.colors
    return colors.shellSurface.compositeOver(colors.background)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderTopBar(state: ReaderUiState, actions: ReaderActions, bookmarked: Boolean) {
    val colors = OttershelfTheme.colors
    val open = !state.loading && state.error == null
    Column(Modifier.fillMaxWidth().background(barColor())) {
        Row(
            Modifier
                .windowInsetsPadding(
                    WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.onBack) {
                Icon(AppIcons.Back, contentDescription = stringResource(R.string.nav_back), tint = colors.foreground)
            }
            Text(
                text = state.title,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = actions.onBookmark, enabled = open) {
                LucideIcon(
                    if (bookmarked) "BookmarkCheck" else "Bookmark",
                    contentDescription = stringResource(if (bookmarked) R.string.reader_bookmark_remove else R.string.reader_bookmark_add),
                    tint = when {
                        !open -> colors.mutedForeground
                        bookmarked -> colors.primary
                        else -> colors.foreground
                    },
                    size = 22.dp,
                )
            }
            IconButton(onClick = actions.onNotes, enabled = open) {
                LucideIcon(
                    "Highlighter",
                    contentDescription = stringResource(R.string.reader_notes),
                    tint = if (open) colors.foreground else colors.mutedForeground,
                    size = 22.dp,
                )
            }
            IconButton(onClick = actions.onToc, enabled = state.toc.isNotEmpty()) {
                LucideIcon(
                    "TableOfContents",
                    contentDescription = stringResource(R.string.reader_contents),
                    tint = if (state.toc.isNotEmpty()) colors.foreground else colors.mutedForeground,
                    size = 22.dp,
                )
            }
            IconButton(onClick = actions.onSettings) {
                LucideIcon("ALargeSmall", contentDescription = stringResource(R.string.reader_settings), tint = colors.foreground, size = 22.dp)
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.border)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReaderBottomBar(state: ReaderUiState, onSeek: (Float) -> Unit, onFooter: () -> Unit) {
    val colors = OttershelfTheme.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth().background(barColor())) {
        HorizontalDivider(thickness = 1.dp, color = colors.border)
        Column(
            Modifier
                .windowInsetsPadding(
                    WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                )
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 6.dp),
        ) {
            Text(
                text = state.chapter,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Slider(
                value = dragging ?: state.fraction,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let(onSeek)
                    dragging = null
                },
                enabled = !state.loading && state.error == null,
                modifier = Modifier.fillMaxWidth().height(36.dp),
                colors = SliderDefaults.colors(
                    thumbColor = colors.primary,
                    activeTrackColor = colors.primary,
                    inactiveTrackColor = colors.muted,
                ),
                thumb = {
                    Box(Modifier.size(18.dp).background(colors.primary, CircleShape))
                },
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(4.dp),
                        colors = SliderDefaults.colors(activeTrackColor = colors.primary, inactiveTrackColor = colors.muted),
                        drawStopIndicator = null,
                        thumbTrackGapSize = 0.dp,
                    )
                },
            )
            FooterLine(state, dragging, onFooter)
        }
    }
}

/**
 * The footer: the page and percentage, or the reading time left in the chapter or the book
 * ([ReaderPrefs.footerDisplayMode]); a tap moves to the next. While the slider is dragged, where it
 * would go.
 */
@Composable
private fun FooterLine(state: ReaderUiState, dragging: Float?, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    val mode = FooterMode.of(state.prefs.footerDisplayMode)
    val timed = dragging == null && mode != FooterMode.PAGE
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(OttershelfTheme.radii.sm))
            .clickable(
                enabled = dragging == null && !state.loading && state.error == null,
                role = Role.Button,
                onClickLabel = stringResource(R.string.reader_footer_cycle),
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (timed) {
            LucideIcon("Hourglass", contentDescription = null, tint = colors.mutedForeground, size = 14.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = footerText(state, dragging),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = colors.mutedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun footerText(state: ReaderUiState, dragging: Float?): String {
    if (dragging != null) return stringResource(R.string.reader_position_percent, (dragging * 100).roundToInt())
    val pct = (state.fraction * 100).roundToInt()
    val left = state.timeLeft
    when (FooterMode.of(state.prefs.footerDisplayMode)) {
        FooterMode.CHAPTER -> left?.chapterSeconds?.let {
            return stringResource(R.string.reader_footer_chapter_left, readingTimeText(it), pct)
        }
        FooterMode.BOOK -> left?.bookSeconds?.let {
            return stringResource(R.string.reader_footer_book_left, readingTimeText(it), pct)
        }
        FooterMode.PAGE -> {}
    }
    if (FooterMode.of(state.prefs.footerDisplayMode) != FooterMode.PAGE) {
        return stringResource(R.string.reader_footer_no_estimate, pct)
    }
    val page = state.page
    val pages = state.pages
    return if (page != null && pages != null && pages > 0) {
        stringResource(R.string.reader_position_page, page + 1, pages, pct)
    } else stringResource(R.string.reader_position_percent, pct)
}

/** "< 1 min", "12 min", "2 h", "4 h 10 min". */
@Composable
internal fun readingTimeText(seconds: Long): String {
    val minutes = (seconds + 30) / 60
    return when {
        seconds < 60 -> stringResource(R.string.reader_duration_under_min)
        minutes < 60 -> stringResource(R.string.reader_duration_min, minutes)
        minutes % 60 == 0L -> stringResource(R.string.reader_duration_h, minutes / 60)
        else -> stringResource(R.string.reader_duration_h_min, minutes / 60, minutes % 60)
    }
}

/**
 * Scrolled flow, at the end of a chapter: where the next one is, in the page's bottom margin in the
 * page's own text colour, like a line of the page.
 */
@Composable
private fun NextChapterHint(pageText: Color, modifier: Modifier) {
    Row(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon("ChevronUp", contentDescription = null, tint = pageText.copy(alpha = 0.55f), size = 14.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.reader_next_chapter_hint),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = pageText.copy(alpha = 0.6f),
            maxLines = 1,
        )
    }
}

@Composable
private fun ReaderErrorCard(error: ReaderError, onRetry: () -> Unit, modifier: Modifier) {
    val colors = OttershelfTheme.colors
    val (message, detail) = when (error) {
        ReaderError.Offline -> stringResource(R.string.reader_offline_not_downloaded) to null
        ReaderError.Stopped -> stringResource(R.string.reader_stopped) to null
        is ReaderError.Failed -> stringResource(R.string.reader_open_failed) to error.message
    }
    Surface(
        modifier = modifier.padding(24.dp).widthIn(max = 420.dp),
        color = colors.card,
        shape = RoundedCornerShape(OttershelfTheme.radii.lg),
        border = BorderStroke(1.dp, colors.border),
    ) {
        ErrorState(onRetry = onRetry, message = message, detail = detail)
    }
}

// --- Contents --------------------------------------------------------------------------------

/** The table of contents, the current chapter marked like a selected drawer row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(toc: List<TocEntry>, currentHref: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = OttershelfTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = colors.card,
    ) {
        SheetTitle(stringResource(R.string.reader_contents))
        if (toc.isEmpty()) {
            EmptyState(stringResource(R.string.reader_toc_empty), icon = "TableOfContents", compact = true, modifier = Modifier.fillMaxWidth())
            return@ModalBottomSheet
        }
        val current = remember(toc, currentHref) { currentTocIndex(toc, currentHref) }
        LazyColumn(
            state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0)),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            itemsIndexed(toc) { index, entry ->
                TocRow(entry, selected = index == current, onClick = { entry.href?.let(onPick) })
            }
        }
    }
}

/** The entry for [href], ignoring fragments if there's no exact match; -1 if none. */
internal fun currentTocIndex(toc: List<TocEntry>, href: String?): Int {
    if (href == null) return -1
    val exact = toc.indexOfLast { it.href == href }
    if (exact >= 0) return exact
    val file = href.substringBefore('#')
    return toc.indexOfFirst { it.href?.substringBefore('#') == file }
}

@Composable
private fun TocRow(entry: TocEntry, selected: Boolean, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(OttershelfTheme.radii.md))
            .background(if (selected) colors.accentTint else Color.Transparent)
            .clickable(enabled = entry.href != null, role = Role.Button, onClick = onClick)
            .padding(start = 12.dp + 16.dp * entry.depth.coerceAtMost(4), end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.label.ifEmpty { "-" },
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            color = if (selected) colors.primary else if (entry.depth > 0) colors.mutedForeground else colors.foreground,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SheetTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
        color = OttershelfTheme.colors.foreground,
    )
}

// --- Two positions ---------------------------------------------------------------------------

/** Which of two reading positions to continue from; can't be dismissed without an answer. */
@Composable
fun PositionChoiceDialog(choice: PositionChoice, onChoose: (keepMine: Boolean) -> Unit) {
    val colors = OttershelfTheme.colors
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val mine = pct(choice.mine)
    val server = pct(choice.server)
    val serverWhen = io.github.ottershelf.core.util.IsoTime.parse(choice.serverReadAt)
        ?.let { stringResource(R.string.reader_conflict_last_read, format.format(Date(it))) }.orEmpty()
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        containerColor = colors.card,
        title = { Text(stringResource(R.string.reader_conflict_title)) },
        text = {
            Text(
                stringResource(R.string.reader_conflict_device, mine, format.format(Date(choice.mineAt))) + "\n\n" +
                    stringResource(R.string.reader_conflict_server, server, serverWhen) + "\n\n" +
                    stringResource(R.string.reader_conflict_question),
            )
        },
        confirmButton = {
            TextButton(onClick = { onChoose(true) }) { Text(stringResource(R.string.reader_conflict_keep_device, mine)) }
        },
        dismissButton = {
            TextButton(onClick = { onChoose(false) }) { Text(stringResource(R.string.reader_conflict_keep_server, server)) }
        },
    )
}

private fun pct(p: Position) = "${p.percentage.roundToInt()}%"
