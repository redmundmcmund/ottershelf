package io.github.ottershelf.feature.quotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tesseract's lines turned into the picker's blocks and lines, and the bundled model's size. */
class OcrLayoutTest {

    private fun tess(text: String?, top: Int, starts: Boolean = false, left: Int = 100, right: Int = 900) =
        TessLine(text, left, top, right, top + 40, starts)

    @Test
    fun eachParagraphIsABlockWithItsLinesNumberedFromZero() {
        val lines = OcrLayout.lines(
            listOf(
                tess("It was the best of times,\n", 100, starts = true),
                tess("it was the worst of times.\n", 150),
                tess("A new paragraph begins here\n", 220, starts = true),
                tess("and goes on.\n", 270),
            ),
        )
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1), lines.map { it.block to it.index })
        assertEquals("It was the best of times,", lines[0].text)
        assertEquals(OcrLine(1, 1, "and goes on.", 100f, 270f, 900f, 310f), lines[3])
        // The keys the picker selects by stay distinct.
        assertEquals(4, lines.map { it.key }.toSet().size)
    }

    @Test
    fun theFirstLineStartsABlockEvenUnmarked() {
        val lines = OcrLayout.lines(listOf(tess("no paragraph mark", 100), tess("second", 150)))
        assertEquals(listOf(0 to 0, 0 to 1), lines.map { it.block to it.index })
    }

    @Test
    fun linesWithoutLettersOrDigitsOrWithAnEmptyBoxAreDropped() {
        val lines = OcrLayout.lines(
            listOf(
                tess("| , ~\n", 60, starts = true), // the gutter's shadow
                tess("Chapter 7\n", 100),
                tess(null, 130),
                tess("   \n", 140),
                tess("empty box", 150, left = 400, right = 400),
                tess("the text", 200),
            ),
        )
        assertEquals(listOf("Chapter 7", "the text"), lines.map { it.text })
        assertEquals(listOf(0, 1), lines.map { it.index })
    }

    @Test
    fun joinedLikeTheLinesItWasMadeFor() {
        // QuoteCleanup.join's rules keep working on Tesseract's grouping: a broken word joined, a new
        // paragraph after a sentence end on a line of its own.
        val lines = OcrLayout.lines(
            listOf(
                tess("The user could not remem-", 100, starts = true),
                tess("ber the name.", 150),
                tess("Then it came back.", 220, starts = true),
            ),
        )
        assertEquals("The user could not remember the name.\nThen it came back.", QuoteCleanup.join(lines))
    }

    @Test
    fun theModelSizeMatchesTheBundledAsset() {
        val asset = File("src/main/assets/" + Tessdata.ASSET)
        assertTrue("missing ${asset.absolutePath}", asset.isFile)
        assertEquals(asset.length(), Tessdata.SIZE)
    }
}
