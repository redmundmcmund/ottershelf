package io.github.ottershelf.feature.book

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.ottershelf.R
import io.github.ottershelf.core.model.AuthorRef
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.model.formatSeriesIndex
import io.github.ottershelf.feature.notes.BookHighlightsSection
import io.github.ottershelf.feature.quotes.QuoteButtons
import io.github.ottershelf.feature.seriesnext.NextInSeriesCard
import io.github.ottershelf.feature.seriesnext.NextInSeriesRow
import io.github.ottershelf.feature.seriesnext.rememberSeriesNext
import io.github.ottershelf.ui.components.BookCoverPlaceholder
import io.github.ottershelf.ui.components.DetailTopBar
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.FormatChip
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.components.StatusIcon
import io.github.ottershelf.ui.components.rememberNotificationPermissionRequest
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.ReaderRouter
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.util.Locale

/** At this width the actions go beside the cover, as in the Nexus app's tablet layout (phones in landscape too). */
private val WIDE_WIDTH = 600.dp
private val ACTIONS_MAX_WIDTH = 420.dp

/** The page's cover box. */
private val COVER_WIDTH = 160.dp
private val COVER_HEIGHT = 240.dp

/**
 * The thumbnail is sharp enough for the cover box when its height is at least this share of the
 * box's height in pixels (Crop blows it up by at most 1.25x).
 */
private const val SHARP_ENOUGH = 0.8f

/**
 * Which cover the page draws, remembered per [BookDetailUiState.thumb] (see [rememberDetailCover]):
 * the grid's thumbnail (at most 400x600 and usually cached already), and the full cover
 * ([BookDetailUiState.cover], `books/:id/cover`: the original file, often megabytes) only when the
 * thumbnail isn't enough: there is none, it failed, or it is too small for the box ([thumbTooSmall]:
 * a square or landscape cover's, which Crop would blow up, or a phone at a high display density).
 * The page and the finish celebration share it, so both draw the same one.
 */
@Stable
class DetailCover internal constructor() {
    /** The thumbnail couldn't be loaded. */
    var thumbFailed by mutableStateOf(false)
        internal set

    /** The thumbnail came, too small for the cover box. */
    var thumbTooSmall by mutableStateOf(false)
        internal set

    /** Whether the full cover is worth fetching for [state] (the thumbnail isn't enough). */
    fun wantsFull(state: BookDetailUiState): Boolean = state.thumb == null || thumbFailed || thumbTooSmall

    /** The one cover to draw where only one fits (the finish celebration). */
    fun model(state: BookDetailUiState): Any? = if (wantsFull(state)) state.cover ?: state.thumb else state.thumb
}

/** A [DetailCover] for [thumb]: a new thumbnail (an edited cover) is judged afresh. */
@Composable
fun rememberDetailCover(thumb: Any?): DetailCover = remember(thumb) { DetailCover() }

/**
 * Whether a thumbnail [heightPx] tall (as decoded: Coil never scales it up in an AsyncImage) is too
 * small for a cover box [boxHeightPx] tall. A 2:3 thumbnail (600 px) fits the page's 240 dp box at
 * up to 3.125x (common phones are 2.625x to 3x); a square one (400 px) doesn't at 2.625x.
 */
internal fun thumbTooSmall(heightPx: Float, boxHeightPx: Float): Boolean =
    heightPx.isFinite() && heightPx < SHARP_ENOUGH * boxHeightPx

/**
 * Tells [freshness] whether the page shows, so the page asks again only if something was sent
 * meanwhile, the app was stopped, or it's old. Navigation 3 composes the entry again on every return
 * from a screen on top, and a covered entry leaves composition, which pauses it without a stop; only
 * the app really stopping while the page shows sends ON_STOP (`BookDetailShowingTest`).
 */
@Composable
internal fun FollowShowing(freshness: PageFreshness) {
    LifecycleResumeEffect(freshness) {
        freshness.resumed()
        onPauseOrDispose { freshness.paused() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { freshness.stopped() }
}

/**
 * A book's page (Nexus BookDetailActivity, activity_book_detail.xml): cover on a wash of its
 * colour, title, authors, series, details; Read, the read status and the download; the
 * description and the files.
 */
@Composable
fun BookDetailScreen(route: Route.BookDetail, navigator: AppNavigator) {
    val viewModel = appViewModel { BookDetailViewModel(it, route.bookId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tracking by viewModel.tracker.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                is BookDetailMessage.StatusFailed ->
                    snackbar.showSnackbar(resources.getString(R.string.book_status_failed, message.error), duration = SnackbarDuration.Long)
            }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.tracker.messages.collect { message ->
            val text = if (message.arg != null) resources.getString(message.text, message.arg) else resources.getString(message.text)
            snackbar.showSnackbar(text, duration = if (message.arg != null) SnackbarDuration.Long else SnackbarDuration.Short)
        }
    }
    val trackingActions = remember(viewModel) {
        object : BookTrackingActions by viewModel.tracker {
            override fun startTimer() = navigator.navigate(Route.Timer(route.bookId))
        }
    }
    FollowShowing(viewModel.freshness)
    val tint = rememberCoverTint(state.thumb, state.cover)
    val cover = rememberDetailCover(state.thumb)
    // The next book in the series, once the user has finished this one (feature.seriesnext).
    val seriesNext = rememberSeriesNext(route.bookId, state.book, state.status, finishing = tracking.celebration != null)
    val askNotifications = rememberNotificationPermissionRequest()
    var dialog by rememberSaveable { mutableStateOf<DownloadAction?>(null) }
    val start = {
        askNotifications() // the progress notification (with Cancel) and the result need it
        viewModel.startDownload()
    }

    BookDetailContent(
        state = state,
        tint = tint,
        cover = cover,
        snackbar = snackbar,
        onBack = { navigator.back() },
        onRead = {
            viewModel.readTarget()?.let { target ->
                ReaderRouter.route(state.bookId, target.fileId, target.format, state.title.orEmpty())?.let(navigator::navigate)
            }
        },
        onPickStatus = viewModel::setStatus,
        onDownload = {
            when (val action = state.downloadAction) {
                DownloadAction.Start -> start()
                null -> Unit
                else -> dialog = action
            }
        },
        onAuthor = { author -> author.id?.let { navigator.navigate(Route.BookList("author:$it", author.name)) } },
        onSeries = { book ->
            val id = book.seriesId
            val name = book.seriesName
            if (id != null && name != null) navigator.navigate(Route.BookList("series:$id", name))
        },
        onRetry = viewModel::load,
        onEdit = { navigator.navigate(Route.BookEdit(route.bookId)) },
        tracking = tracking,
        trackingActions = trackingActions,
        highlights = {
            BookHighlightsSection(route.bookId, onOpen = { navigator.navigate(Route.BookHighlights(route.bookId, state.title.orEmpty())) })
            QuoteButtons(route.bookId, state.title, navigator, Modifier.padding(top = 10.dp))
        },
        seriesNext = { modifier ->
            NextInSeriesRow(
                state = seriesNext.state,
                onRead = { seriesNext.readRoute()?.let(navigator::navigate) },
                onDetails = { seriesNext.detailsRoute()?.let(navigator::navigate) },
                modifier = modifier,
            )
        },
    )

    tracking.celebration?.let { step ->
        Dialog(
            onDismissRequest = { if (step is CelebrationStep.Achievement) trackingActions.achievementSeen() else trackingActions.celebrationSkip() },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            FinishCelebrationContent(step, state.title, cover.model(state), tracking, trackingActions) {
                // Opening the next book ends the flow here (the page would show it again on return),
                // and an achievement it brings goes to the shell's celebration, not to this page off screen.
                fun open(route: Route?) {
                    trackingActions.celebrationLeave()
                    route?.let(navigator::navigate)
                }
                NextInSeriesCard(seriesNext.state, onRead = { open(seriesNext.readRoute()) }, onDetails = { open(seriesNext.detailsRoute()) })
            }
        }
    }

    dialog?.let { action ->
        DownloadDialog(
            action = action,
            sizeBytes = state.downloaded?.sizeBytes ?: 0,
            onDismiss = { dialog = null },
            onStop = viewModel::cancelDownload,
            onDownload = start,
            onRemove = viewModel::removeDownload,
        )
    }
}

/**
 * Stateless: everything the page shows comes from [state]; [tint] is the cover's colour, if any;
 * [cover] which of the thumbnail and the full cover it draws (shared with the finish celebration).
 */
@Composable
fun BookDetailContent(
    state: BookDetailUiState,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    cover: DetailCover = rememberDetailCover(state.thumb),
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit = {},
    onRead: () -> Unit = {},
    onPickStatus: (ReadStatus) -> Unit = {},
    onDownload: () -> Unit = {},
    onAuthor: (AuthorRef) -> Unit = {},
    onSeries: (BookDetail) -> Unit = {},
    onRetry: () -> Unit = {},
    /** Edit details (feature.bookedit): the toolbar's pencil, shown when [BookDetailUiState.canEdit]. */
    onEdit: () -> Unit = {},
    tracking: BookTrackingUiState? = null,
    trackingActions: BookTrackingActions = BookTrackingActions.None,
    /** The Highlights & notes card (feature.notes), under the files. */
    highlights: @Composable () -> Unit = {},
    /** The next book in the series (feature.seriesnext), under the Read and status buttons; it applies the modifier. */
    seriesNext: @Composable (Modifier) -> Unit = {},
    // Today where the server splits days (Day N counts from a status date on the server's calendar).
    today: java.time.LocalDate = tracking?.let { java.time.LocalDate.now(it.zone) } ?: java.time.LocalDate.now(),
) {
    var trackingDialog by rememberSaveable(stateSaver = TrackingDialogSaver) { mutableStateOf<TrackingDialog?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val direction = LocalLayoutDirection.current
    Scaffold(
        modifier = modifier,
        topBar = {
            DetailTopBar(
                title = "",
                subtitle = if (state.offlineCopy) stringResource(R.string.book_offline_copy) else null,
                onBack = onBack,
                actions = {
                    if (state.canEdit && state.book != null) {
                        IconButton(onClick = onEdit) {
                            LucideIcon("Pencil", contentDescription = stringResource(R.string.bookedit_edit_details), size = 22.dp)
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            val wide = maxWidth >= WIDE_WIDTH
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                TintWash(tint)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 20.dp + padding.calculateStartPadding(direction),
                            end = 20.dp + padding.calculateEndPadding(direction),
                            top = 20.dp,
                            bottom = 20.dp + padding.calculateBottomPadding(),
                        ),
                ) {
                    Row {
                        DetailCoverBox(state, cover, Modifier.size(COVER_WIDTH, COVER_HEIGHT))
                        Spacer(Modifier.width(20.dp))
                        Column(Modifier.weight(1f)) {
                            Header(state, onAuthor, onSeries)
                            if (wide && state.book != null) {
                                Actions(
                                    state = state,
                                    onRead = onRead,
                                    onStatus = { picking = true },
                                    onDownload = onDownload,
                                    modifier = Modifier.padding(top = 16.dp).widthIn(max = ACTIONS_MAX_WIDTH).fillMaxWidth(),
                                )
                                seriesNext(Modifier.padding(top = 12.dp).widthIn(max = ACTIONS_MAX_WIDTH).fillMaxWidth())
                            }
                        }
                    }
                    if (!wide && state.book != null) {
                        Actions(
                            state = state,
                            onRead = onRead,
                            onStatus = { picking = true },
                            onDownload = onDownload,
                            modifier = Modifier.padding(top = 20.dp).fillMaxWidth(),
                        )
                        seriesNext(Modifier.padding(top = 12.dp).fillMaxWidth())
                    }
                    val book = state.book
                    if (book != null && tracking != null) {
                        TrackingProgressCard(book, state.status, tracking, trackingActions, { trackingDialog = it }, Modifier.padding(top = 20.dp), today)
                    }
                    if (book != null) {
                        Description(book)
                        Files(book)
                        highlights()
                    }
                    if (book != null && tracking != null) {
                        RatingReviewCard(tracking, trackingActions, Modifier.padding(top = 24.dp))
                        ReadingLogCard(book, tracking, trackingActions, { trackingDialog = it }, Modifier.padding(top = 16.dp))
                    }
                    if (state.error != null && state.book == null) {
                        ErrorState(
                            onRetry = onRetry,
                            message = stringResource(R.string.book_load_failed),
                            detail = state.error.takeIf { it.isNotBlank() },
                            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                        )
                    }
                    if (state.loading) LoadingState(Modifier.fillMaxWidth().padding(top = 24.dp))
                }
            }
        }
    }

    val dialogBook = state.book
    if (dialogBook != null && tracking != null) {
        TrackingDialogs(trackingDialog, dialogBook, state.status, tracking, trackingActions) { trackingDialog = it }
    }

    if (picking) {
        StatusPickerDialog(
            current = ReadStatus.of(state.status) ?: ReadStatus.UNREAD,
            onPick = {
                picking = false
                onPickStatus(it)
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * The web's cover tint: the cover's dominant colour ([CoverTint]) as a wash fading down behind
 * the header, 380dp tall. No colour, no tint.
 */
@Composable
private fun TintWash(tint: Color?) {
    AnimatedVisibility(visible = tint != null, enter = fadeIn(tween(400)), exit = fadeOut()) {
        val color = tint ?: Color.Transparent
        Box(
            Modifier
                .fillMaxWidth()
                .height(380.dp)
                .background(
                    Brush.verticalGradient(
                        0f to color.copy(alpha = 0x57 / 255f),
                        0.5f to color.copy(alpha = 0x1F / 255f),
                        1f to Color.Transparent,
                    ),
                ),
        )
    }
}

/**
 * The cover, 160x240 with the lg radius: the grid's thumbnail (already cached) underneath, the
 * full cover fading in over it only when [cover] wants it (the thumbnail missing, failed or too
 * small), and the generated cover when there is none.
 */
@Composable
private fun DetailCoverBox(state: BookDetailUiState, cover: DetailCover, modifier: Modifier) {
    val colors = OttershelfTheme.colors
    val boxHeightPx = with(LocalDensity.current) { COVER_HEIGHT.toPx() }
    Box(modifier.clip(RoundedCornerShape(OttershelfTheme.radii.lg)).background(colors.coverSurface)) {
        val thumbFailed = cover.thumbFailed
        var coverFailed by remember(state.cover) { mutableStateOf(false) }
        val none = (state.thumb == null || thumbFailed) && (state.cover == null || coverFailed)
        val known = state.book != null || state.preview != null
        if (none && known && !(state.loading && state.cover == null && state.book?.coverSource != null)) {
            val authors = state.book?.authors?.joinToString(", ") { it.name } ?: state.preview?.authors?.joinToString(", ")
            BookCoverPlaceholder(
                title = state.title,
                authors = authors,
                seed = state.title ?: state.bookId.toString(),
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (state.thumb != null && !thumbFailed) {
            AsyncImage(
                model = state.thumb,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { cover.thumbTooSmall = thumbTooSmall(it.painter.intrinsicSize.height, boxHeightPx) },
                onError = { cover.thumbFailed = true },
            )
        }
        if (state.cover != null && !coverFailed && cover.wantsFull(state)) {
            AsyncImage(
                model = state.cover,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { coverFailed = true },
            )
        }
    }
}

/** Title, subtitle, authors (each opens their books), the series chip and the details. */
@Composable
private fun Header(state: BookDetailUiState, onAuthor: (AuthorRef) -> Unit, onSeries: (BookDetail) -> Unit) {
    val colors = OttershelfTheme.colors
    val book = state.book
    val title = state.title ?: if (book != null) stringResource(R.string.book_untitled) else ""
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
        color = colors.foreground,
    )
    book?.subtitle?.takeIf { it.isNotBlank() }?.let {
        Text(
            text = it,
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.sp),
            color = colors.mutedForeground,
        )
    }

    val authorStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, lineHeight = 21.sp)
    val authors: AnnotatedString = if (book != null) {
        val linkStyles = TextLinkStyles(SpanStyle(color = colors.primary))
        buildAnnotatedString {
            book.authors.forEachIndexed { i, author ->
                if (i > 0) append(", ")
                if (author.id != null) {
                    withLink(LinkAnnotation.Clickable("author-$i", linkStyles) { onAuthor(author) }) { append(author.name) }
                } else {
                    append(author.name)
                }
            }
        }
    } else {
        AnnotatedString((state.preview?.authors ?: state.hint?.authors)?.joinToString(", ").orEmpty())
    }
    if (authors.isNotEmpty()) {
        Text(text = authors, modifier = Modifier.padding(top = 8.dp), style = authorStyle, color = colors.primary)
    }

    book?.seriesName?.let { name ->
        val clickable = book.seriesId != null
        Text(
            text = book.seriesIndex?.let { stringResource(R.string.book_series_with_index, name, formatSeriesIndex(it)) } ?: name,
            modifier = Modifier
                .padding(top = 10.dp)
                .clip(CircleShape)
                .background(colors.muted)
                .then(
                    // (Older offline copies lack the id.) Not a button then: no pressed look.
                    if (clickable) Modifier.clickable(role = Role.Button) { onSeries(book) } else Modifier,
                )
                .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 5.dp),
            style = MaterialTheme.typography.titleSmall,
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (book != null) {
        val pages = book.pageCount?.let { stringResource(R.string.book_pages, it) }
        val meta = listOfNotNull(
            book.publisher,
            book.publishedYear?.toString(),
            pages,
            book.language?.uppercase(Locale.ROOT),
            book.libraryName,
        ).joinToString("\n")
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                color = colors.mutedForeground,
            )
        }
    }
}

/** Read (full width, accent), then the status and download buttons side by side. */
@Composable
private fun Actions(
    state: BookDetailUiState,
    onRead: () -> Unit,
    onStatus: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OttershelfTheme.colors
    Column(modifier) {
        if (state.canRead) {
            Button(
                onClick = onRead,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(OttershelfTheme.radii.md),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                LucideIcon("BookOpen", contentDescription = null, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                val label = state.readLabelFormat?.let { stringResource(R.string.book_read_format, it) } ?: stringResource(R.string.book_read)
                Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp))
            }
        }
        Row(Modifier.padding(top = 8.dp).fillMaxWidth()) {
            val status = ReadStatus.of(state.status) ?: ReadStatus.UNREAD
            DetailAction(
                text = status.label,
                onClick = onStatus,
                enabled = !state.statusBusy,
                modifier = Modifier.weight(1f),
                leading = { StatusIcon(status, size = 20.dp) },
                trailing = { LucideIcon("ChevronDown", contentDescription = null, tint = colors.mutedForeground, size = 16.dp, fallback = null) },
            )
            val download = state.downloadButton
            if (download != DownloadButton.Hidden) {
                Spacer(Modifier.width(8.dp))
                DownloadAction(download, onDownload, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DownloadAction(button: DownloadButton, onClick: () -> Unit, modifier: Modifier) {
    val colors = OttershelfTheme.colors
    val text = when (button) {
        is DownloadButton.Downloading -> when {
            button.waiting -> stringResource(R.string.book_download_waiting)
            button.percent != null -> stringResource(R.string.book_downloading_percent, button.percent)
            else -> stringResource(R.string.book_downloading)
        }
        DownloadButton.Downloaded -> stringResource(R.string.book_downloaded)
        DownloadButton.Update -> stringResource(R.string.book_update_download)
        else -> stringResource(R.string.book_download)
    }
    DetailAction(
        text = text,
        onClick = onClick,
        modifier = modifier,
        leading = {
            if (button == DownloadButton.Downloaded) {
                LucideIcon("CircleCheckBig", contentDescription = null, tint = colors.success, size = 20.dp)
            } else {
                LucideIcon("Download", contentDescription = null, tint = colors.foreground, size = 20.dp)
            }
        },
        bottom = {
            if (button is DownloadButton.Downloading) {
                val bar = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)
                val fraction = button.fraction
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = bar,
                        color = colors.primary,
                        trackColor = Color.Transparent,
                        strokeCap = StrokeCap.Butt,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = bar,
                        color = colors.primary,
                        trackColor = Color.Transparent,
                        strokeCap = StrokeCap.Butt,
                        gapSize = 0.dp,
                    )
                }
            }
        },
    )
}

/**
 * The Nexus `DetailAction` button: 48dp, card fill with a 1dp border and the md radius, icon and
 * 15sp label at the start, dim while disabled; [bottom] draws along its bottom edge (progress).
 */
@Composable
internal fun DetailAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: @Composable () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
    bottom: (@Composable BoxScope.() -> Unit)? = null,
) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.md)
    Box(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.border, shape)
            .clickable(enabled = enabled, role = Role.Button, indication = ripple(color = colors.primary), interactionSource = null, onClick = onClick),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            leading()
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.sp),
                color = if (enabled) colors.foreground else colors.mutedForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailing?.invoke()
        }
        bottom?.invoke(this)
    }
}

/** The description (from the server's HTML), cut at 8 lines with Show more / Show less. */
@Composable
private fun Description(book: BookDetail) {
    val colors = OttershelfTheme.colors
    val context = LocalContext.current
    val html = book.description ?: return
    val text = remember(html, colors.primary, context) { htmlText(html, colors.primary) { openDescriptionLink(context, it) } }
    if (text.isEmpty()) return
    var expanded by rememberSaveable(book.id) { mutableStateOf(false) }
    var overflowing by remember(text) { mutableStateOf(false) }
    Text(
        text = text,
        modifier = Modifier.padding(top = 20.dp).animateContentSize(),
        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
        color = colors.foreground,
        maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_LINES,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
    )
    if (overflowing || expanded) {
        Text(
            text = stringResource(if (expanded) R.string.book_show_less else R.string.book_show_more),
            modifier = Modifier
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(OttershelfTheme.radii.sm))
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = colors.primary,
        )
    }
}

private const val DESCRIPTION_LINES = 8

/** [html] as styled text; a tapped link goes to [onLink], never to the default handler (any scheme). */
internal fun htmlText(html: String, link: Color, onLink: (String) -> Unit): AnnotatedString {
    val parsed = AnnotatedString.fromHtml(
        html,
        linkStyles = TextLinkStyles(SpanStyle(color = link)),
        linkInteractionListener = { (it as? LinkAnnotation.Url)?.let { url -> onLink(url.url) } },
    )
    val start = parsed.text.indexOfFirst { !it.isWhitespace() }
    if (start < 0) return AnnotatedString("")
    val end = parsed.text.indexOfLast { !it.isWhitespace() } + 1
    return parsed.subSequence(start, end)
}

/**
 * Opens a link from a book's description in the app that takes it from a browser
 * (CATEGORY_BROWSABLE): web and mail links only. The description is the book file's or a metadata
 * provider's HTML, so any other scheme is ignored: a `file:` link would crash the app, and a
 * `content:` or custom one could reach another app's screens that links from the web can't.
 * False when nothing was opened.
 */
internal fun openDescriptionLink(context: Context, url: String): Boolean {
    val uri = Uri.parse(url.trim()).normalizeScheme()
    if (uri.scheme !in DESCRIPTION_LINK_SCHEMES) return false
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private val DESCRIPTION_LINK_SCHEMES = setOf("http", "https", "mailto")

/** Each file: its format as a chip, then size and file name, dim. */
@Composable
private fun Files(book: BookDetail) {
    if (book.files.isEmpty()) return
    val colors = OttershelfTheme.colors
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        book.files.forEach { file ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                file.format?.let {
                    FormatChip(it)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = listOfNotNull(file.sizeBytes?.let(::formatSize), file.filename).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.mutedForeground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "Set status": each status with its icon, a check on the current one (item_status.xml). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusPickerDialog(current: ReadStatus, onPick: (ReadStatus) -> Unit, onDismiss: () -> Unit) {
    val colors = OttershelfTheme.colors
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = colors.popover, contentColor = colors.foreground) {
            Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
                Text(
                    text = stringResource(R.string.book_set_status),
                    modifier = Modifier.padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(8.dp))
                ReadStatus.entries.forEach { status ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clickable(role = Role.Button) { onPick(status) }
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusIcon(status, size = 24.dp)
                        Spacer(Modifier.width(20.dp))
                        Text(status.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp))
                        if (status == current) LucideIcon("Check", contentDescription = null, tint = colors.primary, size = 20.dp)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.book_cancel)) }
                }
            }
        }
    }
}

/** The questions the download button asks (Nexus onDownloadClicked). */
@Composable
private fun DownloadDialog(
    action: DownloadAction,
    sizeBytes: Long,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = OttershelfTheme.colors
    fun then(block: () -> Unit): () -> Unit = {
        onDismiss()
        block()
    }
    when (action) {
        DownloadAction.ConfirmStop -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = colors.popover,
            text = { Text(stringResource(R.string.book_stop_download_question)) },
            confirmButton = { TextButton(onClick = then(onStop)) { Text(stringResource(R.string.book_stop)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.book_keep_going)) } },
        )
        DownloadAction.ConfirmUpdate -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = colors.popover,
            title = { Text(stringResource(R.string.book_update_download)) },
            text = { Text(stringResource(R.string.book_update_download_message)) },
            confirmButton = { TextButton(onClick = then(onDownload)) { Text(stringResource(R.string.book_download)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = then(onRemove)) { Text(stringResource(R.string.book_remove)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.book_cancel)) }
                }
            },
        )
        DownloadAction.ConfirmRemove -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = colors.popover,
            title = { Text(stringResource(R.string.book_downloaded)) },
            text = { Text(stringResource(R.string.book_downloaded_message, formatSize(sizeBytes))) },
            confirmButton = { TextButton(onClick = then(onRemove)) { Text(stringResource(R.string.book_remove_download)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.book_keep)) } },
        )
        DownloadAction.Start -> onDismiss()
    }
}

/**
 * The cover's colour for the wash: its own small software-bitmap request (the tint reads pixels,
 * and the displayed cover is a hardware bitmap), from the cached grid thumbnail when there is one,
 * else the cover.
 */
@Composable
private fun rememberCoverTint(thumb: Any?, cover: Any?): Color? {
    val context = LocalPlatformContext.current
    var tint by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(thumb, cover) {
        val loader = SingletonImageLoader.get(context)
        for (model in listOfNotNull(thumb, cover)) {
            val request = ImageRequest.Builder(context)
                .data(model)
                .size(64, 96)
                .allowHardware(false)
                .memoryCacheKey("tint:$model")
                .build()
            val bitmap = (loader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: continue
            tint = withContext(Dispatchers.Default) { CoverTint.of(bitmap) }?.let { Color(it) }
            break
        }
    }
    return tint
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}
