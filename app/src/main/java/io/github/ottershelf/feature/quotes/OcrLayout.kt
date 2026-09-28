package io.github.ottershelf.feature.quotes

/**
 * One text line as Tesseract's result iterator reports it ([PageReader], at the text-line level):
 * its text, its box in image px, and whether it is the first line of a paragraph.
 */
data class TessLine(
    val text: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val startsParagraph: Boolean,
)

/** Tesseract's lines as the line picker's [OcrLine]s. Pure (`OcrLayoutTest`). */
object OcrLayout {

    /**
     * [lines], in Tesseract's reading order, as [OcrLine]s:
     * - each paragraph Tesseract finds is a block, numbered in order, its lines numbered from 0
     *   (so [QuoteCleanup.join] starts a new line where a paragraph after a sentence end begins);
     * - the text is trimmed (Tesseract ends each line with a line break);
     * - a line without a letter or a digit (a rule, specks along the page edge, the gutter's shadow
     *   read as `| ,`) or with an empty box is dropped, and so doesn't count in its paragraph.
     */
    fun lines(lines: List<TessLine>): List<OcrLine> {
        val out = ArrayList<OcrLine>(lines.size)
        var block = -1
        var index = 0
        for (line in lines) {
            if (line.startsParagraph || block < 0) {
                block++
                index = 0
            }
            val text = line.text?.trim().orEmpty()
            if (text.none(Char::isLetterOrDigit)) continue
            if (line.right <= line.left || line.bottom <= line.top) continue
            out += OcrLine(block, index++, text, line.left.toFloat(), line.top.toFloat(), line.right.toFloat(), line.bottom.toFloat())
        }
        return out
    }
}
