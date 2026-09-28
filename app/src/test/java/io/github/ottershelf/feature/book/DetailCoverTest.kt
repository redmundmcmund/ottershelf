package io.github.ottershelf.feature.book

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.asImage
import coil3.compose.AsyncImagePainter
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.compose.asPainter
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import java.util.Collections

/**
 * The book page fetches the full cover (`books/:id/cover`, the original file) only when the grid's
 * thumbnail isn't enough for its 160x240 dp box: missing, failed, or under 0.8x the box's height in
 * pixels. Images are made up by a preview handler that records what the page asked for.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DetailCoverTest {

    @get:Rule
    val rule = createComposeRule()

    /** Models asked for; "thumb:<w>x<h>" and "cover" load as bitmaps that size, anything else fails. */
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val handler = object : AsyncImagePreviewHandler {
        override suspend fun handle(imageLoader: ImageLoader, request: ImageRequest): AsyncImagePainter.State {
            val model = request.data as String
            asked += model
            val size = when {
                model.startsWith("thumb:") -> model.removePrefix("thumb:").split("x").map { it.toInt() }
                model == "cover" -> listOf(1400, 2100)
                else -> null
            }
            if (size == null) return AsyncImagePainter.State.Error(null, ErrorResult(null, request, IOException("no image")))
            val image = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888).asImage()
            return AsyncImagePainter.State.Success(image.asPainter(request.context), SuccessResult(image, request))
        }
    }

    private fun state(thumb: String?) = BookDetailUiState(bookId = 1, loading = false, loaded = true, thumb = thumb, cover = "cover")

    /** Shows the page at [density] (2.625 and 3 are common phone densities, 3.5 a high-resolution mode). */
    private fun show(state: BookDetailUiState, density: Float): DetailCover {
        lateinit var cover: DetailCover
        rule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density),
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides handler,
            ) {
                OttershelfTheme {
                    cover = rememberDetailCover(state.thumb)
                    BookDetailContent(state = state, cover = cover)
                }
            }
        }
        rule.waitForIdle()
        return cover
    }

    private fun aTallThumbnailIsEnough(density: Float) {
        val state = state("thumb:400x600")
        val cover = show(state, density)
        assertEquals(listOf("thumb:400x600"), asked.toList())
        assertFalse(cover.wantsFull(state))
        // The finish celebration draws the thumbnail too.
        assertEquals("thumb:400x600", cover.model(state))
    }

    @Test
    fun aTallThumbnailIsEnoughAt2_625x() = aTallThumbnailIsEnough(2.625f)

    @Test
    fun aTallThumbnailIsEnoughAt3x() = aTallThumbnailIsEnough(3f)

    @Test
    fun aSquareThumbnailFetchesTheFullCover() {
        val state = state("thumb:400x400")
        val cover = show(state, 2.625f)
        assertTrue(cover.thumbTooSmall)
        assertEquals(listOf("thumb:400x400", "cover"), asked.toList())
        assertEquals("cover", cover.model(state))
    }

    @Test
    fun aHighResolutionScreenFetchesTheFullCover() {
        val state = state("thumb:400x600")
        val cover = show(state, 3.5f)
        assertEquals(listOf("thumb:400x600", "cover"), asked.toList())
        assertEquals("cover", cover.model(state))
    }

    @Test
    fun aFailedThumbnailFetchesTheFullCover() {
        val state = state("broken")
        val cover = show(state, 2.625f)
        assertTrue(cover.thumbFailed)
        assertEquals(listOf("broken", "cover"), asked.toList())
        assertEquals("cover", cover.model(state))
    }

    @Test
    fun noThumbnailFetchesTheFullCover() {
        val state = state(null)
        val cover = show(state, 2.625f)
        assertEquals(listOf("cover"), asked.toList())
        assertEquals("cover", cover.model(state))
    }

    @Test
    fun theRule() {
        // 240 dp at 2.625x is 630 px: 504 px is enough, 503 isn't.
        assertFalse(thumbTooSmall(600f, 630f))
        assertFalse(thumbTooSmall(504f, 630f))
        assertTrue(thumbTooSmall(503f, 630f))
        // 3x: 720 px, a 2:3 thumbnail still fits; 3.5x: 840 px, it doesn't.
        assertFalse(thumbTooSmall(600f, 720f))
        assertTrue(thumbTooSmall(600f, 840f))
        // A painter with no size of its own keeps the thumbnail.
        assertFalse(thumbTooSmall(Float.NaN, 630f))
    }
}
