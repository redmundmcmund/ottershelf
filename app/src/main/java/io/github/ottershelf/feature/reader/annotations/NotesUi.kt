package io.github.ottershelf.feature.reader.annotations

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** A place on the page, in CSS px of the reader page (= dp), relative to the WebView. */
@Immutable
data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

@Serializable
internal data class RectPayload(val left: Double = 0.0, val top: Double = 0.0, val right: Double = 0.0, val bottom: Double = 0.0) {
    fun toRect() = PageRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}

/** reader.js `onSelection`: a settled text selection. */
@Serializable
internal data class SelectionPayload(
    val text: String = "",
    val cfi: String? = null,
    val chapter: String? = null,
    val rect: RectPayload? = null,
)

/** reader.js `onAnnotationTap`: a tap on a drawn highlight. */
@Serializable
internal data class AnnotationTapPayload(val cfi: String? = null, val rect: RectPayload? = null)

/** One search match with its context, as foliate's search.js gives it. */
@Immutable
@Serializable
data class SearchHit(
    val cfi: String = "",
    val pre: String = "",
    val match: String = "",
    val post: String = "",
    val section: String = "",
)

/** reader.js `onSearchResults`: a batch of the search [id]. */
@Serializable
internal data class SearchPayload(
    val id: Int = 0,
    val items: List<SearchHit> = emptyList(),
    val progress: Double = 0.0,
    val done: Boolean = false,
    val error: String? = null,
)

/**
 * The selection popup: over a new selection ([annotationId] null) or an existing highlight (a tap
 * on it, or a selection of exactly its text). [color]/[style] are what a colour tap applies.
 */
@Immutable
data class SelectionPopupState(
    val text: String,
    val cfi: String?,
    val chapter: String?,
    val rect: PageRect?,
    val annotationId: Long? = null,
    val color: String = DEFAULT_COLOR,
    val style: String = STYLE_HIGHLIGHT,
    val note: String? = null,
    /** Opened by a tap on a highlight (no text selection behind it). */
    val fromTap: Boolean = false,
) {
    /**
     * A highlight or note can be made here: an existing highlight, or a selection foliate gave a CFI
     * (it does in every format it reflows: EPUB, KEPUB, MOBI, AZW3, AZW, FB2). Without one only Copy
     * and Share are offered.
     */
    val canHighlight: Boolean get() = annotationId != null || !cfi.isNullOrBlank()
}

@Immutable
data class NoteDialogState(
    val quote: String,
    val initial: String,
    val annotationId: Long? = null,
    val cfi: String? = null,
    val chapter: String? = null,
)

@Immutable
data class SearchState(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    val progress: Float = 0f,
    val done: Boolean = false,
    val id: Int = 0,
    val error: String? = null,
)

@Immutable
data class ReaderNotesUiState(
    val annotations: List<Annotation> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val loaded: Boolean = false,
    /** The page on screen holds a bookmark. */
    val bookmarked: Boolean = false,
    val popup: SelectionPopupState? = null,
    val noteDialog: NoteDialogState? = null,
    /** A highlight waiting for the delete to be confirmed. */
    val confirmDelete: Annotation? = null,
    val search: SearchState = SearchState(),
    val lastColor: String = DEFAULT_COLOR,
    val lastStyle: String = STYLE_HIGHLIGHT,
)

/** Consecutive highlights of one chapter, in reading order. */
@Immutable
data class HighlightGroup(val chapter: String?, val items: List<Annotation>)

/** [annotations] (already in reading order) filtered to [color] and grouped by chapter. */
fun highlightGroups(annotations: List<Annotation>, color: String?): List<HighlightGroup> {
    val groups = mutableListOf<HighlightGroup>()
    for (a in annotations) {
        if (color != null && displayHex(a.color) != color) continue
        val chapter = a.chapterTitle?.trim()?.ifEmpty { null }
        val last = groups.lastOrNull()
        if (last != null && last.chapter == chapter) groups[groups.lastIndex] = last.copy(items = last.items + a)
        else groups += HighlightGroup(chapter, listOf(a))
    }
    return groups
}

/** The colours [annotations] use (as drawn), palette order first, with their counts. */
fun highlightColorCounts(annotations: List<Annotation>): List<Pair<String, Int>> {
    val counts = annotations.groupingBy { displayHex(it.color) }.eachCount()
    val palette = HighlightColor.entries.map { it.hex }
    return counts.entries.sortedWith(compareBy({ palette.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.key }))
        .map { it.key to it.value }
}

/** Search hits grouped by their chapter label, in the order found. */
fun searchGroups(hits: List<SearchHit>): List<Pair<String, List<SearchHit>>> {
    val groups = mutableListOf<Pair<String, MutableList<SearchHit>>>()
    for (h in hits) {
        val last = groups.lastOrNull()
        if (last != null && last.first == h.section) last.second += h else groups += h.section to mutableListOf(h)
    }
    return groups
}

/** The text shared for a quote: the quote, then the book and its authors. */
fun shareText(quote: String, title: String, authors: List<String>): String {
    val by = authors.filter { it.isNotBlank() }.joinToString(", ")
    val source = listOf(title.trim(), by).filter { it.isNotEmpty() }.joinToString(", ")
    return if (source.isEmpty()) "“${quote.trim()}”" else "“${quote.trim()}”\n\n— $source"
}
