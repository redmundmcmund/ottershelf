package io.github.ottershelf.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlin.math.abs

/**
 * A cover image filling its box the way the web shows covers by default ("blurred fit",
 * BookCoverArtwork.vue): the whole cover, never cropped, and when its shape isn't the box's (a
 * tall, narrow edition in a 2:3 box) the space around it filled with the same cover, enlarged,
 * blurred and dimmed. A cover within [CLOSE_ENOUGH] of the box's shape fills it as before (its few
 * pixels of crop behind the fitted image, so no hairline shows), without the blur.
 *
 * The backdrop draws the image the loader already decoded: nothing more is fetched or decoded.
 */
@Composable
fun FittedCoverImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onSuccess: (AsyncImagePainter.State.Success) -> Unit = {},
    onError: () -> Unit = {},
) {
    var box by remember { mutableStateOf(Size.Zero) }
    var loaded by remember(model) { mutableStateOf<Painter?>(null) }
    Box(modifier.onSizeChanged { box = Size(it.width.toFloat(), it.height.toFloat()) }) {
        loaded?.let { painter ->
            val blurred = !sameShape(painter.intrinsicSize, box)
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.matchParentSize().then(
                    if (blurred) Modifier.graphicsLayer { scaleX = 1.1f; scaleY = 1.1f }.blur(12.dp) else Modifier,
                ),
                contentScale = ContentScale.Crop,
                colorFilter = if (blurred) DIM else null,
            )
        }
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Fit,
            onSuccess = {
                loaded = it.painter
                onSuccess(it)
            },
            onError = { onError() },
        )
    }
}

/** How far a cover's shape may differ from its box's (as a fraction of the ratio) and still fill it. */
internal const val CLOSE_ENOUGH = 0.03f

/** [image] has [box]'s shape, give or take [CLOSE_ENOUGH]; true while either size is unknown. */
internal fun sameShape(image: Size, box: Size): Boolean {
    if (image.width <= 0f || image.height <= 0f || box.width <= 0f || box.height <= 0f) return true
    if (!image.width.isFinite() || !image.height.isFinite()) return true
    val ratio = (image.width / image.height) / (box.width / box.height)
    return abs(ratio - 1f) <= CLOSE_ENOUGH
}

/** The web's backdrop `brightness-90`. */
private val DIM = ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(0.9f, 0.9f, 0.9f, 1f) })
