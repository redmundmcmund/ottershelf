package io.github.ottershelf.feature.quotes

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import io.github.ottershelf.R
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.DetailTopBar
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.io.File
import kotlin.math.min

/** The page scan, full screen over the form: camera, reading, picking the lines, or what failed. */
@Composable
internal fun ScanContent(
    step: ScanStep,
    onClose: () -> Unit = {},
    onTaken: (File) -> Unit = {},
    onCaptureFailed: (String?) -> Unit = {},
    onChoosePhoto: () -> Unit = {},
    onRetake: () -> Unit = {},
    onToggle: (Long) -> Unit = {},
    onLines: (Set<Long>, Boolean) -> Unit = { _, _ -> },
    onSelectAll: (Boolean) -> Unit = {},
    onUse: () -> Unit = {},
) {
    when (step) {
        ScanStep.Camera -> CameraStep(onClose, onTaken, onCaptureFailed, onChoosePhoto)
        is ScanStep.Reading -> MessageStep(onClose, onRetake = null, onChoosePhoto = null) {
            LoadingState(Modifier.size(64.dp))
            Text(stringResource(R.string.quotes_reading_page), style = MaterialTheme.typography.bodyLarge, color = OttershelfTheme.colors.foreground)
        }
        is ScanStep.Picking ->
            if (step.page.lines.isEmpty()) {
                MessageStep(onClose, onRetake, onChoosePhoto) { Notice("ScanLine", stringResource(R.string.quotes_no_text)) }
            } else {
                PickingStep(step, onClose, onRetake, onToggle, onLines, onSelectAll, onUse)
            }
        is ScanStep.Failed -> MessageStep(onClose, onRetake, onChoosePhoto) {
            Notice("TriangleAlert", listOfNotNull(stringResource(R.string.quotes_scan_failed), step.detail).joinToString("\n"))
        }
    }
}

@Composable
private fun Notice(icon: String, text: String) {
    val colors = OttershelfTheme.colors
    Box(Modifier.size(64.dp).background(colors.muted, CircleShape), contentAlignment = Alignment.Center) {
        LucideIcon(icon, contentDescription = null, tint = colors.mutedForeground, size = 28.dp)
    }
    Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.foreground, textAlign = TextAlign.Center)
}

/** A message in the middle, with Retake and Choose a photo when given. */
@Composable
private fun MessageStep(onClose: () -> Unit, onRetake: (() -> Unit)?, onChoosePhoto: (() -> Unit)?, content: @Composable () -> Unit) {
    Scaffold(topBar = { DetailTopBar(title = stringResource(R.string.quotes_scan), onBack = onClose) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
            if (onRetake != null || onChoosePhoto != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onChoosePhoto?.let { SecondaryButton(stringResource(R.string.quotes_choose_photo), onClick = it, icon = "Image") }
                    onRetake?.let { AccentButton(stringResource(R.string.quotes_retake), onClick = it, icon = "RotateCcw") }
                }
            }
        }
    }
}

/** CameraX: the viewfinder, a shutter, the gallery and Close. The photo goes to the work folder. */
@Composable
private fun CameraStep(onClose: () -> Unit, onTaken: (File) -> Unit, onFailed: (String?) -> Unit, onChoosePhoto: () -> Unit) {
    val colors = OttershelfTheme.colors
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val inPreview = LocalInspectionMode.current
    var surface by remember { mutableStateOf<SurfaceRequest?>(null) }
    var busy by remember { mutableStateOf(false) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val failed by rememberUpdatedState(onFailed)
    if (!inPreview) {
        LaunchedEffect(lifecycle) {
            val preview = Preview.Builder().build().apply { setSurfaceProvider { surface = it } }
            val provider = try {
                ProcessCameraProvider.awaitInstance(context)
            } catch (e: CancellationException) {
                throw e // closed before the camera was ready: nothing failed
            } catch (e: Exception) {
                failed(e.message)
                return@LaunchedEffect
            }
            try {
                provider.unbindAll()
                // Auto rotation: the capture follows how the phone is held (the activity handles
                // rotation itself, and with auto-rotate off the display never turns), so a page
                // shot in landscape isn't read sideways.
                val session = SessionConfig.Builder(preview, capture).setAutoRotationEnabled(true).build()
                provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, session)
            } catch (e: Exception) {
                failed(e.message)
                return@LaunchedEffect
            }
            try {
                awaitCancellation()
            } finally {
                provider.unbindAll()
            }
        }
    }
    Box(Modifier.fillMaxSize().background(colors.background)) {
        surface?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.background(colors.card.copy(alpha = 0.7f), CircleShape)) {
                LucideIcon("X", contentDescription = stringResource(R.string.quotes_close), tint = colors.foreground, size = 22.dp)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.quotes_camera_hint),
                style = MaterialTheme.typography.labelLarge,
                color = colors.foreground,
                modifier = Modifier
                    .background(colors.card.copy(alpha = 0.7f), RoundedCornerShape(OttershelfTheme.radii.lg))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 32.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onChoosePhoto, modifier = Modifier.size(52.dp).background(colors.card.copy(alpha = 0.7f), CircleShape)) {
                LucideIcon("Image", contentDescription = stringResource(R.string.quotes_choose_photo), tint = colors.foreground, size = 24.dp)
            }
            Box(
                Modifier
                    .size(76.dp)
                    .border(4.dp, colors.foreground, CircleShape)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(if (busy) colors.mutedForeground else colors.primary)
                    .clickable(enabled = !busy && surface != null, role = Role.Button) {
                        busy = true
                        val file = PageReader.newWorkFile(context)
                        capture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    busy = false
                                    onTaken(file)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    busy = false
                                    file.delete()
                                    failed(exception.message)
                                }
                            },
                        )
                    }
                    .semantics { contentDescription = context.getString(R.string.quotes_take_photo) },
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon("Aperture", contentDescription = null, tint = colors.onPrimary, size = 28.dp)
            }
            Spacer(Modifier.size(52.dp))
        }
    }
}

/**
 * The photo with every recognised line boxed: tap a line, or drag across lines, to keep or drop
 * them (a drag keeps when it starts on a dropped line). Then "Use N lines" fills the quote.
 */
@Composable
private fun PickingStep(
    step: ScanStep.Picking,
    onClose: () -> Unit,
    onRetake: () -> Unit,
    onToggle: (Long) -> Unit,
    onLines: (Set<Long>, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onUse: () -> Unit,
) {
    val colors = OttershelfTheme.colors
    val count = step.selected.size
    val all = count == step.page.lines.size
    Scaffold(
        topBar = {
            DetailTopBar(
                title = stringResource(R.string.quotes_pick_lines),
                subtitle = stringResource(R.string.quotes_pick_hint),
                onBack = onClose,
                actions = {
                    IconButton(onClick = onRetake) {
                        LucideIcon("RotateCcw", contentDescription = stringResource(R.string.quotes_retake), tint = colors.foreground, size = 22.dp)
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().background(colors.card).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryButton(
                    stringResource(if (all) R.string.quotes_select_none else R.string.quotes_select_all),
                    onClick = { onSelectAll(!all) },
                )
                AccentButton(
                    pluralStringResource(R.plurals.quotes_use_lines, count, count),
                    onClick = onUse,
                    icon = "Check",
                    enabled = count > 0,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) { padding ->
        LinePicker(step, onToggle, onLines, Modifier.fillMaxSize().padding(padding).padding(8.dp))
    }
}

@Composable
private fun LinePicker(step: ScanStep.Picking, onToggle: (Long) -> Unit, onLines: (Set<Long>, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val page = step.page
    val image = remember(step.image) { step.image.asImageBitmap() }
    val density = LocalDensity.current
    val selected by rememberUpdatedState(step.selected)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val scale = min(boxW / page.width, boxH / page.height)
        val w = page.width * scale
        val h = page.height * scale
        val slop = with(density) { 6.dp.toPx() } / scale
        fun lineAt(o: Offset): OcrLine? {
            val x = o.x / scale
            val y = o.y / scale
            return page.lines.firstOrNull { it.contains(x, y) } ?: page.lines.firstOrNull { it.contains(x, y, slop) }
        }
        Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() })) {
            Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            Canvas(
                Modifier
                    .fillMaxSize()
                    // Keyed on what lineAt uses: a rotation or window resize changes the scale
                    // without recreating anything, and a running gesture loop keeps its first lambda.
                    .pointerInput(page, scale, slop) { detectTapGestures { o -> lineAt(o)?.let { onToggle(it.key) } } }
                    .pointerInput(page, scale, slop) {
                        var keep = true
                        val touched = mutableSetOf<Long>()
                        detectDragGestures(
                            onDragStart = { o ->
                                touched.clear()
                                val line = lineAt(o)
                                keep = line == null || line.key !in selected
                                line?.let { touched += it.key; onLines(setOf(it.key), keep) }
                            },
                            onDrag = { change, _ ->
                                lineAt(change.position)?.let { if (touched.add(it.key)) onLines(setOf(it.key), keep) }
                            },
                        )
                    },
            ) {
                val radius = CornerRadius(3.dp.toPx())
                for (line in page.lines) {
                    val on = line.key in step.selected
                    val topLeft = Offset(line.left * scale - 2f, line.top * scale - 2f)
                    val size = Size((line.right - line.left) * scale + 4f, (line.bottom - line.top) * scale + 4f)
                    if (on) drawRoundRect(colors.primary.copy(alpha = 0.28f), topLeft, size, radius)
                    drawRoundRect(
                        if (on) colors.primary else colors.primary.copy(alpha = 0.55f),
                        topLeft, size, radius,
                        style = Stroke(width = (if (on) 2.dp else 1.dp).toPx()),
                    )
                }
            }
        }
    }
}
