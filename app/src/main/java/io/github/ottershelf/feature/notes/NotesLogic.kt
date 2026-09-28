package io.github.ottershelf.feature.notes

import io.github.ottershelf.core.settings.NotesPrefs
import io.github.ottershelf.core.util.IsoTime
import io.github.ottershelf.feature.notes.model.Annotation
import io.github.ottershelf.feature.notes.model.BookAnnotationStats
import io.github.ottershelf.feature.notes.model.ChapterStat
import io.github.ottershelf.feature.notes.model.ColorCount
import io.github.ottershelf.feature.notes.model.NoteFilter
import java.net.URLEncoder
import kotlin.random.Random

/** One chapter of a book's highlights, in reading order. [count] is the whole book's, not just what's loaded. */
data class ChapterGroup(val title: String?, val count: Int, val items: List<Annotation>)

/** The pure part of highlights and notes: queries, grouping, likes and reviews, random picks. */
object NotesLogic {

    /** `GET annotations` for [filter] (newest first). Liked is the phone's own filter, so it isn't sent. */
    fun hubQuery(filter: NoteFilter, page: Int, pageSize: Int): String = buildString {
        append("annotations?page=").append(page).append("&pageSize=").append(pageSize)
        append("&sortBy=createdAt&sortDir=desc")
        filter.bookId?.let { append("&bookId=").append(it) }
        filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let { append("&search=").append(encode(it.take(200))) }
        filter.color?.let { append("&colors=").append(encode(it)) }
        if (filter.hasNote) append("&hasNote=true")
        if (filter.trashed) append("&status=trashed")
    }

    /** One book's highlights, by position through the book or newest first. */
    fun bookQuery(bookId: Long, page: Int, pageSize: Int, newestFirst: Boolean): String =
        "books/$bookId/annotations?page=$page&pageSize=$pageSize&" +
            if (newestFirst) "sortBy=createdAt&sortDir=desc" else "sortBy=position&sortDir=asc"

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    /**
     * [items] (in reading order) by chapter, in the order each chapter first appears. Counts come
     * from the server's [chapters] (the whole book) when it has the chapter, else what's loaded.
     */
    fun chapterGroups(items: List<Annotation>, chapters: List<ChapterStat>): List<ChapterGroup> {
        val byTitle = LinkedHashMap<String?, MutableList<Annotation>>()
        items.forEach { byTitle.getOrPut(it.chapterTitle?.takeIf { t -> t.isNotBlank() }) { mutableListOf() } += it }
        return byTitle.map { (title, list) ->
            val count = chapters.firstOrNull { (it.title?.takeIf { t -> t.isNotBlank() }) == title }?.count ?: list.size
            ChapterGroup(title, maxOf(count, list.size), list)
        }
    }

    /** Each colour's share of [breakdown] (0..1), biggest first; empty when there's nothing. */
    fun shares(breakdown: List<ColorCount>): List<Pair<String, Float>> {
        val total = breakdown.sumOf { it.count }.takeIf { it > 0 } ?: return emptyList()
        return breakdown.filter { it.count > 0 }.sortedByDescending { it.count }.map { it.color to it.count.toFloat() / total }
    }

    /**
     * [note] liked, or no longer liked. Null when it would be one like more than
     * [NotesPrefs.MAX_ENTRIES] (the key has to fit users.settings): the user unlikes some first, rather
     * than an older like being dropped without a word.
     */
    fun toggleLiked(prefs: NotesPrefs, note: Annotation): NotesPrefs? {
        if (note.id in prefs.liked) return prefs.copy(liked = prefs.liked - note.id)
        if (prefs.liked.size >= NotesPrefs.MAX_ENTRIES) return null
        return prefs.copy(liked = prefs.liked + (note.id to note.bookId))
    }

    /** One more Memorize review of [id]. */
    fun addReview(prefs: NotesPrefs, id: Long): NotesPrefs = addReviews(prefs, mapOf(id to 1))

    /** [counts] more Memorize reviews (id -> how many), in one change. */
    fun addReviews(prefs: NotesPrefs, counts: Map<Long, Int>): NotesPrefs {
        if (counts.isEmpty()) return prefs
        val merged = prefs.reviews.toMutableMap()
        counts.forEach { (id, n) -> merged[id] = (merged[id] ?: 0) + n }
        return prefs.copy(reviews = cappedReviews(merged, keep = counts.keys))
    }

    /** Likes of highlights that no longer exist dropped ([gone]: ids a complete fetch didn't return). */
    fun withoutLikes(prefs: NotesPrefs, gone: Set<Long>): NotesPrefs =
        if (gone.none { it in prefs.liked }) prefs else prefs.copy(liked = prefs.liked - gone, reviews = prefs.reviews - gone)

    /**
     * At most [NotesPrefs.MAX_ENTRIES] review counts: the least reviewed go first (the oldest id on a
     * tie), never one in [keep] (just reviewed), so a review always counts.
     */
    internal fun cappedReviews(reviews: Map<Long, Int>, keep: Set<Long>): Map<Long, Int> {
        val over = reviews.size - NotesPrefs.MAX_ENTRIES
        if (over <= 0) return reviews
        val evicted = reviews.entries.filter { it.key !in keep }
            .sortedWith(compareBy<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
            .take(over)
            .mapTo(HashSet()) { it.key }
        return reviews - evicted
    }

    /** [target] with the fields named in [fields] (`note`, `color`, `style`) taken from [from]. */
    fun withFields(target: Annotation, from: Annotation, fields: Set<String>): Annotation = target.copy(
        note = if ("note" in fields) from.note else target.note,
        color = if ("color" in fields) from.color else target.color,
        style = if ("style" in fields) from.style else target.style,
    )

    /**
     * [items] with [note] back where it was: after the one that preceded it ([afterId], null when it
     * was first), else at [index].
     */
    fun reinsert(items: List<Annotation>, note: Annotation, afterId: Long?, index: Int): List<Annotation> {
        if (items.any { it.id == note.id }) return items
        val at = when (afterId) {
            null -> 0
            else -> items.indexOfFirst { it.id == afterId }.let { if (it >= 0) it + 1 else index.coerceIn(0, items.size) }
        }
        return items.toMutableList().apply { add(at, note) }
    }

    /** Whether [note] passes the parts of [filter] the phone applies to liked notes (the server does the rest otherwise). */
    fun matches(note: Annotation, filter: NoteFilter): Boolean {
        if (filter.bookId != null && note.bookId != filter.bookId) return false
        if (filter.color != null && !note.color.equals(filter.color, ignoreCase = true)) return false
        if (filter.hasNote && !note.hasNote) return false
        val q = filter.query?.trim().orEmpty()
        if (q.isNotEmpty()) {
            val hay = listOfNotNull(note.text, note.note, note.chapterTitle, note.bookTitle, note.author)
            if (hay.none { it.contains(q, ignoreCase = true) }) return false
        }
        return true
    }

    /** Newest first, as the hub sorts. */
    fun newestFirst(notes: List<Annotation>): List<Annotation> =
        notes.sortedWith(compareByDescending<Annotation> { IsoTime.parse(it.createdAt) ?: 0L }.thenByDescending { it.id })

    /**
     * A random index below [total] not in [seen] (a shuffle without repeats); once every index was
     * seen, any but [last]. Null when there is nothing.
     */
    fun randomIndex(total: Int, seen: Set<Int>, last: Int? = null, random: Random = Random.Default): Int? {
        if (total <= 0) return null
        if (total == 1) return 0
        val unseen = total - seen.count { it in 0 until total }
        if (unseen > 0) {
            // Pick the n-th unseen index without building the whole list (totals can be large).
            var n = random.nextInt(unseen)
            if (seen.isEmpty()) return n
            for (i in 0 until total) {
                if (i in seen) continue
                if (n == 0) return i
                n--
            }
        }
        var pick = random.nextInt(total)
        if (pick == last) pick = (pick + 1) % total
        return pick
    }

    /** [stats] after [before] became [after] (colour or note changed), or was deleted (after null). */
    fun statsAfter(stats: BookAnnotationStats, before: Annotation, after: Annotation?): BookAnnotationStats {
        fun List<ColorCount>.add(hex: String, delta: Int): List<ColorCount> {
            val i = indexOfFirst { it.color.equals(hex, ignoreCase = true) }
            val next = if (i >= 0) mapIndexed { j, c -> if (j == i) c.copy(count = c.count + delta) else c } else this + ColorCount(hex, delta)
            return next.filter { it.count > 0 }
        }
        var colors = stats.colorBreakdown
        if (after == null || !after.color.equals(before.color, ignoreCase = true)) {
            colors = colors.add(before.color, -1)
            if (after != null) colors = colors.add(after.color, 1)
        }
        val notes = stats.highlightsWithNotes - (if (before.hasNote) 1 else 0) + (if (after?.hasNote == true) 1 else 0)
        if (after != null) return stats.copy(colorBreakdown = colors, highlightsWithNotes = notes.coerceAtLeast(0))
        val chapters = stats.chapterBreakdown.map { if (it.title == before.chapterTitle) it.copy(count = it.count - 1) else it }.filter { it.count > 0 }
        return stats.copy(
            totalHighlights = (stats.totalHighlights - 1).coerceAtLeast(0),
            colorBreakdown = colors,
            highlightsWithNotes = notes.coerceAtLeast(0),
            chapterBreakdown = chapters,
            chaptersWithHighlights = if (chapters.size < stats.chapterBreakdown.size) (stats.chaptersWithHighlights - 1).coerceAtLeast(0) else stats.chaptersWithHighlights,
        )
    }

    /** [stats] with [note] counted again (its delete failed): the reverse of [statsAfter] with no after. */
    fun statsRestored(stats: BookAnnotationStats, note: Annotation): BookAnnotationStats {
        val c = stats.colorBreakdown.indexOfFirst { it.color.equals(note.color, ignoreCase = true) }
        val colors = if (c >= 0) stats.colorBreakdown.mapIndexed { j, it -> if (j == c) it.copy(count = it.count + 1) else it } else stats.colorBreakdown + ColorCount(note.color, 1)
        val ch = stats.chapterBreakdown.indexOfFirst { it.title == note.chapterTitle }
        val chapters = if (ch >= 0) stats.chapterBreakdown.mapIndexed { j, it -> if (j == ch) it.copy(count = it.count + 1) else it } else stats.chapterBreakdown + ChapterStat(note.chapterTitle, 1)
        return stats.copy(
            totalHighlights = stats.totalHighlights + 1,
            colorBreakdown = colors,
            highlightsWithNotes = stats.highlightsWithNotes + if (note.hasNote) 1 else 0,
            chapterBreakdown = chapters,
            chaptersWithHighlights = stats.chaptersWithHighlights + if (ch >= 0) 0 else 1,
        )
    }

    /** A file name for a book's exported highlights. */
    fun exportFileName(title: String?): String {
        val base = title?.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.take(80)
        return "${base?.takeIf { it.isNotEmpty() } ?: "Book"} highlights.md"
    }
}
