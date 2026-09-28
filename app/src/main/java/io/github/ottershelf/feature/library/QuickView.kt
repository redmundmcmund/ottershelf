package io.github.ottershelf.feature.library

import android.text.format.Formatter
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ottershelf.R
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.model.formatSeriesIndex
import io.github.ottershelf.feature.book.DownloadAction
import io.github.ottershelf.feature.book.DownloadButton
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.CoverProgressBar
import io.github.ottershelf.ui.components.PillProgressBar
import io.github.ottershelf.ui.components.SkeletonBox
import io.github.ottershelf.ui.components.StatusIcon
import io.github.ottershelf.ui.components.rememberNotificationPermissionRequest
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.theme.OttershelfTheme
import kotlin.math.roundToInt

private val QUICK_COVER_WIDTH = 104.dp
private const val DESCRIPTION_LINES = 4

/**
 * The quick view of a long-pressed cover, as a bottom sheet while [viewModel] holds a book: Read
 * (or Continue) in the reader the book page would open, the book page, the download and the read
 * status, each as the book page does it (the download asks the same questions; a new status shows
 * at once and is put back if the server refuses).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookQuickViewSheet(viewModel: BookQuickViewModel, navigator: AppNavigator) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The download's progress notification (with Cancel) and its result need it, as on the book page.
    val askNotifications = rememberNotificationPermissionRequest()
    var dialog by remember { mutableStateOf<DownloadAction?>(null) }
    val current = state
    LaunchedEffect(current == null) { if (current == null) dialog = null }
    if (current != null) {
        ModalBottomSheet(
            onDismissRequest = viewModel::close,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = OttershelfTheme.colors.card,
        ) {
            BookQuickViewContent(
                state = current,
                onRead = {
                    viewModel.readRoute()?.let { route ->
                        viewModel.close()
                        navigator.navigate(route)
                    }
                },
                onDetails = {
                    viewModel.opening()
                    viewModel.close()
                    navigator.navigate(Route.BookDetail(current.bookId))
                },
                onDownload = {
                    when (val action = current.page.downloadAction) {
                        DownloadAction.Start -> {
                            askNotifications()
                            viewModel.startDownload()
                        }
                        null -> Unit
                        else -> dialog = action
                    }
                },
                onStatus = viewModel::setStatus,
                onRetry = viewModel::retry,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            )
        }
    }
    dialog?.let { action ->
        QuickDownloadDialog(
            action = action,
            sizeBytes = current?.page?.downloaded?.sizeBytes ?: 0,
            onDismiss = { dialog = null },
            onStop = viewModel::cancelDownload,
            onDownload = {
                askNotifications()
                viewModel.startDownload()
            },
            onRemove = viewModel::removeDownload,
        )
    }
}

/**
 * The quick view's content (stateless, screenshot-tested): the cover and what the card knows
 * (title, authors, series, the user's rating, progress), a few lines of the description once the book is
 * in, then Read or Continue and the book page, the status (tap for the list of statuses) and the
 * download. [statusesOpen] starts with the statuses listed (screenshots).
 */
@Composable
fun BookQuickViewContent(
    state: QuickViewUiState,
    modifier: Modifier = Modifier,
    onRead: () -> Unit = {},
    onDetails: () -> Unit = {},
    onDownload: () -> Unit = {},
    onStatus: (ReadStatus) -> Unit = {},
    onRetry: () -> Unit = {},
    statusesOpen: Boolean = false,
) {
    val colors = OttershelfTheme.colors
    var picking by rememberSaveable(state.bookId) { mutableStateOf(statusesOpen) }
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .animateContentSize()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
    ) {
        Header(state)
        Description(state, onRetry)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.page.canRead || state.loading) {
                AccentButton(
                    text = readLabel(state),
                    onClick = onRead,
                    icon = "BookOpen",
                    enabled = state.page.canRead,
                    modifier = Modifier.weight(1.4f).height(48.dp),
                )
            }
            QuickAction(
                text = stringResource(R.string.library_quick_details),
                onClick = onDetails,
                modifier = Modifier.weight(1f),
                leading = { LucideIcon("Info", contentDescription = null, tint = colors.foreground, size = 20.dp) },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val status = state.status
            QuickAction(
                text = status.label,
                onClick = { picking = !picking },
                enabled = !state.page.statusBusy,
                modifier = Modifier.weight(1f),
                leading = { StatusIcon(status, size = 20.dp) },
                trailing = { LucideIcon(if (picking) "ChevronUp" else "ChevronDown", contentDescription = null, tint = colors.mutedForeground, size = 16.dp, fallback = null) },
            )
            val download = state.page.downloadButton
            if (download != DownloadButton.Hidden) DownloadQuickAction(download, onDownload, Modifier.weight(1f))
        }
        state.statusError?.let {
            Text(
                stringResource(R.string.library_quick_status_failed, it),
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.destructive,
            )
        }
        if (picking) {
            StatusList(
                current = state.status,
                onPick = {
                    picking = false
                    onStatus(it)
                },
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun readLabel(state: QuickViewUiState): String {
    val format = state.page.readLabelFormat
    val percent = state.progress?.roundToInt()?.coerceIn(1, 99)
    return when {
        state.continues && percent != null && format != null -> stringResource(R.string.library_quick_continue_format, format, percent)
        state.continues && percent != null -> stringResource(R.string.library_quick_continue, percent)
        // The status button beside it already reads "Read" for a finished book.
        state.status == ReadStatus.READ && format != null -> stringResource(R.string.library_quick_open_format, format)
        state.status == ReadStatus.READ -> stringResource(R.string.library_quick_open)
        format != null -> stringResource(R.string.library_quick_read_format, format)
        else -> stringResource(R.string.library_quick_read)
    }
}

/** The cover, title, authors, series, the user's rating and the progress. */
@Composable
private fun Header(state: QuickViewUiState) {
    val colors = OttershelfTheme.colors
    val authors = state.authors.joinToString(", ")
    val progress = ((state.progress ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    val read = state.status == ReadStatus.READ
    Row {
        BookCover(
            model = state.cover,
            title = state.title,
            authors = authors,
            seed = state.card.title ?: state.bookId.toString(),
            modifier = Modifier.width(QUICK_COVER_WIDTH),
            shape = RoundedCornerShape(OttershelfTheme.radii.md),
            requestWidth = QUICK_COVER_WIDTH,
        ) {
            CoverProgressBar(progress, read = read, modifier = Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = state.title ?: stringResource(R.string.components_untitled),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
                color = colors.foreground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (authors.isNotEmpty()) {
                Text(
                    text = authors,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    color = colors.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            state.seriesName?.takeIf { it.isNotBlank() }?.let { name ->
                Text(
                    text = state.seriesIndex?.let { stringResource(R.string.library_row_series, name, formatSeriesIndex(it)) } ?: name,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .background(colors.muted, CircleShape)
                        .padding(start = 10.dp, top = 3.dp, end = 10.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            state.rating?.takeIf { it in 1..5 }?.let {
                RatingStars(it, Modifier.padding(top = 10.dp), size = 16)
            }
            if (progress > 0f) {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillProgressBar(progress, Modifier.weight(1f), color = if (read) colors.coverProgressRead else colors.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.library_row_percent, (progress * 100).roundToInt()),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
    }
}

/** A few lines of the description once the book is in; placeholders while it loads; Retry if it can't. */
@Composable
private fun Description(state: QuickViewUiState, onRetry: () -> Unit) {
    val colors = OttershelfTheme.colors
    when {
        state.loadFailed -> Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.library_quick_load_failed),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.mutedForeground,
            )
            Text(
                stringResource(R.string.library_quick_retry),
                Modifier
                    .clip(RoundedCornerShape(OttershelfTheme.radii.sm))
                    .clickable(role = Role.Button, onClick = onRetry)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
            )
        }
        state.loading -> Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBox(Modifier.fillMaxWidth().height(12.dp))
            SkeletonBox(Modifier.fillMaxWidth(0.92f).height(12.dp))
            SkeletonBox(Modifier.fillMaxWidth(0.6f).height(12.dp))
        }
        else -> {
            val html = state.description ?: return
            val text = remember(html) { plainText(html) }
            if (text.isEmpty()) return
            Text(
                text = text,
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
                color = colors.foreground.copy(alpha = 0.85f),
                maxLines = DESCRIPTION_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The server's HTML description as plain text in one paragraph run (the sheet shows only a few lines). */
internal fun plainText(html: String): String =
    AnnotatedString.fromHtml(html).text.replace(Regex("\\s+"), " ").trim()

/** Each status with its icon, a check on the current one (the book page's picker, in the sheet). */
@Composable
private fun StatusList(current: ReadStatus, onPick: (ReadStatus) -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.lg)
    Column(modifier.fillMaxWidth().clip(shape).background(colors.card).border(1.dp, colors.border, shape)) {
        ReadStatus.entries.forEachIndexed { index, status ->
            if (index > 0) HorizontalDivider(color = colors.border)
            val selected = status == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .background(if (selected) colors.accentTint else colors.card)
                    .clickable(role = Role.RadioButton) { onPick(status) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusIcon(status, size = 20.dp)
                Spacer(Modifier.width(14.dp))
                Text(
                    status.label,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    color = if (selected) colors.primary else colors.foreground,
                )
                if (selected) LucideIcon("Check", contentDescription = null, tint = colors.primary, size = 18.dp)
            }
        }
    }
}

@Composable
private fun DownloadQuickAction(button: DownloadButton, onClick: () -> Unit, modifier: Modifier) {
    val colors = OttershelfTheme.colors
    val text = when (button) {
        is DownloadButton.Downloading -> when {
            button.waiting -> stringResource(R.string.library_quick_download_waiting)
            button.percent != null -> stringResource(R.string.library_quick_downloading_percent, button.percent)
            else -> stringResource(R.string.library_quick_downloading)
        }
        DownloadButton.Downloaded -> stringResource(R.string.library_quick_downloaded)
        DownloadButton.Update -> stringResource(R.string.library_quick_update)
        else -> stringResource(R.string.library_quick_download)
    }
    QuickAction(
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
                    LinearProgressIndicator(modifier = bar, color = colors.primary, trackColor = Color.Transparent, strokeCap = StrokeCap.Butt, gapSize = 0.dp)
                }
            }
        },
    )
}

/**
 * The book page's secondary button (the Nexus `DetailAction`): 48dp, card fill with a 1dp border
 * and the md radius, icon and 15sp label at the start, dim while disabled; [bottom] along its foot.
 */
@Composable
private fun QuickAction(
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
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            leading()
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
                color = if (enabled) colors.foreground else colors.mutedForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailing?.invoke()
        }
        bottom?.invoke(this)
    }
}

/** The questions the download button asks, as on the book page. */
@Composable
private fun QuickDownloadDialog(
    action: DownloadAction,
    sizeBytes: Long,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = OttershelfTheme.colors
    val context = LocalContext.current
    fun then(block: () -> Unit): () -> Unit = {
        onDismiss()
        block()
    }
    when (action) {
        DownloadAction.ConfirmStop -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = colors.popover,
            text = { Text(stringResource(R.string.library_quick_stop_question)) },
            confirmButton = { TextButton(onClick = then(onStop)) { Text(stringResource(R.string.library_quick_stop)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_quick_keep_going)) } },
        )
        DownloadAction.ConfirmUpdate -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = colors.popover,
            title = { Text(stringResource(R.string.library_quick_update)) },
            text = { Text(stringResource(R.string.library_quick_update_message)) },
            confirmButton = { TextButton(onClick = then(onDownload)) { Text(stringResource(R.string.library_quick_download)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = then(onRemove)) { Text(stringResource(R.string.library_quick_remove)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_quick_cancel)) }
                }
            },
        )
        DownloadAction.ConfirmRemove -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = colors.popover,
            title = { Text(stringResource(R.string.library_quick_downloaded)) },
            text = { Text(stringResource(R.string.library_quick_downloaded_message, Formatter.formatShortFileSize(context, sizeBytes))) },
            confirmButton = {
                TextButton(onClick = then(onRemove)) { Text(stringResource(R.string.library_quick_remove_download), color = colors.destructive) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_quick_keep)) } },
        )
        DownloadAction.Start -> LaunchedEffect(Unit) { onDismiss() }
    }
}
