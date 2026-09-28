package io.github.ottershelf.feature.scan

import android.util.Size
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import io.github.ottershelf.R
import zxingcpp.BarcodeReader
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The back camera's viewfinder, reading book barcodes: CameraX (a `camera-compose` viewfinder, as
 * the quotes' page scan) with an [ImageAnalysis] at [ANALYSIS_SIZE], fed to zxing-cpp
 * (`io.github.zxing-cpp:android`, Apache-2.0, native code in the APK: the frames stay on the
 * phone) restricted to EAN-13, the barcode ISBNs are printed as. Frames are dropped while a newer
 * one waits, so a detection comes within a frame or two.
 *
 * [analysing] false: frames are closed unread (a result is showing), the preview keeps running.
 * [onIsbn] runs on the main thread for each frame that holds an ISBN barcode ([Isbn.firstIn]:
 * 978/979 with a valid check digit; price codes and add-ons are ignored). [torch]: the flash as a
 * light; [onTorchAvailable] says whether there is one. A tap focuses there. There is no automatic
 * zoom (ML Kit's zoom suggestion has no zxing-cpp equivalent): a barcode too far away to read
 * needs the phone brought closer.
 */
@Composable
internal fun BarcodeCamera(
    analysing: Boolean,
    torch: Boolean,
    onTorchAvailable: (Boolean) -> Unit,
    onIsbn: (Isbn) -> Unit,
    onFailed: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var surface by remember { mutableStateOf<SurfaceRequest?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val transformer = remember { MutableCoordinateTransformer() }
    val accepting = remember { AtomicBoolean(false) }
    SideEffect { accepting.set(analysing) }
    val isbn by rememberUpdatedState(onIsbn)
    val failed by rememberUpdatedState(onFailed)
    val torchAvailable by rememberUpdatedState(onTorchAvailable)

    // One reader, used only on the one worker thread (a BarcodeReader isn't thread-safe). It holds
    // no native memory between frames, so there is nothing to close but the thread.
    val scanner = remember { newScanner() }
    val worker = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { worker.shutdown() }
    }

    LaunchedEffect(lifecycle) {
        val preview = Preview.Builder().build().apply { setSurfaceProvider { surface = it } }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(worker, IsbnAnalyzer(scanner, accepting, ContextCompat.getMainExecutor(context)) { isbn(it) })
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
            val bound = provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            camera = bound
            torchAvailable(bound.cameraInfo.hasFlashUnit())
        } catch (e: Exception) {
            failed(e.message)
            return@LaunchedEffect
        }
        try {
            awaitCancellation()
        } finally {
            camera = null
            analysis.clearAnalyzer()
            provider.unbindAll()
        }
    }

    LaunchedEffect(camera, torch) {
        val cam = camera ?: return@LaunchedEffect
        if (cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(torch)
    }

    val description = stringResource(R.string.scan_viewfinder)
    Box(modifier) {
        surface?.let { request ->
            CameraXViewfinder(
                surfaceRequest = request,
                coordinateTransformer = transformer,
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = description }
                    .pointerInput(request, camera) {
                        detectTapGestures { tap ->
                            val cam = camera ?: return@detectTapGestures
                            val point = with(transformer) { tap.transform() }
                            val factory = SurfaceOrientedMeteringPointFactory(request.resolution.width.toFloat(), request.resolution.height.toFloat())
                            cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(factory.createPoint(point.x, point.y)).build())
                        }
                    },
            )
        }
    }
}

/**
 * 1280x720: EAN-13 has 95 modules, so a barcode across half the frame gets about 7 px each, plenty
 * for zxing-cpp (it reads 2 or less), and a frame this size is read in a few milliseconds.
 */
private val ANALYSIS_SIZE = Size(1280, 720)

/**
 * The reader [BarcodeCamera] reads frames with (internal: the device tests read barcode pictures
 * with it): EAN-13 only, which is also how a UPC-A price code is reported (13 digits starting with
 * 0, so [Isbn.firstIn] passes over it); UPC-A, UPC-E and EAN-8 aren't looked for at all. A 2- or
 * 5-digit add-on is ignored (the code before it is still read). `tryHarder` scans more rows of the
 * frame, `tryRotate` also reads a barcode held upright, and `tryDownscale` retries a frame at lower
 * resolutions, which helps with a blurred one. Nothing native is kept, so nothing needs closing.
 */
internal fun newScanner(): BarcodeReader = BarcodeReader(
    BarcodeReader.Options(
        formats = setOf(BarcodeReader.Format.EAN_13),
        tryHarder = true,
        tryRotate = true,
        tryDownscale = true,
        eanAddOnSymbol = BarcodeReader.EanAddOnSymbol.IGNORE,
    ),
)

/**
 * Hands each frame (the luma plane, turned by its rotation) to [reader] while [accepting], and the
 * first ISBN it holds to [onIsbn] on [main]; frames are closed unread otherwise, and always closed
 * before the next one is taken. Runs on the one analysis thread.
 */
private class IsbnAnalyzer(
    private val reader: BarcodeReader,
    private val accepting: AtomicBoolean,
    private val main: Executor,
    private val onIsbn: (Isbn) -> Unit,
) : ImageAnalysis.Analyzer {

    override fun analyze(frame: ImageProxy) {
        val isbn = frame.use {
            if (!accepting.get()) return
            // An unexpected frame format (read() wants YUV) is a frame without an ISBN.
            val texts = try {
                reader.read(frame).map { it.text }
            } catch (e: IllegalStateException) {
                return
            }
            Isbn.firstIn(texts) ?: return
        }
        main.execute { if (accepting.get()) onIsbn(isbn) }
    }
}
