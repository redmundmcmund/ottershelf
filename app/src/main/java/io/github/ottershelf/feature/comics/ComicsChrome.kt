package io.github.ottershelf.feature.comics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import io.github.ottershelf.R
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.icons.AppIcons
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import kotlin.math.roundToInt

/** What the comics reader's chrome and pages can do. */
class ComicsActions(
    val onBack: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onToggleChrome: () -> Unit = {},
    /** Pages now on screen (0-based, reading order). */
    val onPagesShown: (List<Int>) -> Unit = {},
    /** The user zoomed or scrolled (not a page change): a move, and the session isn't idle. */
    val onActivity: () -> Unit = {},
    val onPageSize: (page: Int, width: Int, height: Int) -> Unit = { _, _, _ -> },
    /** Go to this page (0-based): the slider. */
    val onSeek: (Int) -> Unit = {},
    val onJumpHandled: (Int) -> Unit = {},
    /** A turn forward on the last page, by tap, key or swipe (auto-advance). */
    val onPastEnd: () -> Unit = {},
    val onOpenNext: () -> Unit = {},
    /** The strip went to the opening's page ([ComicsUiState.stripPlaced]). */
    val onStripPlaced: () -> Unit = {},
)

/** The web's CBZ backgrounds (CBZ_BG_VALUES): fixed page colours, not the app theme's. */
internal fun comicBackground(id: String): Color = when (id) {
    "gray" -> Color(0xFF525659)
    "white" -> Color(0xFFE8E8E8)
    else -> Color(0xFF0A0A0A)
}

/**
 * The comics reader's layout, the EPUB reader's (feature.reader.ReaderContent): the pages filling the
 * screen on the chosen background, kept clear of the display cutout; over them, while
 * [ComicsUiState.chromeVisible], the top bar (Back, title, settings) and the bottom bar (page slider,
 * "12 / 180"). A spinner while it opens, the error with Retry in a card if it can't. At the last
 * page of a paged comic the series' next book is offered in a card. Volume keys turn pages.
 * [imageLoader]: the reader's ([ComicImages]); null in screenshot tests.
 */
@Composable
fun ComicsContent(
    state: ComicsUiState,
    actions: ComicsActions = ComicsActions(),
    imageLoader: ImageLoader? = null,
    snackbars: SnackbarHostState = remember { SnackbarHostState() },
) {
    val background = comicBackground(state.settings.bgColor)
    val focus = remember { FocusRequester() }
    val turner = remember { PageTurner() }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(background)
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.VolumeDown, Key.VolumeUp -> {
                        if (event.type == KeyEventType.KeyDown && state.ready) {
                            if (event.key == Key.VolumeDown) turner.next() else turner.previous()
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        val landscape = maxWidth > maxHeight
        if (state.ready) {
            ComicPages(
                state = state,
                actions = actions,
                imageLoader = imageLoader,
                turner = turner,
                landscape = landscape,
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout),
            )
        }

        if (state.error != null) {
            ComicsErrorCard(state.error, actions.onRetry, Modifier.align(Alignment.Center))
        } else if (state.loading) {
            LoadingState(Modifier.align(Alignment.Center))
        }

        val next = state.nextBook
        // Switched off in Settings, it still shows with auto-advance on: it says what the turn will open.
        if (next != null && state.ready && state.paged && state.atLastPage && (state.suggestNext || state.settings.autoAdvance)) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = if (state.chromeVisible) 120.dp else 24.dp),
            ) {
                NextIssueCard(next, cover = state.nextCover, autoAdvance = state.settings.autoAdvance, onOpen = actions.onOpenNext)
            }
        }

        AnimatedVisibility(
            visible = state.chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            ComicsTopBar(state, actions)
        }
        AnimatedVisibility(
            visible = state.chromeVisible && state.ready,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            ComicsBottomBar(state, actions.onSeek)
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

/** The bars' fill: the app toolbar's (shell surface over the page colour), as in the EPUB reader. */
@Composable
private fun barColor(): Color {
    val colors = OttershelfTheme.colors
    return colors.shellSurface.compositeOver(colors.background)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComicsTopBar(state: ComicsUiState, actions: ComicsActions) {
    val colors = OttershelfTheme.colors
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
            if (state.offline) {
                LucideIcon(
                    "HardDriveDownload",
                    contentDescription = stringResource(R.string.comics_reading_downloaded),
                    tint = colors.mutedForeground,
                    size = 18.dp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            IconButton(onClick = actions.onSettings) {
                LucideIcon("Settings2", contentDescription = stringResource(R.string.comics_settings), tint = colors.foreground, size = 22.dp)
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.border)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ComicsBottomBar(state: ComicsUiState, onSeek: (Int) -> Unit) {
    val colors = OttershelfTheme.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    val maxPage = (state.pageCount - 1).coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().background(barColor())) {
        HorizontalDivider(thickness = 1.dp, color = colors.border)
        Column(
            Modifier
                .windowInsetsPadding(
                    WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                )
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp),
        ) {
            // A right-to-left comic's slider runs right to left too.
            CompositionLocalProvider(LocalLayoutDirection provides if (state.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Slider(
                    value = dragging ?: state.currentPage.toFloat(),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { onSeek(it.roundToInt()) }
                        dragging = null
                    },
                    valueRange = 0f..maxPage.toFloat(),
                    enabled = state.pageCount > 1,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = colors.primary,
                        activeTrackColor = colors.primary,
                        inactiveTrackColor = colors.muted,
                    ),
                    thumb = { Box(Modifier.size(18.dp).background(colors.primary, CircleShape)) },
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
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pageLabel(state, dragging?.roundToInt()),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = colors.mutedForeground,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.comics_percent, percentOf(state)),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = colors.mutedForeground,
                    maxLines = 1,
                )
            }
        }
    }
}

/** "12 / 180", "12-13 / 180" for a spread, or the page the slider is being dragged to. */
@Composable
private fun pageLabel(state: ComicsUiState, dragging: Int?): String {
    val count = state.pageCount
    if (dragging != null) return stringResource(R.string.comics_page_label, dragging + 1, count)
    val first = state.currentPage + 1
    val last = state.lastShownPage + 1
    return if (last > first) stringResource(R.string.comics_page_label_spread, first, last, count)
    else stringResource(R.string.comics_page_label, first, count)
}

/** The percentage saved for the page on screen (a spread counts its last page), as the web shows it. */
private fun percentOf(state: ComicsUiState): Int =
    if (state.pageCount <= 0) 0 else ((state.lastShownPage + 1) * 100.0 / state.pageCount).roundToInt()

@Composable
private fun ComicsErrorCard(error: ComicsError, onRetry: () -> Unit, modifier: Modifier) {
    val colors = OttershelfTheme.colors
    val (message, detail) = when (error) {
        ComicsError.Offline -> stringResource(R.string.comics_offline_not_downloaded) to null
        ComicsError.Empty -> stringResource(R.string.comics_no_pages) to null
        is ComicsError.Failed -> stringResource(R.string.comics_open_failed) to error.message
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

/**
 * The series' next comic (the web's NextIssueCard): cover, "Next in series", "#3 Title", and Read
 * next. With auto-advance on, a hint that one more turn opens it.
 */
@Composable
internal fun NextIssueCard(next: SeriesNextBook, cover: Any?, autoAdvance: Boolean, onOpen: () -> Unit) {
    val colors = OttershelfTheme.colors
    val radii = OttershelfTheme.radii
    val title = next.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.comics_next_untitled)
    val label = next.seriesIndex?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.comics_next_numbered, it, title) } ?: title
    val openLabel = stringResource(R.string.comics_next_open, label)
    Surface(
        modifier = Modifier.widthIn(max = 448.dp).fillMaxWidth(),
        color = colors.background.copy(alpha = 0.96f),
        shape = RoundedCornerShape(radii.xl),
        border = BorderStroke(1.dp, colors.border),
        shadowElevation = 12.dp,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(model = cover, title = next.title, modifier = Modifier.width(44.dp), seed = next.bookId.toString())
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = stringResource(R.string.comics_next_eyebrow).uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
                    color = colors.mutedForeground,
                    maxLines = 1,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (autoAdvance) {
                    Text(
                        text = stringResource(R.string.comics_next_auto_hint),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = colors.mutedForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(radii.md))
                    .background(colors.primary)
                    .clickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpen)
                    .padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.comics_next_action),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.onPrimary,
                )
                LucideIcon("ChevronRight", contentDescription = null, tint = colors.onPrimary, size = 16.dp)
            }
        }
    }
}
