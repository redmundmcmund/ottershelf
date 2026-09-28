package io.github.ottershelf.devicetest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import io.github.ottershelf.feature.quotes.OcrPage
import io.github.ottershelf.feature.quotes.PageReader
import io.github.ottershelf.feature.quotes.QuoteCleanup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The quotes' page reading on the phone ([PageReader.recognise]: Tesseract with the bundled English
 * model, which it copies to `noBackupFilesDir/tessdata/` the first time, as the app does): a page of
 * text drawn into a bitmap is read back with most of its words, and each drawn line has a
 * recognised line boxed over it. The page is saved to `device-tests/ocr/` and the times and texts
 * go to `report.txt`.
 */
@RunWith(AndroidJUnit4::class)
class OcrDeviceTest {

    private class Page(val bitmap: Bitmap, val words: List<String>, val lineBands: List<IntRange>)

    /** [paragraphs] set in a serif face on an off-white page, as a photo of a book page reads. */
    private fun draw(paragraphs: List<String>, width: Int = 1600, textSize: Float = 44f): Page {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(28, 28, 28)
            this.textSize = textSize
            typeface = Typeface.SERIF
        }
        val margin = 120
        val layouts = paragraphs.map {
            StaticLayout.Builder.obtain(it, 0, it.length, paint, width - 2 * margin)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.25f)
                .build()
        }
        val gap = (textSize * 1.2f).toInt()
        val height = margin * 2 + layouts.sumOf { it.height } + gap * (layouts.size - 1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(246, 242, 232))
        val bands = mutableListOf<IntRange>()
        var y = margin
        for (layout in layouts) {
            canvas.save()
            canvas.translate(margin.toFloat(), y.toFloat())
            layout.draw(canvas)
            canvas.restore()
            for (i in 0 until layout.lineCount) bands += (y + layout.getLineTop(i))..(y + layout.getLineBaseline(i))
            y += layout.height + gap
        }
        return Page(bitmap, paragraphs.flatMap(::words), bands)
    }

    private fun words(text: String): List<String> =
        text.split(Regex("\\s+")).map { w -> w.filter(Char::isLetterOrDigit).lowercase() }.filter { it.isNotEmpty() }

    private fun read(name: String, page: Page): OcrPage {
        File(Device.outDir, "ocr/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { page.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val start = System.nanoTime()
        val result = runBlocking(Dispatchers.Default) { PageReader.recognise(Device.context, page.bitmap) }
        val ms = (System.nanoTime() - start) / 1_000_000
        Device.report("ocr $name (${page.bitmap.width}x${page.bitmap.height}): $ms ms, ${result.lines.size} lines in ${result.lines.map { it.block }.distinct().size} blocks")
        result.lines.forEach { Device.report("  [${it.block}.${it.index}] (${it.left.toInt()},${it.top.toInt()})-(${it.right.toInt()},${it.bottom.toInt()}) ${it.text}") }
        return result
    }

    @Test
    fun aPrintedPageIsReadWithItsLinesBoxed() {
        val page = draw(
            listOf(
                "The lighthouse keeper climbed the narrow stairs every evening at dusk, counting each of the " +
                    "hundred and twelve steps aloud, as his father had done before him and his grandfather before that.",
                "Nobody in the village remembered when the lamp had last failed. The ships passed safely along " +
                    "the rocky coast, and the sailors, who never saw his face, raised their caps to the light.",
            ),
        )
        val result = read("page", page)
        assertEquals(page.bitmap.width, result.width)
        assertEquals(page.bitmap.height, result.height)

        // Most words read (nine in ten: Tesseract may miss a word or misread a letter).
        val read = QuoteCleanup.join(result.lines)
        val found = words(read).toMutableList()
        val hits = page.words.count { found.remove(it) }
        Device.report("ocr page: $hits of ${page.words.size} words; text: $read")
        assertTrue("only $hits of ${page.words.size} words read: $read", hits >= page.words.size * 9 / 10)

        // Every box lies on the page, in reading order, and every drawn line has a box over it.
        for (line in result.lines) {
            assertTrue("box off the page: $line", line.left >= 0 && line.top >= 0 && line.right <= result.width && line.bottom <= result.height)
            assertTrue("empty box: $line", line.right > line.left && line.bottom > line.top)
        }
        assertEquals(result.lines.sortedWith(compareBy({ it.block }, { it.index })), result.lines)
        for (band in page.lineBands) {
            val middle = (band.first + band.last) / 2f
            assertTrue("no recognised line over the drawn line at y=$band", result.lines.any { middle in it.top..it.bottom })
        }
        assertTrue("fewer lines read (${result.lines.size}) than drawn (${page.lineBands.size})", result.lines.size >= page.lineBands.size)
    }

    @Test
    fun aBlankPageHasNoLines() {
        val blank = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(246, 242, 232)) }
        val result = read("blank", Page(blank, emptyList(), emptyList()))
        assertTrue(result.lines.toString(), result.lines.isEmpty())
    }

    @Test
    fun aCancelledReadStopsAndTheNextOneStillWorks() {
        val text = "A long page of text to read, so that the reading is still going when it is cancelled. "
        val page = draw(List(6) { text.repeat(4) }, width = 2400, textSize = 34f)
        val start = System.nanoTime()
        runBlocking(Dispatchers.Default) {
            val reading = async { PageReader.recognise(Device.context, page.bitmap) }
            delay(300)
            reading.cancel()
            try {
                reading.await()
                Device.report("ocr cancel: the page was read before the cancel arrived")
            } catch (_: CancellationException) {
                Device.report("ocr cancel: stopped after ${(System.nanoTime() - start) / 1_000_000} ms")
            }
        }
        // The engine was recycled cleanly: a new read works.
        val again = read("after_cancel", draw(listOf("The second reading works as the first one did.")))
        val text2 = QuoteCleanup.join(again.lines)
        if (!text2.contains("second", ignoreCase = true)) fail("the read after a cancel found: $text2")
    }
}
