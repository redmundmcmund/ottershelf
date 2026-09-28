package io.github.ottershelf.feature.reader

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** A book page's colours, resolved: text, page, links, and whether the page is dark. */
data class PageColors(val foreground: Color, val background: Color, val link: Color, val dark: Boolean)

/**
 * What reader.js applies to the page (`readerOpen`'s and `readerSettings`' `settings`): the
 * chapters' CSS (foliate's renderer styles, which it puts in every chapter as it loads, so they
 * survive chapter changes), the page colours behind the book, and the paginator's layout
 * attributes. Field names are reader.js's.
 */
@Serializable
internal data class PageSettings(
    val css: String,
    val bg: String,
    val fg: String,
    val dark: Boolean,
    val flow: String,
    val animated: Boolean,
    val columns: String,
    val gap: String,
    val margin: String,
    val maxInlineSize: String,
    val maxBlockSize: String,
    val fixedLayoutSpread: String,
)

/**
 * The reading settings as the page's styles. Pure, so the mapping is unit-tested
 * (`ReaderStylesTest`): the settings the reader always had produce exactly the CSS reader.js used
 * to build itself; the spacing ones are the web reader's rules (useReaderState.ts `generateCSS`),
 * each written only when set, so the book's own styling is left alone otherwise.
 */
internal object ReaderStyles {

    /** The paginator's page margin (above and below the text), as reader.js always had it. */
    const val MARGIN = "28px"
    const val MAX_BLOCK_SIZE = "1440px"

    /** The reader's font stacks for `font` (the book's own for `publisher`). */
    private val FONTS = mapOf(
        ReaderPrefs.FONT_SERIF to "Georgia, \"Noto Serif\", serif",
        ReaderPrefs.FONT_SANS to "\"Roboto\", \"Noto Sans\", sans-serif",
    )

    /** reader.js' page colours for the App theme before the app's are known. */
    private val APP_FALLBACK = PageColors(Color(0xFF1B1B1B), Color(0xFFFFFFFF), Color(0xFF1A4FD6), dark = false)

    /** Links on a custom page: the app's accent when it reads well there (3:1), else the text colour. */
    private const val LINK_MIN_CONTRAST = 3.0

    fun pageColors(prefs: ReaderPrefs, app: ReaderAppColors?): PageColors = when (val theme = ReaderTheme.of(prefs.theme)) {
        ReaderTheme.APP -> app?.let { PageColors(it.foreground, it.background, it.link, it.dark) } ?: APP_FALLBACK
        ReaderTheme.CUSTOM -> {
            val p = prefs.normalized()
            val bg = PageColor.parse(p.customBg) ?: PageColor.parse(ReaderPrefs.DEFAULT_CUSTOM_BG)!!
            val fg = PageColor.parse(p.customFg) ?: PageColor.parse(ReaderPrefs.DEFAULT_CUSTOM_FG)!!
            val accent = app?.link ?: APP_FALLBACK.link
            val link = if (PageColor.contrast(accent, bg) >= LINK_MIN_CONTRAST) accent else fg
            PageColors(fg, bg, link, dark = PageColor.isDark(bg))
        }
        else -> PageColors(theme.foreground!!, theme.background!!, theme.link!!, dark = theme == ReaderTheme.DARK || theme == ReaderTheme.BLACK)
    }

    /** The chapters' CSS for [prefs] on [colors]. */
    fun css(prefs: ReaderPrefs, colors: PageColors): String {
        val p = prefs.normalized()
        val font = FONTS[p.font]
        // "Original" keeps the publisher's styling apart from size; every other page colour overrides colours.
        val recolor = ReaderTheme.of(p.theme) != ReaderTheme.ORIGINAL
        val fg = PageColor.hex(colors.foreground)
        val hyphens = if (p.hyphenate) "auto" else "manual"
        return buildString {
            append("\n    html { color-scheme: ${if (colors.dark) "dark" else "light"}; font-size: ${p.fontSize}px !important; }\n")
            if (recolor) {
                append("    html, body { color: $fg !important; background: ${PageColor.hex(colors.background)} !important; }\n")
                append("      body * { color: inherit !important; background-color: transparent !important; border-color: ${fg}33 !important; }\n")
                append("      a:link, a:visited, a:link *, a:visited * { color: ${PageColor.hex(colors.link)} !important; }\n")
            }
            if (font != null) append("    body, body * { font-family: $font !important; }\n")
            append("    p, li, blockquote, dd {\n")
            append("      line-height: ${number(p.lineHeight)} !important;\n")
            append("      text-align: ${if (p.justify) "justify" else "start"};\n")
            append("      hyphens: $hyphens;\n")
            append("      -webkit-hyphens: $hyphens;\n")
            append("    }\n")
            append("    img, svg { max-width: 100%; height: auto; }\n")
            append("    aside[role~=\"doc-footnote\"] { display: none; }\n")
            // The web reader's spacing rules, only when set.
            if (p.paragraphSpacing > 0.0) {
                append("    p { margin-block: 0 ${number(p.paragraphSpacing)}em !important; }\n")
                append("    :is(hgroup, header) p { margin-block: unset !important; }\n")
            }
            val inline = listOfNotNull(
                p.letterSpacing?.let { "letter-spacing: ${number(it)}em !important;" },
                p.wordSpacing?.let { "word-spacing: ${number(it)}em !important;" },
            )
            if (inline.isNotEmpty()) append("    p, li, blockquote, dd { ${inline.joinToString(" ")} }\n")
            p.textIndent?.let {
                append("    p { text-indent: ${number(it)}em !important; }\n")
                append("    :is(hgroup, header, figure, figcaption, blockquote, li) > p { text-indent: unset !important; }\n")
            }
            append("  ")
        }
    }

    /** Everything reader.js applies for [prefs], with the app theme's page colours [app] (null until known). */
    fun pageSettings(prefs: ReaderPrefs, app: ReaderAppColors?): PageSettings {
        val p = prefs.normalized()
        val colors = pageColors(p, app)
        return PageSettings(
            css = css(p, colors),
            bg = PageColor.hex(colors.background),
            fg = PageColor.hex(colors.foreground),
            dark = colors.dark,
            flow = if (p.scrolled) ReaderPrefs.FLOW_SCROLLED else ReaderPrefs.FLOW_PAGINATED,
            animated = p.animated,
            columns = p.columns.toString(),
            gap = "${(p.margin * 100).roundToInt()}%",
            margin = MARGIN,
            maxInlineSize = "${p.maxInlineSize}px",
            maxBlockSize = MAX_BLOCK_SIZE,
            fixedLayoutSpread = p.fixedLayoutSpread,
        )
    }

    /** A number as JavaScript prints it in a template literal: `1.5`, `2`, `0.05`. */
    fun number(value: Double): String =
        BigDecimal(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

/** Page colours as `#rrggbb`, and the WCAG contrast between two of them. Pure. */
object PageColor {

    /** Readable body text: WCAG AA's 4.5:1. Below it the settings warn. */
    const val MIN_CONTRAST = 4.5

    /** `#rrggbb` (or `rrggbb`, `#rgb`) as a colour; null if it isn't one. */
    fun parse(hex: String?): Color? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        val full = when {
            h.length == 3 && h.all { it.isHex() } -> h.map { "$it$it" }.joinToString("")
            h.length == 6 && h.all { it.isHex() } -> h
            else -> return null
        }
        return Color(0xFF000000 or full.toLong(16))
    }

    /** [hex] as the stored form (`#rrggbb`, lower case), or null if it isn't a colour. */
    fun normalize(hex: String?): String? = parse(hex)?.let(::hex)

    fun hex(color: Color): String {
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255).roundToInt().toString(16).padStart(2, '0')
        return "#${c(color.red)}${c(color.green)}${c(color.blue)}"
    }

    /** WCAG relative luminance. */
    fun luminance(color: Color): Double {
        fun channel(v: Float): Double {
            val c = v.toDouble()
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    /** WCAG contrast ratio, 1..21. */
    fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** A page reads as dark when white text would stand out on it more than black. */
    fun isDark(background: Color): Boolean = contrast(background, Color.White) > contrast(background, Color.Black)

    /** Hue (0..360), saturation and lightness (0..1): the inverse of `Color.hsl`. */
    fun toHsl(color: Color): FloatArray {
        val r = color.red
        val g = color.green
        val b = color.blue
        val hi = maxOf(r, g, b)
        val lo = minOf(r, g, b)
        val l = (hi + lo) / 2f
        val d = hi - lo
        if (d < 1e-6f) return floatArrayOf(0f, 0f, l)
        val s = (d / (1f - kotlin.math.abs(2f * l - 1f))).coerceIn(0f, 1f)
        val h = when (hi) {
            r -> 60f * (((g - b) / d).mod(6f))
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf(h.mod(360f), s, l)
    }

    /**
     * A ratio as the settings show it, one decimal, cut rather than rounded: 4.478 is "4.4", so a
     * ratio under [MIN_CONTRAST] never reads "4.5" beside the warning. A hair of slack for floating
     * point (black on white can come out as 20.999…, and shows 21.0).
     */
    fun ratioText(ratio: Double): String = String.format(Locale.ROOT, "%.1f", floor(ratio * 10 + 1e-9) / 10)

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
