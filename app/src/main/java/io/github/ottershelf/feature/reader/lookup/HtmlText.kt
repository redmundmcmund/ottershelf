package io.github.ottershelf.feature.reader.lookup

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Wikimedia's HTML snippets (Wiktionary definitions and examples, Wikipedia's `extract_html`) as
 * plain styled text: bold and italic kept, every other tag dropped (links become their text),
 * `<style>` and `<script>` contents dropped, entities decoded, whitespace collapsed. Never shown in
 * a WebView. Pure (`LookupTextTest`).
 */
internal object HtmlText {

    fun toAnnotated(html: String): AnnotatedString {
        val runs = mutableListOf<Run>()
        var bold = 0
        var italic = 0
        var skipping: String? = null
        var i = 0
        val text = StringBuilder()
        fun flush() {
            if (text.isEmpty()) return
            runs += Run(decode(text.toString()), bold > 0, italic > 0)
            text.setLength(0)
        }
        while (i < html.length) {
            val c = html[i]
            if (c != '<') {
                if (skipping == null) text.append(c)
                i++
                continue
            }
            if (html.startsWith("<!--", i)) {
                val close = html.indexOf("-->", i + 4)
                i = if (close < 0) html.length else close + 3
                continue
            }
            val close = html.indexOf('>', i + 1)
            if (close < 0) {
                // A stray '<' with no tag after it is text.
                if (skipping == null) text.append(c)
                i++
                continue
            }
            val tag = html.substring(i + 1, close)
            i = close + 1
            val closing = tag.startsWith("/")
            val name = tag.removePrefix("/").takeWhile { it.isLetterOrDigit() }.lowercase()
            if (name.isEmpty()) {
                if (skipping == null) text.append('<').append(tag).append('>')
                continue
            }
            if (skipping != null) {
                if (closing && name == skipping) skipping = null
                continue
            }
            val selfClosing = tag.endsWith("/")
            when {
                name in SKIPPED -> if (!closing && !selfClosing) { flush(); skipping = name }
                name in BOLD -> { flush(); bold = (bold + if (closing) -1 else 1).coerceAtLeast(0) }
                name in ITALIC -> { flush(); italic = (italic + if (closing) -1 else 1).coerceAtLeast(0) }
                name in BREAKS -> text.append(' ')
            }
        }
        flush()
        return build(runs)
    }

    /** Just the text of [html]. */
    fun toPlain(html: String): String = toAnnotated(html).text

    private class Run(val text: String, val bold: Boolean, val italic: Boolean)

    private fun build(runs: List<Run>): AnnotatedString {
        val builder = AnnotatedString.Builder()
        var lastSpace = true
        for (run in runs) {
            val collapsed = StringBuilder()
            for (ch in run.text) {
                val space = ch.isWhitespace() || ch.code == 0xA0
                if (space) {
                    if (!lastSpace) collapsed.append(' ')
                } else {
                    collapsed.append(ch)
                }
                lastSpace = space
            }
            if (collapsed.isEmpty()) continue
            val style = when {
                run.bold && run.italic -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                run.bold -> SpanStyle(fontWeight = FontWeight.Bold)
                run.italic -> SpanStyle(fontStyle = FontStyle.Italic)
                else -> null
            }
            if (style == null) builder.append(collapsed.toString()) else builder.withStyle(style) { append(collapsed.toString()) }
        }
        val built = builder.toAnnotatedString()
        // A trailing space (only ever one) is cut, spans with it.
        return if (built.text.endsWith(' ')) built.subSequence(0, built.length - 1) else built
    }

    private val SKIPPED = setOf("style", "script", "noscript", "template")
    private val BOLD = setOf("b", "strong")
    private val ITALIC = setOf("i", "em")
    private val BREAKS = setOf("br", "p", "div", "li", "ul", "ol", "dl", "dd", "dt", "tr", "td", "th", "table", "hr")

    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")

    internal fun decode(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.substring(2).toIntOrNull(16)) ?: m.value
                body.startsWith("#") -> codePoint(body.substring(1).toIntOrNull()) ?: m.value
                else -> NAMED[body] ?: m.value
            }
        }
    }

    private fun codePoint(cp: Int?): String? =
        if (cp == null || cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) null else String(Character.toChars(cp))

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to Char(0xA0).toString(),
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“",
        "rdquo" to "”", "laquo" to "«", "raquo" to "»", "middot" to "·", "thinsp" to Char(0x2009).toString(), "ensp" to Char(0x2002).toString(),
        "emsp" to Char(0x2003).toString(), "shy" to "", "zwj" to "", "zwnj" to "", "times" to "×", "deg" to "°", "prime" to "′",
    )
}
