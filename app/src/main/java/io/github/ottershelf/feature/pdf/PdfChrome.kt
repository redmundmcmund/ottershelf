package io.github.ottershelf.feature.pdf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import io.github.ottershelf.R
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.icons.AppIcons
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import kotlin.math.roundToInt
import io.github.ottershelf.ui.components.belowStatusBar

/** What the PDF reader's chrome can do. */
class PdfActions(
    val onBack: () -> Unit = {},
    val onContents: () -> Unit = {},
    val onSearch: () -> Unit = {},
    val onSettings: () -> Unit = {},
    /** The slider let go at this page (1-based). */
    val onSeek: (Int) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNextHit: () -> Unit = {},
    val onPreviousHit: () -> Unit = {},
    val onClearSearch: () -> Unit = {},
    val viewer: PdfViewerEvents = PdfViewerEvents(),
)

/**
 * The PDF reader's layout, the EPUB reader's (feature.reader.ReaderContent): the pages filling the
 * screen, kept clear of the display cutout; over them, while [PdfUiState.chromeVisible], the top bar
 * (Back, title, Contents, Search, Settings) and the bottom bar (the chapter from the outline, the
 * search's matches with previous/next, the page slider and "Page 12 of 300"). A spinner (with the
 * download's progress) while the file comes, the error with Retry in a card if it can't be opened.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PdfContent(
    state: PdfUiState,
    actions: PdfActions = PdfActions(),
    jumps: Flow<PdfJump> = emptyFlow(),
    snackbars: SnackbarHostState = remember { SnackbarHostState() },
) {
    val colors = OttershelfTheme.colors
    val focus = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            // Volume keys turn pages, as in the EPUB reader.
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.VolumeDown, Key.VolumeUp -> {
                        if (state.ready && event.type == KeyEventType.KeyDown) {
                            if (event.key == Key.VolumeDown) actions.onNext() else actions.onPrevious()
                        }
                        state.ready
                    }
                    else -> false
                }
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        val source = state.source
        if (state.ready && source != null) {
            val search = state.search
            val marks = remember(search.hits, search.selected, colors.primary) {
                PdfMarks(
                    hits = search.hits.groupBy { it.page },
                    selected = search.hits.getOrNull(search.selected),
                    color = colors.primary.copy(alpha = 0.28f),
                    selectedColor = colors.primary.copy(alpha = 0.55f),
                )
            }
            val pages = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)
            key(state.view.scroll, source) {
                // Opens where the other layout was.
                val initial = remember { state.page - 1 }
                when (state.view.scroll) {
                    PdfScroll.Continuous ->
                        ContinuousPages(source, state.sizes, state.view.fit, state.view.night, initial, jumps, marks, actions.viewer, pages)
                    PdfScroll.Paged ->
                        PagedPages(source, state.sizes, state.view.fit, state.view.night, initial, jumps, marks, actions.viewer, pages)
                }
            }
        }

        when (val phase = state.phase) {
            is PdfPhase.Loading -> LoadingMessage(
                phase.progress?.let { stringResource(R.string.pdf_downloading_percent, (it * 100).roundToInt()) }
                    ?: stringResource(R.string.pdf_loading),
                Modifier.align(Alignment.Center),
            )
            PdfPhase.Opening -> LoadingMessage(stringResource(R.string.pdf_opening), Modifier.align(Alignment.Center))
            is PdfPhase.Failed -> PdfErrorCard(phase, actions.onRetry, Modifier.align(Alignment.Center))
            is PdfPhase.Password, PdfPhase.Ready -> {}
        }

        AnimatedVisibility(
            visible = state.chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            PdfTopBar(state, actions)
        }
        AnimatedVisibility(
            visible = state.chromeVisible && state.ready,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            PdfBottomBar(state, actions)
        }
        SnackbarHost(
            snackbars,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (state.chromeVisible) 140.dp else 16.dp),
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

@Composable
private fun LoadingMessage(text: String, modifier: Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LoadingState()
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = OttershelfTheme.colors.mutedForeground)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PdfTopBar(state: PdfUiState, actions: PdfActions) {
    val colors = OttershelfTheme.colors
    val open = state.ready
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
            BarIcon("TableOfContents", stringResource(R.string.pdf_contents), open, actions.onContents)
            if (state.searchable) BarIcon("Search", stringResource(R.string.pdf_search), open, actions.onSearch)
            BarIcon("Settings2", stringResource(R.string.pdf_settings), enabled = true, actions.onSettings)
        }
        HorizontalDivider(thickness = 1.dp, color = colors.border)
    }
}

@Composable
private fun BarIcon(icon: String, label: String, enabled: Boolean, onClick: () -> Unit, tint: Color? = null) {
    val colors = OttershelfTheme.colors
    IconButton(onClick = onClick, enabled = enabled) {
        LucideIcon(icon, contentDescription = label, tint = if (!enabled) colors.mutedForeground else tint ?: colors.foreground, size = 22.dp)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PdfBottomBar(state: PdfUiState, actions: PdfActions) {
    val colors = OttershelfTheme.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    val last = (state.pageCount - 1).coerceAtLeast(1)
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
            if (state.search.active) SearchStrip(state.search, actions)
            val chapter = state.outline.getOrNull(state.outlineIndex)?.title
            if (!chapter.isNullOrEmpty()) {
                Text(
                    text = chapter,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Slider(
                value = dragging ?: (state.page - 1).toFloat().coerceIn(0f, last.toFloat()),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { actions.onSeek(it.roundToInt() + 1) }
                    dragging = null
                },
                valueRange = 0f..last.toFloat(),
                enabled = state.pageCount > 1,
                modifier = Modifier.fillMaxWidth().height(36.dp),
                colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary, inactiveTrackColor = colors.muted),
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
            val page = dragging?.let { it.roundToInt() + 1 } ?: state.page
            Text(
                text = stringResource(R.string.pdf_position_page, page, state.pageCount),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = colors.mutedForeground,
                maxLines = 1,
            )
        }
    }
}

/** The search's matches while it's on: the query, "3 of 27", previous, next, clear. */
@Composable
private fun SearchStrip(search: PdfSearch, actions: PdfActions) {
    val colors = OttershelfTheme.colors
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LucideIcon("Search", contentDescription = null, tint = colors.mutedForeground, size = 16.dp)
        Text(
            text = "“${search.query}”",
            modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = when {
                search.hits.isEmpty() && search.running -> stringResource(R.string.pdf_search_searching_short)
                search.hits.isEmpty() -> stringResource(R.string.pdf_search_none)
                search.selected >= 0 -> stringResource(R.string.pdf_search_position, search.selected + 1, search.hits.size)
                else -> pluralStringResource(R.plurals.pdf_search_results, search.hits.size, search.hits.size)
            },
            modifier = Modifier.padding(start = 8.dp).weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = colors.mutedForeground,
            maxLines = 1,
        )
        BarIcon("ChevronUp", stringResource(R.string.pdf_search_previous), search.hits.isNotEmpty(), actions.onPreviousHit)
        BarIcon("ChevronDown", stringResource(R.string.pdf_search_next), search.hits.isNotEmpty(), actions.onNextHit)
        BarIcon("X", stringResource(R.string.pdf_search_clear), enabled = true, actions.onClearSearch)
    }
}

@Composable
private fun PdfErrorCard(error: PdfPhase.Failed, onRetry: () -> Unit, modifier: Modifier) {
    val colors = OttershelfTheme.colors
    Surface(
        modifier = modifier.padding(24.dp).widthIn(max = 420.dp),
        color = colors.card,
        shape = RoundedCornerShape(OttershelfTheme.radii.lg),
        border = BorderStroke(1.dp, colors.border),
    ) {
        if (error.offline) ErrorState(onRetry = onRetry, message = stringResource(R.string.pdf_offline_not_downloaded))
        else if (error.locked) ErrorState(onRetry = onRetry, message = stringResource(R.string.pdf_locked_unsupported))
        else ErrorState(onRetry = onRetry, message = stringResource(R.string.pdf_open_failed), detail = error.message)
    }
}

// --- sheets --------------------------------------------------------------------------------------

@Composable
private fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
        color = OttershelfTheme.colors.foreground,
    )
}

/**
 * Contents: the document's outline (the current entry marked like a selected drawer row, its page on
 * the right), and page thumbnails; only the thumbnails when the document has no outline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfContentsSheet(state: PdfUiState, onPage: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        modifier = Modifier.belowStatusBar(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OttershelfTheme.colors.card,
    ) {
        PdfContentsContent(state, onPage, Modifier.fillMaxWidth().heightIn(max = 640.dp))
    }
}

@Composable
fun PdfContentsContent(state: PdfUiState, onPage: (Int) -> Unit, modifier: Modifier = Modifier) {
    val hasOutline = state.outline.isNotEmpty()
    var tab by rememberSaveable { mutableStateOf(if (hasOutline) 0 else 1) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            SheetTitle(stringResource(R.string.pdf_contents), Modifier.weight(1f))
            if (hasOutline) {
                Segmented(
                    listOf(stringResource(R.string.pdf_outline), stringResource(R.string.pdf_pages)),
                    tab,
                    Modifier.padding(bottom = 8.dp),
                ) { tab = it }
            }
        }
        if (hasOutline && tab == 0) OutlineList(state, onPage) else ThumbnailGrid(state, onPage)
    }
}

@Composable
private fun OutlineList(state: PdfUiState, onPage: (Int) -> Unit) {
    val colors = OttershelfTheme.colors
    val current = state.outlineIndex
    LazyColumn(
        state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0)),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        itemsIndexed(state.outline) { index, entry ->
            val selected = index == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(OttershelfTheme.radii.md))
                    .background(if (selected) colors.accentTint else Color.Transparent)
                    .clickable(role = Role.Button) { onPage(entry.page + 1) }
                    .padding(start = 12.dp + 16.dp * entry.depth.coerceAtMost(4), end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.title.ifEmpty { "-" },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                    color = if (selected) colors.primary else if (entry.depth > 0) colors.mutedForeground else colors.foreground,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = (entry.page + 1).toString(),
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = if (selected) colors.primary else colors.mutedForeground,
                )
            }
        }
    }
}

@Composable
private fun ThumbnailGrid(state: PdfUiState, onPage: (Int) -> Unit) {
    val source = state.source ?: return
    val colors = OttershelfTheme.colors
    val current = state.page - 1
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        state = rememberLazyGridState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0)),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.pageCount, key = { it }) { index ->
            val selected = index == current
            Column(
                Modifier
                    .clip(RoundedCornerShape(OttershelfTheme.radii.sm))
                    .clickable(role = Role.Button) { onPage(index + 1) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio((state.sizes.width(index) / state.sizes.height(index)).coerceIn(0.2f, 5f))
                        .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.border, RoundedCornerShape(OttershelfTheme.radii.sm))
                        .padding(if (selected) 2.dp else 1.dp),
                ) {
                    val w = constraints.maxWidth.coerceAtLeast(1)
                    val h = constraints.maxHeight.coerceAtLeast(1)
                    val bitmap = rememberPageBitmap(source, index, IntSize(w, h))
                    val night = state.view.night
                    Canvas(Modifier.fillMaxSize()) {
                        if (bitmap != null) {
                            drawImage(
                                bitmap,
                                dstOffset = IntOffset.Zero,
                                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                                colorFilter = if (night) NightFilter else null,
                                filterQuality = FilterQuality.Low,
                            )
                        } else drawRect(if (night) NightPaperColor else PaperColor)
                    }
                }
                Text(
                    text = (index + 1).toString(),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = if (selected) colors.primary else colors.mutedForeground,
                )
            }
        }
    }
}

/** Search: the field, then the matches with their page and context; a tap shows one and closes the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfSearchSheet(search: PdfSearch, pageCount: Int, onSearch: (String) -> Unit, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        modifier = Modifier.belowStatusBar(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OttershelfTheme.colors.card,
    ) {
        PdfSearchContent(search, pageCount, onSearch, onPick, Modifier.fillMaxWidth().heightIn(max = 640.dp))
    }
}

@Composable
fun PdfSearchContent(search: PdfSearch, pageCount: Int, onSearch: (String) -> Unit, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    var text by rememberSaveable { mutableStateOf(search.query) }
    val focus = remember { FocusRequester() }
    Column(modifier) {
        SheetTitle(stringResource(R.string.pdf_search))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).focusRequester(focus),
            placeholder = { Text(stringResource(R.string.pdf_search_hint)) },
            leadingIcon = { LucideIcon("Search", contentDescription = null, tint = colors.mutedForeground, size = 18.dp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(text) }),
            shape = RoundedCornerShape(OttershelfTheme.radii.md),
        )
        val status = when {
            !search.active -> null
            search.running -> stringResource(R.string.pdf_search_searching, search.searched.coerceAtLeast(1), pageCount)
            search.hits.isEmpty() -> stringResource(R.string.pdf_search_none)
            search.capped -> stringResource(R.string.pdf_search_capped, search.hits.size)
            else -> pluralStringResource(R.plurals.pdf_search_results, search.hits.size, search.hits.size)
        }
        if (status != null) {
            Text(
                status,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = colors.mutedForeground,
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            itemsIndexed(search.hits) { index, hit ->
                val selected = index == search.selected
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(OttershelfTheme.radii.md))
                        .background(if (selected) colors.accentTint else Color.Transparent)
                        .clickable(role = Role.Button) { onPick(index) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        stringResource(R.string.pdf_page_number, hit.page + 1),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        color = if (selected) colors.primary else colors.mutedForeground,
                    )
                    Text(
                        text = buildAnnotatedString {
                            val start = hit.matchStart.coerceIn(0, hit.snippet.length)
                            val end = (hit.matchStart + hit.matchLength).coerceIn(start, hit.snippet.length)
                            append(hit.snippet.substring(0, start))
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.primary)) { append(hit.snippet.substring(start, end)) }
                            append(hit.snippet.substring(end))
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = colors.foreground,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    if (!LocalInspectionMode.current && !search.active) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

// --- settings ------------------------------------------------------------------------------------

class PdfSettingsActions(
    val onScroll: (PdfScroll) -> Unit = {},
    val onFit: (PdfFit) -> Unit = {},
    val onNight: (Boolean) -> Unit = {},
    val onReset: () -> Unit = {},
    val onUseForAll: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfSettingsSheet(view: PdfView, customized: Boolean, actions: PdfSettingsActions, onDismiss: () -> Unit) {
    ModalBottomSheet(
        modifier = Modifier.belowStatusBar(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OttershelfTheme.colors.card,
    ) {
        PdfSettingsContent(view, customized, actions, Modifier.verticalScroll(rememberScrollState()))
    }
}

/** Layout, zoom and night mode, applied as they change; this book's own settings can be reset or made the default. */
@Composable
fun PdfSettingsContent(view: PdfView, customized: Boolean, actions: PdfSettingsActions, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    Column(modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        SheetTitle(stringResource(R.string.pdf_settings))
        SettingRow(stringResource(R.string.pdf_layout)) {
            Segmented(
                listOf(stringResource(R.string.pdf_layout_continuous), stringResource(R.string.pdf_layout_single)),
                if (view.scroll == PdfScroll.Continuous) 0 else 1,
            ) { actions.onScroll(if (it == 0) PdfScroll.Continuous else PdfScroll.Paged) }
        }
        SettingRow(stringResource(R.string.pdf_zoom)) {
            Segmented(
                listOf(stringResource(R.string.pdf_fit_width), stringResource(R.string.pdf_fit_page)),
                if (view.fit == PdfFit.Width) 0 else 1,
            ) { actions.onFit(if (it == 0) PdfFit.Width else PdfFit.Page) }
        }
        SettingRow(stringResource(R.string.pdf_night), stringResource(R.string.pdf_night_detail)) {
            Switch(checked = view.night, onCheckedChange = actions.onNight)
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), thickness = 1.dp, color = colors.border)
        Text(
            text = stringResource(if (customized) R.string.pdf_settings_customized else R.string.pdf_settings_default),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = colors.mutedForeground,
        )
        if (customized) {
            Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = actions.onReset) { Text(stringResource(R.string.pdf_settings_reset)) }
                TextButton(onClick = actions.onUseForAll) { Text(stringResource(R.string.pdf_settings_use_for_all)) }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, detail: String? = null, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), color = OttershelfTheme.colors.foreground)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = OttershelfTheme.colors.mutedForeground)
            }
        }
        control()
    }
}

/** The web's segmented tabs: a muted track, the selected option raised on the card colour (as the EPUB reader's). */
@Composable
private fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val colors = OttershelfTheme.colors
    val radii = OttershelfTheme.radii
    Row(modifier.clip(RoundedCornerShape(radii.md)).background(colors.muted).padding(3.dp)) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(radii.sm))
                    .background(if (isSelected) colors.card else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    color = if (isSelected) colors.foreground else colors.mutedForeground,
                    maxLines = 1,
                )
            }
        }
    }
}

// --- password ----------------------------------------------------------------------------------

/** The encrypted document's password; [wrong] after one that didn't open it. Cancel leaves the reader. */
@Composable
fun PdfPasswordDialog(wrong: Boolean, onOpen: (String) -> Unit, onCancel: () -> Unit) {
    val colors = OttershelfTheme.colors
    var password by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = colors.card,
        icon = { LucideIcon("Lock", contentDescription = null, tint = colors.primary, size = 24.dp) },
        title = { Text(stringResource(R.string.pdf_password_title)) },
        text = {
            Column {
                Text(stringResource(R.string.pdf_password_message))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    label = { Text(stringResource(R.string.pdf_password_label)) },
                    singleLine = true,
                    isError = wrong,
                    supportingText = if (wrong) ({ Text(stringResource(R.string.pdf_password_wrong)) }) else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (password.isNotEmpty()) onOpen(password) }),
                    shape = RoundedCornerShape(OttershelfTheme.radii.md),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onOpen(password) }, enabled = password.isNotEmpty()) { Text(stringResource(R.string.pdf_password_open)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.pdf_cancel)) }
        },
    )
    if (!LocalInspectionMode.current) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}
