package io.github.ottershelf.feature.pdf

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import io.github.ottershelf.testing.PHONE_HEIGHT_DP
import io.github.ottershelf.testing.PHONE_WIDTH_DP
import io.github.ottershelf.testing.SCREENSHOT_DIR
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The PDF reader over made-up pages (PdfRenderer isn't there under Robolectric): the strip with its
 * bars and a search on, the settings sheet, the contents (outline) and the search results. Dark only.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class PdfScreenshotTest {

    private val hits = listOf(
        PdfHit(2, listOf(PdfRect(72f, 140f, 160f, 156f)), "…the ship left orbit at dawn, and the crew…", 15, 5),
        PdfHit(2, listOf(PdfRect(300f, 420f, 380f, 436f)), "…a stable orbit around the second moon…", 10, 5),
        PdfHit(7, listOf(PdfRect(90f, 600f, 170f, 616f)), "…no orbit is forever, said the captain…", 4, 5),
    )

    private val outline = listOf(
        OutlineEntry("Preface", 0, 0),
        OutlineEntry("1. Leaving Earth", 1, 0),
        OutlineEntry("1.1 The launch window", 2, 1),
        OutlineEntry("1.2 Transfer orbits", 4, 1),
        OutlineEntry("2. The long coast", 6, 0),
        OutlineEntry("3. Arrival", 9, 0),
    )

    private val state = PdfUiState(
        title = "An Introduction to Orbital Mechanics",
        phase = PdfPhase.Ready,
        pageCount = 12,
        sizes = PdfPageSizes.uniform(12, 612f, 792f),
        page = 3,
        chromeVisible = true,
        outline = outline,
        search = PdfSearch(query = "orbit", hits = hits, searched = 12, selected = 0),
        source = FakePages,
    )

    @Test
    fun reader() = captureDark("pdf/reader_chrome") {
        PdfContent(state)
    }

    @Test
    fun settings() = captureDark("pdf/settings") {
        Surface(color = OttershelfTheme.colors.card) {
            Column(Modifier.padding(top = 24.dp)) {
                PdfSettingsContent(PdfView(PdfScroll.Continuous, PdfFit.Width, night = true), customized = true, actions = PdfSettingsActions())
            }
        }
    }

    @Test
    fun contents() = captureDark("pdf/contents") {
        Surface(color = OttershelfTheme.colors.card) {
            Column(Modifier.padding(top = 24.dp)) { PdfContentsContent(state.copy(page = 5), onPage = {}) }
        }
    }

    @Test
    fun search() = captureDark("pdf/search") {
        Surface(color = OttershelfTheme.colors.card) {
            Column(Modifier.padding(top = 24.dp)) { PdfSearchContent(state.search, 12, onSearch = {}, onPick = {}) }
        }
    }

    /** Grey bars standing in for text, a heading and the page number, on white. */
    private object FakePages : PdfPageSource {
        private val cache = HashMap<Pair<Int, Int>, ImageBitmap>()

        override fun cached(index: Int, width: Int): ImageBitmap = cache.getOrPut(index to width) { draw(index, width, (width * 792f / 612f).toInt()) }
        override suspend fun render(index: Int, width: Int, height: Int): ImageBitmap = cached(index, width)
        override suspend fun renderRegion(index: Int, fullWidth: Int, fullHeight: Int, region: IntRect): ImageBitmap? = null
        override fun linkAt(index: Int, x: Float, y: Float): PdfLink? = null

        private fun draw(index: Int, width: Int, height: Int): ImageBitmap {
            val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            val canvas = Canvas(bitmap)
            val s = width / 612f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt() }
            canvas.drawRect(72 * s, 72 * s, 340 * s, 92 * s, paint)
            paint.color = 0xFF9A9A9A.toInt()
            var y = 130f
            var line = 0
            while (y < 720f) {
                val end = if (line % 7 == 6) 380f else 540f - (line * 37 % 50)
                canvas.drawRect(72 * s, y * s, end * s, (y + 8) * s, paint)
                y += 18f
                line++
                if (line % 9 == 0) y += 14f
            }
            paint.color = 0xFF666666.toInt()
            paint.textSize = 12 * s
            canvas.drawText((index + 1).toString(), 300 * s, 760 * s, paint)
            return bitmap.asImageBitmap()
        }
    }
}

@OptIn(ExperimentalRoborazziApi::class)
private fun captureDark(name: String, content: @Composable () -> Unit) {
    captureRoboImage(
        filePath = "$SCREENSHOT_DIR/${name}_dark.png",
        roborazziComposeOptions = RoborazziComposeOptions {
            size(widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP)
            uiMode(Configuration.UI_MODE_NIGHT_YES)
        },
    ) {
        OttershelfTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { WithFakeCovers { content() } }
        }
    }
}
