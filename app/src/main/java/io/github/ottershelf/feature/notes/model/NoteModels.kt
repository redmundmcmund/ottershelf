package io.github.ottershelf.feature.notes.model

import kotlinx.serialization.Serializable

/**
 * One highlight (with or without a note): the server's `AnnotationItem`
 * (`annotation-response.dto.ts`), plus the hub's `bookTitle`, `author` and `jumpFileFormat` when it
 * comes from `GET annotations`. Text can't be changed after creation; note, colour and style can
 * (`PATCH books/:bookId/annotations/:id`).
 */
@Serializable
data class Annotation(
    val id: Long,
    val bookId: Long,
    val cfi: String? = null,
    /** The file a jump opens (EPUB for a CFI). */
    val jumpFileId: Long? = null,
    val pageno: Int? = null,
    val text: String = "",
    /** A hex colour (`#FACC15`); KOReader and Kobo highlights may carry their own. */
    val color: String = HighlightColors.DEFAULT,
    /** highlight, underline, strikethrough, squiggly or invert. */
    val style: String = "highlight",
    val note: String? = null,
    val chapterTitle: String? = null,
    /** web, koreader or kobo. */
    val origin: String = "web",
    val positionStatus: String? = null,
    val chapterIndex: Int? = null,
    val highlightedAt: String? = null,
    val createdAt: String? = null,
    // The hub's (GET annotations) extra fields.
    val bookTitle: String? = null,
    val author: String? = null,
    val jumpFileFormat: String? = null,
) {
    val hasNote: Boolean get() = !note.isNullOrBlank()
}

@Serializable
data class ColorCount(val color: String, val count: Int = 0)

/** One chapter's highlights across the whole book (the server's `AnnotationChapterStat`). */
@Serializable
data class ChapterStat(
    val title: String? = null,
    val count: Int = 0,
    val colors: List<ColorCount> = emptyList(),
    val chapterIndex: Int? = null,
    val order: Double? = null,
)

/** A book's totals (`AnnotationStats`, the fields the app shows). */
@Serializable
data class BookAnnotationStats(
    val totalHighlights: Int = 0,
    val colorBreakdown: List<ColorCount> = emptyList(),
    val chaptersWithHighlights: Int = 0,
    val highlightsWithNotes: Int = 0,
    val chapterBreakdown: List<ChapterStat> = emptyList(),
)

/** `GET books/:bookId/annotations?page=...` (`AnnotationListResponse`). */
@Serializable
data class BookAnnotationsPage(
    val items: List<Annotation> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 0,
    val stats: BookAnnotationStats = BookAnnotationStats(),
)

@Serializable
data class HubStats(val books: Int = 0, val withNotes: Int = 0)

/** `GET annotations` (`AnnotationHubResponse`): every book's highlights, filtered and paged. */
@Serializable
data class HubPage(
    val items: List<Annotation> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 0,
    val stats: HubStats = HubStats(),
)

/** `GET annotations/overview` (the fields the app shows). */
@Serializable
data class HubOverview(
    val total: Int = 0,
    val books: Int = 0,
    val withNotes: Int = 0,
    val colorBreakdown: List<ColorCount> = emptyList(),
)

/** `GET annotations/books`: a book with highlights, for the book filter. */
@Serializable
data class BookFacet(val bookId: Long, val bookTitle: String? = null, val author: String? = null, val count: Int = 0)

/**
 * The Notes feed's filters. [query] is the shell's search; [colors] are hex colours; [liked] is the
 * app's own (the heart, kept in the app settings key), so it is filtered on the phone.
 */
data class NoteFilter(
    val query: String? = null,
    val bookId: Long? = null,
    val bookTitle: String? = null,
    val color: String? = null,
    val hasNote: Boolean = false,
    val liked: Boolean = false,
    /** The web's trash instead (`status=trashed`): only to check likes, never shown. */
    val trashed: Boolean = false,
) {
    val narrowed: Boolean get() = bookId != null || color != null || hasNote || liked || !query.isNullOrBlank()
}

/** The web's ten highlight colours (`ANNOTATION_HIGHLIGHT_COLORS`), in its order. */
object HighlightColors {
    const val DEFAULT = "#FACC15"

    val all: List<Pair<String, String>> = listOf(
        "yellow" to "#FACC15",
        "green" to "#4ADE80",
        "blue" to "#38BDF8",
        "pink" to "#F472B6",
        "orange" to "#FB923C",
        "red" to "#F87171",
        "olive" to "#84CC16",
        "cyan" to "#22D3EE",
        "purple" to "#C084FC",
        "gray" to "#9CA3AF",
    )

    /** The colour's name (yellow...) for a known hex, else null. */
    fun nameOf(hex: String): String? = all.firstOrNull { it.second.equals(hex, ignoreCase = true) }?.first

    /** `#RRGGBB` (or `RRGGBB`, `#RGB`) as an opaque ARGB int; [DEFAULT] when it can't be read. */
    fun argb(hex: String?): Int {
        val digits = hex?.trim()?.removePrefix("#").orEmpty()
        val full = when (digits.length) {
            3 -> digits.map { "$it$it" }.joinToString("")
            6 -> digits
            8 -> digits.substring(2)
            else -> null
        }
        val value = full?.toLongOrNull(16) ?: return argb(DEFAULT)
        return (0xFF000000 or value).toInt()
    }
}

/** The Lucide icon for a highlight style (the web's STYLES). */
fun styleIcon(style: String): String = when (style) {
    "underline" -> "Underline"
    "strikethrough" -> "Strikethrough"
    "squiggly" -> "WavesHorizontal"
    "invert" -> "Contrast"
    else -> "Highlighter"
}

/** The styles the server takes, in the web's order. */
val HIGHLIGHT_STYLES = listOf("highlight", "underline", "strikethrough", "squiggly", "invert")
