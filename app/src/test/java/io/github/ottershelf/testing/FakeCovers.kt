package io.github.ottershelf.testing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import coil3.ImageLoader
import coil3.asImage
import coil3.compose.AsyncImagePainter
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.compose.asPainter
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.ottershelf.ui.icons.Lucide
import java.io.File
import java.io.IOException

/**
 * Makes Coil draw made-up covers in screenshot tests, with no network: a cover model
 * `"fake-cover:<n>"` becomes a generated 200x300 cover (colour picked by n), and any other model
 * fails to load (so the component's error fallback shows). Also reads the full Lucide set, so
 * server-chosen icon names draw.
 */
@Composable
fun WithFakeCovers(content: @Composable () -> Unit) {
    Lucide.load { File("src/main/assets/lucide.txt").inputStream() }
    CompositionLocalProvider(
        LocalInspectionMode provides true,
        LocalAsyncImagePreviewHandler provides FakeCoverHandler,
        content = content,
    )
}

/** A cover model that [WithFakeCovers] draws. */
fun fakeCover(n: Int): String = "$FAKE_COVER$n"

/** A cover model that fails to load. */
const val BROKEN_COVER = "broken-cover"

private const val FAKE_COVER = "fake-cover:"

/** A fake cover, or a failed load for anything else. */
private object FakeCoverHandler : AsyncImagePreviewHandler {
    override suspend fun handle(imageLoader: ImageLoader, request: ImageRequest): AsyncImagePainter.State {
        val model = request.data as? String
        if (model == null || !model.startsWith(FAKE_COVER)) {
            return AsyncImagePainter.State.Error(null, ErrorResult(null, request, IOException("no cover")))
        }
        val image = drawFakeCover(model.removePrefix(FAKE_COVER).toIntOrNull() ?: 0).asImage()
        return AsyncImagePainter.State.Success(image.asPainter(request.context), SuccessResult(image, request))
    }
}

private val PALETTES = listOf(
    intArrayOf(0xFF1E3A5F.toInt(), 0xFF3D7EA6.toInt(), 0xFFF2C14E.toInt()),
    intArrayOf(0xFF5B1A18.toInt(), 0xFFB23A48.toInt(), 0xFFF7E1D7.toInt()),
    intArrayOf(0xFF20332B.toInt(), 0xFF4F7C5A.toInt(), 0xFFE9D8A6.toInt()),
    intArrayOf(0xFF2E1F47.toInt(), 0xFF7353BA.toInt(), 0xFFFFD166.toInt()),
    intArrayOf(0xFF3B2F2F.toInt(), 0xFFC97B3C.toInt(), 0xFFFFF3E0.toInt()),
    intArrayOf(0xFF0B1320.toInt(), 0xFF1C7C7D.toInt(), 0xFFE0FBFC.toInt()),
)

private fun drawFakeCover(n: Int): Bitmap {
    val (dark, mid, light) = PALETTES[Math.floorMod(n, PALETTES.size)].let { Triple(it[0], it[1], it[2]) }
    val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(0f, 0f, 0f, 300f, dark, mid, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, 200f, 300f, paint)
    paint.shader = null
    paint.color = light
    canvas.drawCircle(100f, 130f, 46f + (n % 3) * 8f, paint)
    paint.color = dark
    canvas.drawRect(0f, 232f, 200f, 300f, paint)
    paint.color = light
    canvas.drawRect(28f, 252f, 172f, 260f, paint)
    canvas.drawRect(52f, 270f, 148f, 276f, paint)
    return bitmap
}
