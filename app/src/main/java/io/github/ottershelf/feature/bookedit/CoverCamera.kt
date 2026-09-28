package io.github.ottershelf.feature.bookedit

import android.app.Activity
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import io.github.ottershelf.R
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.io.File
import kotlin.math.min

/** Where the guide frame sits on a viewfinder of [size] px: a 2:3 box, centred, a little above the middle. */
internal fun guideFrame(size: IntSize, topInset: Float = 0f, bottomInset: Float = 0f): CameraFrame {
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val room = (h - topInset - bottomInset).coerceAtLeast(1f)
    val frameW = min(w * 0.74f, room * 0.78f * 2f / 3f)
    val frameH = frameW * 1.5f
    val left = (w - frameW) / 2f
    val top = topInset + (room - frameH) / 2f
    return CameraFrame(size.width, size.height, left, top, left + frameW, top + frameH)
}

/**
 * The cover photo, full screen over the form: CameraX's viewfinder (as the quote scanner's), the
 * page dimmed outside a 2:3 frame to line the cover up in, the shutter, the gallery and Close. The
 * shot goes to the work folder with where the frame was, and is cut to it ([CoverImage.fromCamera]).
 */
@Composable
internal fun CoverCameraContent(
    onClose: () -> Unit,
    onTaken: (File, CameraFrame?) -> Unit,
    onFailed: () -> Unit,
    onChoosePhoto: () -> Unit,
) {
    val colors = OttershelfTheme.colors
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val inPreview = LocalInspectionMode.current
    val density = LocalDensity.current
    var surface by remember { mutableStateOf<SurfaceRequest?>(null) }
    var busy by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val failed by rememberUpdatedState(onFailed)
    // Light status bar icons over the camera (as the ISBN scanner), whatever the theme.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val before = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { if (before != null) controller.isAppearanceLightStatusBars = before }
    }
    if (!inPreview) {
        LaunchedEffect(lifecycle) {
            val preview = Preview.Builder().build().apply { setSurfaceProvider { surface = it } }
            val provider = try {
                ProcessCameraProvider.awaitInstance(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed()
                return@LaunchedEffect
            }
            try {
                provider.unbindAll()
                // Auto rotation, as the quote scanner: the shot follows how the phone is held.
                val session = SessionConfig.Builder(preview, capture).setAutoRotationEnabled(true).build()
                provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, session)
            } catch (e: Exception) {
                failed()
                return@LaunchedEffect
            }
            try {
                awaitCancellation()
            } finally {
                provider.unbindAll()
            }
        }
    }
    // The frame keeps clear of the top row and the shutter.
    val topInset = with(density) { 96.dp.toPx() }
    val bottomInset = with(density) { 150.dp.toPx() }
    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { viewSize = it }) {
        surface?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
        if (viewSize != IntSize.Zero) {
            val frame = guideFrame(viewSize, topInset, bottomInset)
            val radius = with(density) { OttershelfTheme.radii.md.toPx() }
            val stroke = with(density) { 2.dp.toPx() }
            val line = colors.primary
            Canvas(Modifier.fillMaxSize()) {
                val rect = Rect(frame.left, frame.top, frame.right, frame.bottom)
                val hole = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(rect, CornerRadius(radius))) }
                clipPath(hole, clipOp = ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.55f)) }
                drawRoundRect(line, topLeft = Offset(rect.left, rect.top), size = Size(rect.width, rect.height), cornerRadius = CornerRadius(radius), style = Stroke(stroke))
            }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.background(colors.card.copy(alpha = 0.7f), CircleShape)) {
                LucideIcon("X", contentDescription = stringResource(R.string.bookedit_close), tint = colors.foreground, size = 22.dp)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.bookedit_camera_hint),
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
                LucideIcon("Image", contentDescription = stringResource(R.string.bookedit_choose_photo), tint = colors.foreground, size = 24.dp)
            }
            val shutter = stringResource(R.string.bookedit_shutter)
            Box(
                Modifier
                    .size(76.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(if (busy) colors.mutedForeground else colors.primary)
                    .clickable(enabled = !busy && surface != null, role = Role.Button) {
                        busy = true
                        val file = CoverImage.newWorkFile(context, "shot")
                        val frame = viewSize.takeIf { it != IntSize.Zero }?.let { guideFrame(it, topInset, bottomInset) }
                        capture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    busy = false
                                    onTaken(file, frame)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    busy = false
                                    file.delete()
                                    failed()
                                }
                            },
                        )
                    }
                    .semantics { contentDescription = shutter },
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon("Camera", contentDescription = null, tint = colors.onPrimary, size = 28.dp)
            }
            Spacer(Modifier.size(52.dp))
        }
    }
}
