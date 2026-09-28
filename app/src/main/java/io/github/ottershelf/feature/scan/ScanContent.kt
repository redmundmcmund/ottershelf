package io.github.ottershelf.feature.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.core.model.MetadataCandidate
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.CardRow
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.DetailTopBar
import io.github.ottershelf.ui.components.FormatChip
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.components.SkeletonBox
import io.github.ottershelf.ui.components.StatusIcon
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import kotlin.math.min

/** Whether the camera may be used: allowed, not asked yet (the rationale shows), or refused. */
enum class CameraAccess { Granted, Ask, Denied }

/** The camera's side of the screen: access, the light, and a failure to start ([failed] "" for one without a message). */
data class CameraUi(
    val access: CameraAccess,
    val torchAvailable: Boolean = false,
    val torchOn: Boolean = false,
    val failed: String? = null,
) {
    val live: Boolean get() = access == CameraAccess.Granted && failed == null
}

/** The scanner's callbacks, grouped so screenshot tests pass none. */
internal class ScanActions(
    val onClose: () -> Unit = {},
    val onTorch: () -> Unit = {},
    val onAllowCamera: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onType: () -> Unit = {},
    val onManualText: (String) -> Unit = {},
    val onFind: () -> Unit = {},
    val onCloseManual: () -> Unit = {},
    val onPick: (ScanBook) -> Unit = {},
    val onRequest: () -> Unit = {},
    val onScanAgain: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * The scanner: the camera full screen with a reticle, the light and "Type the ISBN" (or, without the
 * camera, why not and the way to allow it), and over it a sheet for what the ISBN led to or for
 * typing one. [viewfinder] draws the camera (nothing in screenshot tests).
 */
@Composable
internal fun ScanContent(
    state: ScanUiState,
    camera: CameraUi,
    actions: ScanActions = ScanActions(),
    viewfinder: @Composable (Modifier) -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        if (camera.live) CameraStage(purposeOf(state), camera, actions, viewfinder) else NoCameraStage(purposeOf(state), camera, actions)

        val manual = state.manual
        val result = state.result
        if (manual != null || result != null) {
            // Tapping above the sheet closes it (back to the camera), like a bottom sheet's scrim.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(SCRIM)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (manual != null) actions.onCloseManual() else actions.onScanAgain()
                    },
            )
            Sheet(Modifier.align(Alignment.BottomCenter)) {
                when {
                    manual != null -> ManualSheet(manual, actions)
                    result != null -> ResultSheet(result, purposeOf(state), actions)
                }
            }
        }
    }
}

/** Behind a sheet: the drawer's scrim (the Nexus DrawerLayout's 60% black), a little lighter over the camera. */
private val SCRIM = Color(0x80000000)

/** Around the reticle, over the camera image (not the page, so no theme colour applies). */
private val CAMERA_DIM = Color(0x73000000)

/** What the scan is for, in words: under the title, over several copies, and when none is in the library. */
private class ScanPurpose(val subtitle: Int?, val choose: Int, val missing: Int?)

private fun purposeOf(state: ScanUiState) = when {
    state.pick -> ScanPurpose(R.string.scan_subtitle_pick, R.string.scan_choose_pick, R.string.scan_missing_pick)
    state.forTimer -> ScanPurpose(R.string.scan_subtitle_timer, R.string.scan_choose_timer, R.string.scan_missing_timer)
    else -> ScanPurpose(null, R.string.scan_choose, null)
}

// --- the camera -----------------------------------------------------------------------------------

@Composable
private fun CameraStage(purpose: ScanPurpose, camera: CameraUi, actions: ScanActions, viewfinder: @Composable (Modifier) -> Unit) {
    val colors = OttershelfTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        viewfinder(Modifier.fillMaxSize())
        // A landscape window, as a barcode is: the width of the phone less a margin, at most 360dp.
        val windowW = min(maxWidth.value - 64f, 360f).dp
        val windowH = windowW * 0.52f
        val windowTop = maxHeight * 0.40f - windowH / 2
        Reticle(windowTop, windowW, windowH, Modifier.fillMaxSize())
        Text(
            stringResource(R.string.scan_hint),
            style = MaterialTheme.typography.labelLarge,
            color = colors.foreground,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = windowTop + windowH + 20.dp)
                .padding(horizontal = 32.dp)
                .background(colors.card.copy(alpha = 0.78f), RoundedCornerShape(OttershelfTheme.radii.lg))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onClose, modifier = Modifier.background(colors.card.copy(alpha = 0.78f), CircleShape)) {
                LucideIcon("X", contentDescription = stringResource(R.string.scan_close), tint = colors.foreground, size = 22.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(
                Modifier
                    .background(colors.card.copy(alpha = 0.78f), RoundedCornerShape(OttershelfTheme.radii.lg))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.titleSmall, color = colors.foreground)
                purpose.subtitle?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
                }
            }
            Spacer(Modifier.weight(1f))
            if (camera.torchAvailable) {
                val on = camera.torchOn
                IconButton(
                    onClick = actions.onTorch,
                    modifier = Modifier.background(if (on) colors.primary else colors.card.copy(alpha = 0.78f), CircleShape),
                ) {
                    LucideIcon(
                        if (on) "Flashlight" else "FlashlightOff",
                        contentDescription = stringResource(if (on) R.string.scan_torch_off else R.string.scan_torch_on),
                        tint = if (on) colors.onPrimary else colors.foreground,
                        size = 22.dp,
                    )
                }
            }
        }

        SecondaryButton(
            stringResource(R.string.scan_type_isbn),
            onClick = actions.onType,
            icon = "Keyboard",
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp),
        )
    }
}

/**
 * The reticle: the camera dimmed outside a rounded window, accent corners and a faint line across
 * the middle to line the bars up on. It takes no touches, so a tap reaches the viewfinder (focus).
 */
@Composable
private fun Reticle(top: androidx.compose.ui.unit.Dp, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val accent = OttershelfTheme.colors.primary
    Canvas(modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val w = width.toPx()
        val h = height.toPx()
        val left = (size.width - w) / 2
        val t = top.toPx()
        val radius = 18.dp.toPx()
        drawRect(CAMERA_DIM)
        drawRoundRect(Color.Transparent, Offset(left, t), Size(w, h), CornerRadius(radius), blendMode = BlendMode.Clear)

        // Corner brackets: an arc and two short arms each.
        val stroke = 4.dp.toPx()
        val arm = 26.dp.toPx()
        val inset = stroke / 2
        val l = left + inset
        val r = left + w - inset
        val tt = t + inset
        val b = t + h - inset
        val rr = radius - inset
        val path = androidx.compose.ui.graphics.Path().apply {
            // top left
            moveTo(l, tt + rr + arm); lineTo(l, tt + rr)
            arcTo(androidx.compose.ui.geometry.Rect(l, tt, l + 2 * rr, tt + 2 * rr), 180f, 90f, false)
            lineTo(l + rr + arm, tt)
            // top right
            moveTo(r - rr - arm, tt); lineTo(r - rr, tt)
            arcTo(androidx.compose.ui.geometry.Rect(r - 2 * rr, tt, r, tt + 2 * rr), 270f, 90f, false)
            lineTo(r, tt + rr + arm)
            // bottom right
            moveTo(r, b - rr - arm); lineTo(r, b - rr)
            arcTo(androidx.compose.ui.geometry.Rect(r - 2 * rr, b - 2 * rr, r, b), 0f, 90f, false)
            lineTo(r - rr - arm, b)
            // bottom left
            moveTo(l + rr + arm, b); lineTo(l + rr, b)
            arcTo(androidx.compose.ui.geometry.Rect(l, b - 2 * rr, l + 2 * rr, b), 90f, 90f, false)
            lineTo(l, b - rr - arm)
        }
        drawPath(path, accent, style = Stroke(width = stroke, cap = StrokeCap.Round))
        val y = t + h / 2
        drawLine(accent.copy(alpha = 0.55f), Offset(left + 24.dp.toPx(), y), Offset(left + w - 24.dp.toPx(), y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** No camera: the rationale (Allow camera), a refusal (Open settings) or a failure, and typing the ISBN. */
@Composable
private fun NoCameraStage(purpose: ScanPurpose, camera: CameraUi, actions: ScanActions) {
    val colors = OttershelfTheme.colors
    Scaffold(
        topBar = {
            DetailTopBar(
                title = stringResource(R.string.scan_title),
                subtitle = purpose.subtitle?.let { stringResource(it) },
                onBack = actions.onClose,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val failed = camera.failed != null && camera.access == CameraAccess.Granted
            DashCard(Modifier.fillMaxWidth().widthIn(max = 480.dp), contentPadding = PaddingValues(20.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val icon = when {
                        failed -> "TriangleAlert"
                        camera.access == CameraAccess.Denied -> "CameraOff"
                        else -> "ScanBarcode"
                    }
                    Box(Modifier.size(56.dp).background(colors.accentTint, CircleShape), contentAlignment = Alignment.Center) {
                        LucideIcon(icon, contentDescription = null, tint = colors.primary, size = 26.dp)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(
                            when {
                                failed -> R.string.scan_camera_failed
                                camera.access == CameraAccess.Denied -> R.string.scan_camera_denied_title
                                else -> R.string.scan_camera_title
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.foreground,
                        textAlign = TextAlign.Center,
                    )
                    val detail = when {
                        failed -> camera.failed.takeIf { it.isNotBlank() }
                        camera.access == CameraAccess.Denied -> stringResource(R.string.scan_camera_denied)
                        else -> stringResource(R.string.scan_camera_rationale)
                    }
                    detail?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.mutedForeground, textAlign = TextAlign.Center)
                    }
                    when {
                        failed -> Unit
                        camera.access == CameraAccess.Denied -> {
                            Spacer(Modifier.height(18.dp))
                            AccentButton(stringResource(R.string.scan_open_settings), onClick = actions.onOpenSettings, icon = "Settings", modifier = Modifier.fillMaxWidth())
                        }
                        else -> {
                            Spacer(Modifier.height(18.dp))
                            AccentButton(stringResource(R.string.scan_camera_allow), onClick = actions.onAllowCamera, icon = "Camera", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            SecondaryButton(
                stringResource(R.string.scan_type_instead),
                onClick = actions.onType,
                icon = "Keyboard",
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
            )
        }
    }
}

// --- sheets ---------------------------------------------------------------------------------------

/** The app's bottom sheet look (card colour, 2xl top corners, a handle), kept in the layout so the camera stays behind it. */
@Composable
private fun Sheet(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = OttershelfTheme.colors
    val radius = OttershelfTheme.radii.xl2
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.88f)
                .clip(RoundedCornerShape(topStart = radius, topEnd = radius))
                .background(colors.card)
                // Taps inside the sheet stay in it (not the scrim's).
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp, 4.dp).background(colors.mutedForeground.copy(alpha = 0.4f), CircleShape))
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SheetHeader(icon: String, title: String, subtitle: String?, iconTint: Color = OttershelfTheme.colors.primary, disc: Color = OttershelfTheme.colors.accentTint) {
    val colors = OttershelfTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(disc, CircleShape), contentAlignment = Alignment.Center) {
            LucideIcon(icon, contentDescription = null, tint = iconTint, size = 20.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.foreground)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground) }
        }
    }
}

@Composable
private fun isbnLine(isbn: Isbn) = stringResource(R.string.scan_isbn, isbn.isbn13)

@Composable
private fun ResultSheet(result: ScanResult, purpose: ScanPurpose, actions: ScanActions) {
    val colors = OttershelfTheme.colors
    when (result) {
        is ScanResult.Looking -> Row(verticalAlignment = Alignment.CenterVertically) {
            LoadingState(Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.scan_looking), style = MaterialTheme.typography.titleMedium, color = colors.foreground)
                Text(isbnLine(result.isbn), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
            }
        }

        is ScanResult.Opening -> {
            BookChoiceRow(result.book, onClick = null)
            Text(stringResource(R.string.scan_opening), style = MaterialTheme.typography.bodyMedium, color = colors.mutedForeground)
        }

        is ScanResult.Choose -> {
            SheetHeader(
                "Library",
                pluralStringResource(R.plurals.scan_copies, result.books.size, result.books.size),
                stringResource(purpose.choose),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                result.books.forEach { book -> BookChoiceRow(book, onClick = { actions.onPick(book) }) }
            }
            Text(isbnLine(result.isbn), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
            SecondaryButton(stringResource(R.string.scan_again), onClick = actions.onScanAgain, icon = "ScanBarcode", modifier = Modifier.fillMaxWidth())
        }

        is ScanResult.Missing -> MissingSheet(result, purpose, actions)

        is ScanResult.Failed -> {
            SheetHeader("TriangleAlert", stringResource(R.string.scan_failed), result.message?.takeIf { it.isNotBlank() }, iconTint = colors.destructive, disc = colors.destructive.copy(alpha = 0.12f))
            Text(isbnLine(result.isbn), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(stringResource(R.string.scan_again), onClick = actions.onScanAgain, modifier = Modifier.weight(1f))
                AccentButton(stringResource(R.string.scan_retry), onClick = actions.onRetry, icon = "RefreshCw", modifier = Modifier.weight(1f))
            }
        }
    }
}

/** "Not in your library": what the providers know, other editions the user has, and Request it. */
@Composable
private fun MissingSheet(result: ScanResult.Missing, purpose: ScanPurpose, actions: ScanActions) {
    val colors = OttershelfTheme.colors
    SheetHeader("BookX", stringResource(R.string.scan_missing_title), isbnLine(result.isbn))
    purpose.missing?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = colors.mutedForeground) }

    val candidate = result.candidate
    when {
        candidate != null -> CandidateCard(candidate)
        result.lookingUp -> Row(verticalAlignment = Alignment.CenterVertically) {
            SkeletonBox(Modifier.size(56.dp, 84.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.scan_looking_up), style = MaterialTheme.typography.bodyMedium, color = colors.mutedForeground)
                SkeletonBox(Modifier.fillMaxWidth(0.8f).height(14.dp))
                SkeletonBox(Modifier.fillMaxWidth(0.5f).height(12.dp))
            }
        }
        else -> Text(
            stringResource(if (result.lookupFailed) R.string.scan_lookup_failed else R.string.scan_unknown),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.mutedForeground,
        )
    }

    if (result.editions.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.scan_editions), style = MaterialTheme.typography.labelLarge, color = colors.mutedForeground)
            result.editions.forEach { book -> BookChoiceRow(book, onClick = { actions.onPick(book) }) }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(stringResource(R.string.scan_again), onClick = actions.onScanAgain, icon = "ScanBarcode", modifier = Modifier.weight(1f))
        when {
            !result.canRequest -> Unit
            candidate != null -> AccentButton(stringResource(R.string.scan_request), onClick = actions.onRequest, icon = "BookPlus", modifier = Modifier.weight(1f))
            !result.lookingUp -> SecondaryButton(stringResource(R.string.scan_open_requests), onClick = actions.onRequest, icon = "BookPlus", modifier = Modifier.weight(1f))
        }
    }
}

/** A provider's book: cover, title, subtitle, authors, year and series. */
@Composable
private fun CandidateCard(c: MetadataCandidate) {
    val colors = OttershelfTheme.colors
    val authors = c.authors?.filter { it.isNotBlank() }?.joinToString(", ")?.ifEmpty { null }
    Row {
        BookCover(c.coverUrl, c.shownTitle, Modifier.size(64.dp, 96.dp), authors = authors, seed = c.shownTitle)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.shownTitle.orEmpty(), style = MaterialTheme.typography.titleMedium, color = colors.foreground, maxLines = 3, overflow = TextOverflow.Ellipsis)
            c.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            authors?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.mutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val meta = listOfNotNull(
                c.publishedYear?.toString(),
                c.seriesName?.takeIf { it.isNotBlank() }?.let { s -> c.seriesIndex?.let { "$s #${formatIndex(it)}" } ?: s },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun formatIndex(index: Double): String = if (index % 1.0 == 0.0) index.toLong().toString() else index.toString()

/** A library book to open or pick: cover, title, authors, its formats and year, the user's status. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookChoiceRow(book: ScanBook, onClick: (() -> Unit)?) {
    val colors = OttershelfTheme.colors
    val title = book.title ?: stringResource(R.string.scan_untitled)
    val authors = book.authors.joinToString(", ")
    CardRow(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
        BookCover(book.cover, book.title, Modifier.size(44.dp, 66.dp), authors = authors, seed = book.title ?: book.id.toString())
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (authors.isNotEmpty()) {
                Text(authors, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (book.formats.isNotEmpty() || book.year != null) {
                Spacer(Modifier.height(5.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    book.formats.forEach { FormatChip(it) }
                    book.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = colors.mutedForeground) }
                }
            }
        }
        ReadStatus.of(book.status)?.takeIf { it != ReadStatus.UNREAD }?.let { status ->
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StatusIcon(status, size = 18.dp)
                Text(status.label, style = MaterialTheme.typography.labelSmall, color = colors.mutedForeground)
            }
        }
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            LucideIcon("ChevronRight", contentDescription = null, tint = colors.mutedForeground, size = 18.dp)
        }
    }
}

/** Typing the ISBN: the field (number keyboard, an X key for ISBN-10s), what's wrong or right, Find. */
@Composable
private fun ManualSheet(entry: ManualEntry, actions: ScanActions) {
    val colors = OttershelfTheme.colors
    Column {
        Text(stringResource(R.string.scan_manual_title), style = MaterialTheme.typography.titleMedium, color = colors.foreground)
        Text(stringResource(R.string.scan_manual_detail), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
    }
    val focus = remember { FocusRequester() }
    val inPreview = LocalInspectionMode.current
    if (!inPreview) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val problem = entry.problem
    val valid = entry.isbn
    // The field keeps its own cursor, so the X key types where the user is, as a key on the keyboard would.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(entry.text, TextRange(entry.text.length))) }
    // Changed from outside (brought back after the process was killed, or cut to length): that text, the cursor at its end.
    val value = if (field.text == entry.text) field else TextFieldValue(entry.text, TextRange(entry.text.length))
    fun type(next: TextFieldValue) {
        field = next
        actions.onManualText(next.text)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = ::type,
            modifier = Modifier.weight(1f).focusRequester(focus),
            placeholder = { Text(stringResource(R.string.scan_manual_placeholder), style = MaterialTheme.typography.bodyLarge) },
            leadingIcon = { LucideIcon("Barcode", contentDescription = null, tint = colors.mutedForeground, size = 18.dp) },
            singleLine = true,
            isError = problem != null,
            textStyle = MaterialTheme.typography.bodyLarge.copy(letterSpacing = 0.5.sp),
            shape = RoundedCornerShape(OttershelfTheme.radii.md),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { actions.onFind() }),
        )
        if (entry.offersX) {
            Spacer(Modifier.width(8.dp))
            val description = stringResource(R.string.scan_manual_x_description)
            Box(
                Modifier
                    .size(52.dp, 56.dp)
                    .clip(RoundedCornerShape(OttershelfTheme.radii.md))
                    .background(colors.accentTint)
                    .clickable(role = Role.Button) { type(withTypedX(value)) }
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.scan_manual_x), style = MaterialTheme.typography.titleMedium, color = colors.primary)
            }
        }
    }
    when {
        problem != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            LucideIcon("CircleAlert", contentDescription = null, tint = colors.destructive, size = 16.dp)
            Spacer(Modifier.width(6.dp))
            Text(problemText(problem), style = MaterialTheme.typography.bodyMedium, color = colors.destructive)
        }
        valid != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            LucideIcon("CircleCheck", contentDescription = null, tint = colors.success, size = 16.dp)
            Spacer(Modifier.width(6.dp))
            val compact = entry.text.count { it.isDigit() || it == 'x' || it == 'X' }
            Text(
                if (compact == 10) stringResource(R.string.scan_manual_valid_13, valid.isbn13) else stringResource(R.string.scan_manual_valid),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.success,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(stringResource(R.string.scan_manual_cancel), onClick = actions.onCloseManual, modifier = Modifier.weight(1f))
        AccentButton(
            stringResource(R.string.scan_manual_find),
            onClick = actions.onFind,
            icon = "Search",
            enabled = entry.input !is IsbnInput.Empty,
            modifier = Modifier.weight(1f),
        )
    }
}

/** [value] with an X typed at the cursor (over any selection) and the cursor just after it: the X key. */
internal fun withTypedX(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val start = value.selection.min.coerceIn(0, text.length)
    val end = value.selection.max.coerceIn(start, text.length)
    return TextFieldValue(text.replaceRange(start, end, "X"), TextRange(start + 1))
}

@Composable
private fun problemText(problem: IsbnInput): String = stringResource(
    when (problem) {
        IsbnInput.TooLong -> R.string.scan_problem_too_long
        IsbnInput.BadCharacter -> R.string.scan_problem_character
        IsbnInput.MisplacedX -> R.string.scan_problem_x
        IsbnInput.NotABook -> R.string.scan_problem_not_book
        is IsbnInput.BadCheckDigit -> R.string.scan_problem_check
        else -> R.string.scan_problem_incomplete
    },
)
