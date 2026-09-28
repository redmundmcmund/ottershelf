package io.github.ottershelf.feature.quotes

/** A recognised line of a photographed page: its [block] and place in it, and its box in image px. */
data class OcrLine(
    val block: Int,
    val index: Int,
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val key: Long get() = block.toLong() shl 20 or index.toLong()

    fun contains(x: Float, y: Float, slop: Float = 0f): Boolean =
        x >= left - slop && x <= right + slop && y >= top - slop && y <= bottom + slop
}

/** What text recognition found on a photo: the image size it measured in and the lines, in reading order. */
data class OcrPage(val width: Int, val height: Int, val lines: List<OcrLine>)

/** Turns the lines the user picked into the quote's text. Pure. */
object QuoteCleanup {
    private val hyphens = setOf('-', '‐', '­', '¬')
    private val sentenceEnd = setOf('.', '!', '?', ':', '"', '”', '’', '…', ')')
    private val ligatures = mapOf(
        "ﬀ" to "ff", "ﬁ" to "fi", "ﬂ" to "fl", "ﬃ" to "ffi", "ﬄ" to "ffl",
    )

    /**
     * [lines] joined in reading order (block, then line):
     * - a word broken at a line end ("remem-" + "ber") is joined, the hyphen dropped when the next
     *   line starts in lower case (kept for "Anglo-" + "Saxon");
     * - other line breaks become spaces;
     * - a new block after a line ending a sentence starts a new paragraph (a line break);
     * - ligatures become letters, spaces are collapsed, and no space is left before punctuation.
     */
    fun join(lines: List<OcrLine>): String {
        val out = StringBuilder()
        var lastBlock: Int? = null
        for (line in lines.sortedWith(compareBy({ it.block }, { it.index }))) {
            val text = normalise(line.text)
            if (text.isEmpty()) continue
            if (out.isNotEmpty()) {
                val last = out.last()
                val broken = last in hyphens && out.length >= 2 && out[out.length - 2].isLetter()
                when {
                    broken && text.first().isLowerCase() -> out.setLength(out.length - 1)
                    broken && last != '-' -> {
                        out.setLength(out.length - 1)
                        out.append('-')
                    }
                    broken -> Unit
                    lastBlock != line.block && last in sentenceEnd -> out.append('\n')
                    else -> out.append(' ')
                }
            }
            out.append(text)
            lastBlock = line.block
        }
        return tidy(out.toString())
    }

    private fun normalise(text: String): String {
        var t = text
        ligatures.forEach { (from, to) -> t = t.replace(from, to) }
        return t.replace(Regex("[\\t\\u00A0 ]+"), " ").trim()
    }

    private fun tidy(text: String): String =
        text.replace(Regex(" +([,.;:!?…)])"), "$1")
            .replace(Regex("([(]) +"), "$1")
            .replace(Regex(" {2,}"), " ")
            .trim()
}
