package io.github.ottershelf.feature.quotes

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import io.github.ottershelf.feature.notes.model.BookAnnotationStats
import io.github.ottershelf.feature.notes.HighlightsSummary
import io.github.ottershelf.feature.notes.HighlightsSummaryCard
import io.github.ottershelf.testing.PHONE_HEIGHT_DP
import io.github.ottershelf.testing.PHONE_WIDTH_DP
import io.github.ottershelf.testing.SCREENSHOT_DIR
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Quotes, dark: quotes/..._dark.png. The camera itself can't run here, so the steps after it are shown. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class QuotesScreenshotTest {

    @Test
    fun form() = captureDark("quotes/add_quote", heightDp = 1100) {
        AddQuoteContent(
            state = AddQuoteUiState(
                book = QuoteBook(1, "Pride and Prejudice", "Jane Austen", fakeCover(1)),
                bookFixed = true,
                text = "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.",
                pageFrom = "1",
                pageTo = "2",
                note = "The irony starts in the very first line.",
                color = "#F472B6",
                photo = File("page.jpg"),
                keepPhoto = true,
            ),
            actions = AddQuoteActions(),
        )
    }

    @Test
    fun picker() = captureDark("quotes/book_picker", heightDp = 620) {
        Column(Modifier.padding(top = 16.dp)) {
            BookPickerContent(
                PickerState(
                    loading = false,
                    books = listOf(
                        QuoteBook(1, "Pride and Prejudice", "Jane Austen", fakeCover(1)),
                        QuoteBook(2, "Middlemarch", "George Eliot", fakeCover(2)),
                        QuoteBook(3, "Far from the Madding Crowd", "Thomas Hardy", null),
                    ),
                ),
                onQuery = {}, onPick = {},
            )
        }
    }

    @Test
    fun pickLines() = captureDark("quotes/pick_lines") {
        val (bitmap, page) = samplePage()
        ScanContent(ScanStep.Picking(File("page.jpg"), bitmap, page, selected = page.lines.drop(2).take(4).map { it.key }.toSet()))
    }

    @Test
    fun bookPageEntry() = captureDark("quotes/book_entry", heightDp = 420) {
        Column(Modifier.padding(20.dp)) {
            HighlightsSummaryCard(HighlightsSummary(loading = false, stats = BookAnnotationStats()), onOpen = {}, onRetry = {})
            QuoteButtons(1, "Pride and Prejudice", AppNavigator.None, Modifier.padding(top = 10.dp))
        }
    }

    /** A made-up photographed page: serif lines on paper, with their boxes as recognition would give them. */
    private fun samplePage(): Pair<Bitmap, OcrPage> {
        val texts = listOf(
            "CHAPTER I" to 0,
            "" to -1,
            "It is a truth universally acknowledged, that a" to 1,
            "single man in possession of a good fortune, must" to 1,
            "be in want of a wife. However little known the" to 1,
            "feelings or views of such a man may be on his" to 1,
            "first entering a neighbourhood, this truth is so" to 1,
            "well fixed in the minds of the surrounding fami-" to 1,
            "lies, that he is considered the rightful property" to 1,
            "of some one or other of their daughters." to 1,
            "" to -1,
            "12" to 2,
        )
        val bitmap = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFFF1E9D8.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2522.toInt(); textSize = 40f; typeface = Typeface.SERIF }
        val lines = mutableListOf<OcrLine>()
        var y = 160f
        val counters = mutableMapOf<Int, Int>()
        for ((text, block) in texts) {
            if (block >= 0) {
                val x = if (block == 1) 80f else (1000 - paint.measureText(text)) / 2
                canvas.drawText(text, x, y, paint)
                val index = counters.getOrDefault(block, 0)
                counters[block] = index + 1
                lines += OcrLine(block, index, text, x, y - 32f, x + paint.measureText(text), y + 10f)
            }
            y += 70f
        }
        return bitmap to OcrPage(bitmap.width, bitmap.height, lines)
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureDark(name: String, heightDp: Int = PHONE_HEIGHT_DP, content: @Composable () -> Unit) = captureRoboImage(
        filePath = "$SCREENSHOT_DIR/${name}_dark.png",
        roborazziComposeOptions = RoborazziComposeOptions {
            size(widthDp = PHONE_WIDTH_DP, heightDp = heightDp)
            uiMode(Configuration.UI_MODE_NIGHT_YES)
        },
    ) {
        OttershelfTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                WithFakeCovers { content() }
            }
        }
    }
}
