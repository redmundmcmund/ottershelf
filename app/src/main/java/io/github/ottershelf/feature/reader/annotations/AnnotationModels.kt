package io.github.ottershelf.feature.reader.annotations

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A highlight or note (`GET books/:bookId/annotations`, `annotation-response.dto.ts`). Only the
 * fields the reader uses; [cfi] is null for PDF highlights and positions KOReader/Kobo couldn't map.
 * Local ones not on the server yet carry a negative [id] (see [NoteOp.CreateAnnotation]).
 */
@Immutable
@Serializable
data class Annotation(
    val id: Long,
    val bookId: Long = 0,
    val cfi: String? = null,
    val text: String = "",
    val color: String = DEFAULT_COLOR,
    val style: String = STYLE_HIGHLIGHT,
    val note: String? = null,
    val chapterTitle: String? = null,
    val chapterIndex: Int? = null,
    val origin: String = "web",
    val positionStatus: String? = null,
    val highlightedAt: String? = null,
    val createdAt: String? = null,
) {
    val local: Boolean get() = id < 0
    val hasNote: Boolean get() = !note.isNullOrBlank()
}

/** `GET books/:bookId/bookmarks` (`bookmark-response.dto.ts`); negative [id] until it's on the server. */
@Immutable
@Serializable
data class Bookmark(
    val id: Long,
    val bookId: Long = 0,
    val cfi: String? = null,
    val title: String = "",
    val createdAt: String? = null,
) {
    val local: Boolean get() = id < 0
}

/**
 * What `POST books/:bookId/annotations` takes for an EPUB highlight (`create-annotation.dto.ts`):
 * exactly one of cfi/pdf, here always the cfi foliate made (the web reader's own format, so the web
 * and KOReader place it the same way). Sent as built by [ApiNotesRemote], nothing else.
 */
@Serializable
data class AnnotationDraft(
    val cfi: String,
    val text: String,
    val color: String,
    val style: String,
    val note: String? = null,
    val chapterTitle: String? = null,
    val bookFileId: Long? = null,
)

/**
 * A write waiting for the server, kept on the device (ReaderNotesStore) before it's sent. [opId]
 * names it, so a finished send removes exactly that one even if the queue changed meanwhile.
 */
@Serializable
sealed interface NoteOp {
    val opId: String

    /**
     * Not retry-safe on the server (a resend makes a second highlight). [attempted]: a send was
     * started, so before sending again the book's annotations are searched for one with the same
     * cfi and text that this device didn't know before.
     */
    @Serializable
    @SerialName("createAnnotation")
    data class CreateAnnotation(
        override val opId: String,
        val localId: Long,
        val draft: AnnotationDraft,
        val attempted: Boolean = false,
        val createdAt: String? = null,
    ) : NoteOp

    /** Only what changed (null: unchanged); an empty [note] clears it. */
    @Serializable
    @SerialName("updateAnnotation")
    data class UpdateAnnotation(
        override val opId: String,
        val id: Long,
        val note: String? = null,
        val color: String? = null,
        val style: String? = null,
    ) : NoteOp

    @Serializable
    @SerialName("deleteAnnotation")
    data class DeleteAnnotation(override val opId: String, val id: Long) : NoteOp

    /** Retry-safe: the server answers an existing bookmark at the same place instead of adding one. */
    @Serializable
    @SerialName("createBookmark")
    data class CreateBookmark(
        override val opId: String,
        val localId: Long,
        val cfi: String,
        val title: String,
        val attempted: Boolean = false,
        val createdAt: String? = null,
    ) : NoteOp

    @Serializable
    @SerialName("deleteBookmark")
    data class DeleteBookmark(override val opId: String, val id: Long) : NoteOp
}

/**
 * One book's notes on this device: the server's lists as last fetched (shown offline) and the
 * writes still waiting. `files/reader-notes/<account>/<bookId>.json`.
 */
@Serializable
data class NotesFile(
    val annotations: List<Annotation> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val queue: List<NoteOp> = emptyList(),
    /** Local id -> server id, for ops (and open popups) still naming the local one. */
    val ids: Map<Long, Long> = emptyMap(),
)

/** What the reader shows: the server's lists with the waiting writes applied. */
@Immutable
data class NotesSnapshot(
    val annotations: List<Annotation> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val pending: Int = 0,
    /** The server's lists have been fetched (or were cached on the device). */
    val loaded: Boolean = false,
)

/** [file]'s lists with its queue applied, in reading order. */
internal fun NotesFile.visible(): NotesSnapshot {
    val annotations = annotations.associateBy { it.id }.toMutableMap()
    val bookmarks = bookmarks.associateBy { it.id }.toMutableMap()
    fun resolve(id: Long) = ids[id] ?: id
    for (op in queue) when (op) {
        is NoteOp.CreateAnnotation -> {
            val d = op.draft
            // Already matched on the server (ids has it): the server's copy is in the list.
            if (op.localId !in ids) annotations[op.localId] = Annotation(
                id = op.localId, cfi = d.cfi, text = d.text, color = d.color, style = d.style,
                note = d.note, chapterTitle = d.chapterTitle, createdAt = op.createdAt,
            )
        }
        is NoteOp.UpdateAnnotation -> annotations[resolve(op.id)]?.let { a ->
            annotations[a.id] = a.copy(
                note = if (op.note != null) op.note.ifBlank { null } else a.note,
                color = op.color ?: a.color,
                style = op.style ?: a.style,
            )
        }
        is NoteOp.DeleteAnnotation -> annotations.remove(resolve(op.id))
        is NoteOp.CreateBookmark ->
            if (op.localId !in ids) bookmarks[op.localId] = Bookmark(op.localId, cfi = op.cfi, title = op.title, createdAt = op.createdAt)
        is NoteOp.DeleteBookmark -> bookmarks.remove(resolve(op.id))
    }
    return NotesSnapshot(
        annotations = annotations.values.filter { it.cfi != null }.sortedWith(compareBy(Cfi.order) { it.cfi }),
        bookmarks = bookmarks.values.filter { it.cfi != null }.sortedWith(compareBy(Cfi.order) { it.cfi }),
        pending = queue.size,
        loaded = true,
    )
}

// --- colours and styles --------------------------------------------------------------------

const val STYLE_HIGHLIGHT = "highlight"
const val STYLE_UNDERLINE = "underline"
const val STYLE_STRIKETHROUGH = "strikethrough"
const val STYLE_SQUIGGLY = "squiggly"

/** The four the web's popup offers (the server also knows `invert`, which is drawn but not offered). */
val HIGHLIGHT_STYLES = listOf(STYLE_HIGHLIGHT, STYLE_UNDERLINE, STYLE_STRIKETHROUGH, STYLE_SQUIGGLY)

const val DEFAULT_COLOR = "#FACC15"

/** The web's ten highlight colours (`ANNOTATION_HIGHLIGHT_COLORS`), stored as these hex strings. */
enum class HighlightColor(val key: String, val hex: String) {
    YELLOW("yellow", "#FACC15"),
    GREEN("green", "#4ADE80"),
    BLUE("blue", "#38BDF8"),
    PINK("pink", "#F472B6"),
    ORANGE("orange", "#FB923C"),
    RED("red", "#F87171"),
    OLIVE("olive", "#84CC16"),
    CYAN("cyan", "#22D3EE"),
    PURPLE("purple", "#C084FC"),
    GRAY("gray", "#9CA3AF");

    val color: Color get() = parseHex(hex) ?: Color.Yellow

    companion object {
        /** The palette entry [stored] names or is (case-insensitive hex), if any. */
        fun of(stored: String?): HighlightColor? {
            val s = stored?.trim() ?: return null
            return entries.firstOrNull { it.hex.equals(s, ignoreCase = true) || it.key.equals(s, ignoreCase = true) }
        }
    }
}

/**
 * The CSS colour to draw [stored] with: the server's default `yellow` and KOReader's colour names
 * become the app's hex (`KOREADER_HIGHLIGHT_COLORS.appHex`); a hex is used as it is.
 */
fun displayHex(stored: String?): String {
    val s = stored?.trim().orEmpty()
    if (s.isEmpty()) return DEFAULT_COLOR
    HighlightColor.of(s)?.let { return it.hex }
    return if (HEX.matches(s)) s else DEFAULT_COLOR
}

fun displayColor(stored: String?): Color = parseHex(displayHex(stored)) ?: HighlightColor.YELLOW.color

private val HEX = Regex("#[0-9a-fA-F]{6}")

internal fun parseHex(hex: String): Color? {
    if (!HEX.matches(hex)) return null
    val v = hex.substring(1).toLong(16)
    return Color(0xFF000000 or v)
}

// --- CFIs ----------------------------------------------------------------------------------

/**
 * Just enough of EPUB CFIs to order and compare foliate's: the steps as numbers, assertions
 * (`[...]`) dropped, the `!` indirection and `:` offsets kept as further numbers, and a range
 * (`parent,start,end`) as its two ends. Positions in the same chapter compare correctly; this
 * doesn't resolve anything against a document.
 */
internal object Cfi {
    class Parsed(val spine: List<Int>, val start: List<Int>, val end: List<Int>)

    private val assertion = Regex("\\[[^\\]]*]")
    private val number = Regex("\\d+")

    fun parse(cfi: String?): Parsed? {
        if (cfi.isNullOrBlank()) return null
        val body = cfi.trim().removePrefix("epubcfi(").removeSuffix(")").replace(assertion, "")
        if (body.isEmpty()) return null
        val parts = body.split(',')
        val parent = parts[0]
        val spine = numbers(parent.substringBefore('!'))
        if (spine.isEmpty()) return null
        val start = numbers(parent + parts.getOrElse(1) { "" })
        val end = if (parts.size >= 3) numbers(parent + parts[2]) else start
        return Parsed(spine, start, end)
    }

    private fun numbers(path: String): List<Int> =
        number.findAll(path.substringBefore('~').substringBefore('@')).mapNotNull { it.value.toIntOrNull() }.toList()

    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }

    /** Reading order; unparseable ones last. */
    val order: Comparator<String?> = Comparator { x, y ->
        val a = parse(x)
        val b = parse(y)
        when {
            a == null && b == null -> 0
            a == null -> 1
            b == null -> -1
            else -> compare(a.start, b.start).takeIf { it != 0 } ?: compare(a.end, b.end)
        }
    }

    /** The same span (the web's `cfiRangesMatch`): selecting an existing highlight edits it. */
    fun sameRange(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        if (a == b) return true
        val x = parse(a) ?: return false
        val y = parse(b) ?: return false
        return x.spine == y.spine && x.start == y.start && x.end == y.end
    }

    /** Whether [point]'s start lies within the range [location] (the page on screen). */
    fun contains(location: String?, point: String?): Boolean {
        if (location == null || point == null) return false
        if (location == point) return true
        val loc = parse(location) ?: return false
        val p = parse(point) ?: return false
        return loc.spine == p.spine && compare(loc.start, p.start) <= 0 && compare(p.start, loc.end) <= 0
    }
}
