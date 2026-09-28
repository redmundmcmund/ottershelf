package io.github.ottershelf.feature.reader

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import io.github.ottershelf.core.sync.ProgressStore.Position

/**
 * A location reported by the page (reader.js). `onRelocate` carries only the cheap fields
 * ([fraction], [page], [pages], [turned]); `onLocation` and `readerLocation()` carry everything the
 * server's progress endpoint takes plus the chapter.
 */
@Serializable
data class Relocate(
    val cfi: String? = null,
    val fraction: Double = 0.0,
    val source: String? = null,
    val koboLocationType: JsonElement? = null,
    val koboLocationValue: JsonElement? = null,
    val contentSourceProgressPercent: Double? = null,
    val koreaderProgress: JsonElement? = null,
    val tocLabel: String? = null,
    val tocHref: String? = null,
    val page: Int? = null,
    val pages: Int? = null,
    /** The user moved (a page turn, swipe or scroll), not foliate settling a section or opening somewhere. */
    val turned: Boolean = false,
    /** foliate's reading time left in the chapter and the book, and the whole book's, in its minutes (relocates only). */
    val timeSection: Double? = null,
    val timeTotal: Double? = null,
    val timeBook: Double? = null,
    /** Scrolled flow: at the end of a chapter with another after it (relocates only). */
    val chapterEnd: Boolean = false,
    /**
     * At the end of the book, nothing left to turn or scroll to: its last page, the bottom of its
     * last section, or a fixed-layout book's last page on screen (relocates only; reader.js
     * atBookEnd). [fraction] can't say it: paginated, a long book's last few pages all come within
     * half a percent of 1; scrolled, it falls short at the bottom; a spread reports its first page.
     */
    val bookEnd: Boolean = false,
)

/** A table-of-contents entry, flattened; [depth] 0 is the top level. */
@Serializable
data class TocEntry(val label: String, val href: String? = null, val depth: Int = 0)

@Serializable
data class Opened(
    val title: String? = null,
    val toc: List<TocEntry> = emptyList(),
    /** A fixed-layout (pre-paginated) book: pages as the publisher laid them out, no text settings. */
    val fixedLayout: Boolean = false,
)

/**
 * The reader's settings, kept on this device (DataStore `reader.prefs`). [ReaderStyles] turns them
 * into what reader.js applies (the chapters' CSS, page colours, the paginator's layout).
 *
 * The original keys keep their names; the ones added since are named, valued and ranged as the web
 * reader's (packages/types reader-settings.ts `EpubReaderSettings`), so these can move to the
 * server's `reader/defaults/epub` later. [customBg] and [customFg] have no web equivalent.
 */
@Serializable
data class ReaderPrefs(
    /** A [ReaderTheme.id]. */
    val theme: String = ReaderTheme.APP.id,
    /** CSS px (dp on the phone). */
    val fontSize: Int = 18,
    val lineHeight: Double = 1.5,
    val justify: Boolean = true,
    val hyphenate: Boolean = false,
    /** `publisher` (the book's own), `serif` or `sans`. */
    val font: String = FONT_PUBLISHER,
    /** Space at the sides and between columns, as a fraction of the width (foliate's `gap`). */
    val margin: Double = 0.06,
    /** Columns side by side at most (foliate shows one in portrait regardless). */
    val columns: Int = 2,
    /** `paginated` or `scrolled` (foliate's `flow`). */
    val flow: String = FLOW_PAGINATED,
    /** Slide page turns (foliate's `animated`). */
    val animated: Boolean = true,
    /** Space after each paragraph, em; 0 keeps the book's own ([PARAGRAPH_SPACING_MAX]). */
    val paragraphSpacing: Double = 0.0,
    /** em; null keeps the book's own ([LETTER_SPACING_MAX]). */
    val letterSpacing: Double? = null,
    /** em; null keeps the book's own ([WORD_SPACING_MAX]). */
    val wordSpacing: Double? = null,
    /** The first line of each paragraph, em; null keeps the book's own ([TEXT_INDENT_MAX]). */
    val textIndent: Double? = null,
    /** The text column's widest, CSS px, [MIN_INLINE_SIZE]..[MAX_INLINE_SIZE] (foliate's `max-inline-size`). */
    val maxInlineSize: Int = 720,
    /** [ReaderTheme.CUSTOM]'s page and text colours, `#rrggbb`. */
    val customBg: String = DEFAULT_CUSTOM_BG,
    val customFg: String = DEFAULT_CUSTOM_FG,
    /** Fixed-layout books: [SPREAD_AUTO] (the book's own spreads) or [SPREAD_NONE] (one page at a time). */
    val fixedLayoutSpread: String = SPREAD_AUTO,
    /** What the footer shows: a [FooterMode.id] (the web's 0 page, 1 time left in the book, 2 in the chapter). */
    val footerDisplayMode: Int = 0,
) {
    val scrolled: Boolean get() = flow == FLOW_SCROLLED

    /** The added settings inside the web's ranges and steps (the originals only ever come from this app). */
    fun normalized(): ReaderPrefs = copy(
        paragraphSpacing = step(paragraphSpacing, 0.0, PARAGRAPH_SPACING_MAX, PARAGRAPH_SPACING_STEP) ?: 0.0,
        letterSpacing = letterSpacing?.let { step(it, 0.0, LETTER_SPACING_MAX, LETTER_SPACING_STEP) },
        wordSpacing = wordSpacing?.let { step(it, 0.0, WORD_SPACING_MAX, WORD_SPACING_STEP) },
        textIndent = textIndent?.let { step(it, 0.0, TEXT_INDENT_MAX, TEXT_INDENT_STEP) },
        maxInlineSize = maxInlineSize.coerceIn(MIN_INLINE_SIZE, MAX_INLINE_SIZE),
        customBg = PageColor.normalize(customBg) ?: DEFAULT_CUSTOM_BG,
        customFg = PageColor.normalize(customFg) ?: DEFAULT_CUSTOM_FG,
        fixedLayoutSpread = if (fixedLayoutSpread == SPREAD_NONE) SPREAD_NONE else SPREAD_AUTO,
        footerDisplayMode = FooterMode.of(footerDisplayMode).id,
    )

    companion object {
        const val FLOW_PAGINATED = "paginated"
        const val FLOW_SCROLLED = "scrolled"
        const val FONT_PUBLISHER = "publisher"
        const val FONT_SERIF = "serif"
        const val FONT_SANS = "sans"
        val FONTS = listOf(FONT_PUBLISHER, FONT_SERIF, FONT_SANS)
        const val MIN_FONT_SIZE = 12
        const val MAX_FONT_SIZE = 32
        const val MIN_LINE_HEIGHT = 1.0
        const val MAX_LINE_HEIGHT = 2.2
        /** Narrow, normal, wide. */
        val MARGINS = listOf(0.03, 0.06, 0.10)

        // The web's ranges (EPUB_*_MIN/MAX) and its settings panel's steps.
        const val PARAGRAPH_SPACING_MAX = 2.0
        const val PARAGRAPH_SPACING_STEP = 0.1
        const val LETTER_SPACING_MAX = 0.2
        const val LETTER_SPACING_STEP = 0.01
        const val WORD_SPACING_MAX = 0.5
        const val WORD_SPACING_STEP = 0.05
        const val TEXT_INDENT_MAX = 4.0
        const val TEXT_INDENT_STEP = 0.25
        const val MIN_INLINE_SIZE = 400
        const val MAX_INLINE_SIZE = 1600

        /** Narrow, medium, wide, full: one value in each of the web's page-width bands, on its 40 px steps (720 is the default). */
        val PAGE_WIDTHS = listOf(560, 720, 1160, 1600)

        /** Which of [PAGE_WIDTHS] [width] reads as, by the web's bands (up to 640, 1000, 1320, beyond). */
        fun pageWidthIndex(width: Int): Int = when {
            width <= 640 -> 0
            width <= 1000 -> 1
            width <= 1320 -> 2
            else -> 3
        }

        const val SPREAD_AUTO = "auto"
        const val SPREAD_NONE = "none"
        const val DEFAULT_CUSTOM_BG = "#e8e1d0"
        const val DEFAULT_CUSTOM_FG = "#2f2a24"

        /** [value] clamped to [min]..[max] and rounded to [step] (as the web's setters do); null if not a number. */
        fun step(value: Double, min: Double, max: Double, step: Double): Double? {
            if (!value.isFinite()) return null
            val clamped = value.coerceIn(min, max)
            return (kotlin.math.round(clamped / step) * step).let { kotlin.math.round(it * 10_000) / 10_000 }
        }
    }
}

/**
 * What the footer (the bottom bar's last line) shows; tapping it moves to [next]. [id]s are the
 * web's `footerDisplayMode` values, so the choice means the same there.
 */
enum class FooterMode(val id: Int) {
    /** foliate's page (location) and the percentage. */
    PAGE(0),
    /** Reading time left in the book. */
    BOOK(1),
    /** Reading time left in the chapter. */
    CHAPTER(2);

    /** The page, then the chapter's time left, then the book's, then the page again. */
    fun next(): FooterMode = when (this) {
        PAGE -> CHAPTER
        CHAPTER -> BOOK
        BOOK -> PAGE
    }

    companion object {
        fun of(id: Int): FooterMode = entries.firstOrNull { it.id == id } ?: PAGE
    }
}

/**
 * The book page's colours. These are the page's own, not the app's UI colours: [APP] is the
 * exception and takes the app theme's page colours, so the book follows the app's light or dark
 * theme; [CUSTOM] takes the user's ([ReaderPrefs.customBg], [ReaderPrefs.customFg]); the others are fixed
 * paper colours. [ReaderStyles.pageColors] resolves them.
 */
enum class ReaderTheme(val id: String, val background: Color?, val foreground: Color?, val link: Color? = null) {
    APP("app", null, null),
    ORIGINAL("original", Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF1A4FD6)),
    SEPIA("sepia", Color(0xFFF1E8D0), Color(0xFF5B4636), Color(0xFF8A5A2B)),
    DARK("dark", Color(0xFF222222), Color(0xFFE0E0E0), Color(0xFF8AB4FF)),
    BLACK("black", Color(0xFF000000), Color(0xFFCFCFCF), Color(0xFF8AB4FF)),
    CUSTOM("custom", null, null);

    companion object {
        fun of(id: String): ReaderTheme = entries.firstOrNull { it.id == id } ?: APP
    }
}

/** The app theme's page colours, for [ReaderTheme.APP]. */
@Immutable
data class ReaderAppColors(val background: Color, val foreground: Color, val link: Color, val dark: Boolean)

/**
 * This device read on without a connection while something else moved the position on the server:
 * the user picks which to continue from.
 */
data class PositionChoice(
    val mine: Position,
    /** Device time [mine] was reached. */
    val mineAt: Long,
    val server: Position,
    /** When the server's position was last read there (ISO), if known. */
    val serverReadAt: String?,
)

/** Why the book isn't showing. */
sealed interface ReaderError {
    /** No connection, and this book isn't downloaded. */
    data object Offline : ReaderError

    /** The WebView's renderer died (reopening makes a new one). */
    data object Stopped : ReaderError

    /** What reader.js reported (English, from the page or the server). */
    data class Failed(val message: String) : ReaderError
}

/** One-off messages for a snackbar. */
sealed interface ReaderMessage {
    /** The book was marked Read/Unread by hand since it was last read: it starts from the beginning. */
    data class FreshStart(val statusLabel: String) : ReaderMessage

    /** The server refused a highlight, note or bookmark change ([message] is the server's). */
    data class NoteRejected(val message: String) : ReaderMessage
}

data class ReaderUiState(
    val title: String,
    /** Until the book is open on screen. */
    val loading: Boolean = true,
    val error: ReaderError? = null,
    /** The top and bottom bars (toggled by a tap in the middle of the page). */
    val chromeVisible: Boolean = false,
    val chapter: String = "",
    /** 0..1 through the book. */
    val fraction: Float = 0f,
    /** foliate's location in the book (0-based) and their count, when known. */
    val page: Int? = null,
    val pages: Int? = null,
    val toc: List<TocEntry> = emptyList(),
    /** The TOC entry the current page belongs to. */
    val tocHref: String? = null,
    val prefs: ReaderPrefs = ReaderPrefs(),
    val choice: PositionChoice? = null,
    /** Bumped to throw the WebView away and load the page again (retry after an error). */
    val pageGeneration: Int = 0,
    /** The open book is fixed-layout (its settings are the page colour and the spreads). */
    val fixedLayout: Boolean = false,
    /** Reading time left here, for the footer's time modes; null until known. */
    val timeLeft: TimeLeft? = null,
    /** Scrolled flow: at the end of a chapter, with another after it. */
    val chapterEnd: Boolean = false,
    /** At the end of the book, nothing left to turn or scroll to (the next book in the series shows). */
    val bookEnd: Boolean = false,
    /**
     * Counts the user's reading ([ReadingSigns]: a move, a jump, the book opening): the screen stays on for
     * a while after the last. Not [fraction], which foliate also changes by itself.
     */
    val activity: Int = 0,
)
