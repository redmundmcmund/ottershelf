package io.github.ottershelf.feature.notes

import io.github.ottershelf.core.settings.NotesPrefs
import io.github.ottershelf.feature.notes.model.Annotation
import io.github.ottershelf.feature.notes.model.BookAnnotationStats
import io.github.ottershelf.feature.notes.model.ChapterStat
import io.github.ottershelf.feature.notes.model.ColorCount
import io.github.ottershelf.feature.notes.model.HighlightColors
import io.github.ottershelf.feature.notes.model.NoteFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NotesLogicTest {

    private fun note(id: Long, chapter: String? = null, color: String = "#FACC15", note: String? = null, book: Long = 1) =
        Annotation(id = id, bookId = book, text = "text $id", chapterTitle = chapter, color = color, note = note, createdAt = "2026-01-0${id % 9 + 1}T10:00:00Z")

    @Test
    fun hubQueryCarriesTheServerFiltersOnly() {
        val q = NotesLogic.hubQuery(NoteFilter(query = "the sea & sky", bookId = 7, color = "#4ADE80", hasNote = true, liked = true), 2, 30)
        assertEquals("annotations?page=2&pageSize=30&sortBy=createdAt&sortDir=desc&bookId=7&search=the%20sea%20%26%20sky&colors=%234ADE80&hasNote=true", q)
        assertEquals("annotations?page=1&pageSize=1&sortBy=createdAt&sortDir=desc", NotesLogic.hubQuery(NoteFilter(query = "  "), 1, 1))
    }

    @Test
    fun bookQuerySortsByPositionOrNewest() {
        assertEquals("books/3/annotations?page=1&pageSize=100&sortBy=position&sortDir=asc", NotesLogic.bookQuery(3, 1, 100, newestFirst = false))
        assertEquals("books/3/annotations?page=1&pageSize=3&sortBy=createdAt&sortDir=desc", NotesLogic.bookQuery(3, 1, 3, newestFirst = true))
    }

    @Test
    fun chaptersKeepReadingOrderAndTheBooksCounts() {
        val items = listOf(note(1, "One"), note(2, "One"), note(3, null), note(4, "Two"), note(5, "One"))
        val groups = NotesLogic.chapterGroups(items, listOf(ChapterStat("One", 9), ChapterStat("Two", 1)))
        assertEquals(listOf("One", null, "Two"), groups.map { it.title })
        assertEquals(listOf(9, 1, 1), groups.map { it.count })
        assertEquals(listOf(1L, 2L, 5L), groups[0].items.map { it.id })
    }

    @Test
    fun likesToggleAndAFullSetRefusesRatherThanDropsOne() {
        val liked = NotesLogic.toggleLiked(NotesPrefs(), note(5, book = 9))!!
        assertEquals(mapOf(5L to 9L), liked.liked)
        assertTrue(NotesLogic.toggleLiked(liked, note(5, book = 9))!!.liked.isEmpty())

        val many = NotesPrefs(liked = (1L..NotesPrefs.MAX_ENTRIES.toLong()).associateWith { 1L })
        // One more is refused: no older like disappears for it.
        assertNull(NotesLogic.toggleLiked(many, note(10_000)))
        assertNull(NotesLogic.toggleLiked(many, note(0)))
        // Unliking still works.
        assertEquals(NotesPrefs.MAX_ENTRIES - 1, NotesLogic.toggleLiked(many, note(1))!!.liked.size)
    }

    @Test
    fun reviewsCountUpAndGoneLikesAreDropped() {
        val once = NotesLogic.addReview(NotesPrefs(), 4)
        assertEquals(2, NotesLogic.addReview(once, 4).reviews[4])
        assertEquals(mapOf(4L to 5, 6L to 1), NotesLogic.addReviews(once, mapOf(4L to 4, 6L to 1)).reviews)
        val prefs = NotesPrefs(liked = mapOf(1L to 1L, 2L to 1L), reviews = mapOf(2L to 3))
        val after = NotesLogic.withoutLikes(prefs, setOf(2L))
        assertEquals(mapOf(1L to 1L), after.liked)
        assertTrue(after.reviews.isEmpty())
    }

    @Test
    fun aFullReviewSetDropsTheLeastReviewedNeverTheOneJustReviewed() {
        // Ids 1..400 reviewed twice, except 300, reviewed once.
        val full = NotesPrefs(reviews = (1L..NotesPrefs.MAX_ENTRIES.toLong()).associateWith { if (it == 300L) 1 else 2 })
        // An old id (below all kept ones) counts, and the least reviewed goes.
        val old = NotesLogic.addReview(full, 0)
        assertEquals(NotesPrefs.MAX_ENTRIES, old.reviews.size)
        assertEquals(1, old.reviews[0])
        assertFalse(300L in old.reviews)
        // Next the least reviewed are the new one and the rest at 2: the oldest other id at 1 goes first.
        val next = NotesLogic.addReview(old, 1_000)
        assertEquals(1, next.reviews[1_000])
        assertFalse(0L in next.reviews)
        assertTrue(1L in next.reviews)
    }

    @Test
    fun anEditAnswerSetsOnlyItsOwnFields() {
        val shown = note(1, color = "#4ADE80", note = "newer note")
        val answer = note(1, color = "#F472B6", note = "old note")
        val merged = NotesLogic.withFields(shown, answer, setOf("color"))
        assertEquals("#F472B6", merged.color)
        assertEquals("newer note", merged.note)
    }

    @Test
    fun aFailedDeletePutsBackOnlyThatHighlight() {
        val a = note(1, "One")
        val b = note(2, "One")
        val c = note(3, "Two")
        // b's delete failed; c was deleted meanwhile and a page was added (4).
        val now = listOf(a, note(4, "Two"))
        assertEquals(listOf(1L, 2L, 4L), NotesLogic.reinsert(now, b, afterId = 1, index = 1).map { it.id })
        assertEquals(listOf(2L, 1L, 4L), NotesLogic.reinsert(now, b, afterId = null, index = 0).map { it.id })
        // The one before it went too: its old index.
        assertEquals(listOf(4L, 3L), NotesLogic.reinsert(listOf(note(4)), c, afterId = 2, index = 2).map { it.id })

        val stats = BookAnnotationStats(
            totalHighlights = 3,
            colorBreakdown = listOf(ColorCount("#FACC15", 3)),
            chaptersWithHighlights = 2,
            highlightsWithNotes = 0,
            chapterBreakdown = listOf(ChapterStat("One", 2), ChapterStat("Two", 1)),
        )
        val deleted = NotesLogic.statsAfter(stats, c, null)
        assertEquals(stats, NotesLogic.statsRestored(deleted, c))
    }

    @Test
    fun theTrashIsAskedForOnlyWhenChecking() {
        assertEquals(
            "annotations?page=1&pageSize=100&sortBy=createdAt&sortDir=desc&bookId=7&status=trashed",
            NotesLogic.hubQuery(NoteFilter(bookId = 7, trashed = true), 1, 100),
        )
    }

    @Test
    fun randomIndexVisitsEveryOneBeforeRepeating() {
        val random = Random(42)
        val seen = mutableSetOf<Int>()
        repeat(7) { seen += NotesLogic.randomIndex(7, seen, random = random)!! }
        assertEquals((0 until 7).toSet(), seen)
        // All seen: anything but the last one.
        repeat(20) { assertTrue(NotesLogic.randomIndex(7, seen, last = 3, random = random) != 3) }
        assertEquals(null, NotesLogic.randomIndex(0, emptySet()))
        assertEquals(0, NotesLogic.randomIndex(1, setOf(0), last = 0))
    }

    @Test
    fun likedNotesAreFilteredOnThePhone() {
        val n = note(1, chapter = "Storm", color = "#38BDF8", note = "remember").copy(bookTitle = "Moby-Dick")
        assertTrue(NotesLogic.matches(n, NoteFilter(query = "moby", color = "#38bdf8", hasNote = true, bookId = 1)))
        assertFalse(NotesLogic.matches(n, NoteFilter(color = "#FACC15")))
        assertFalse(NotesLogic.matches(n.copy(note = " "), NoteFilter(hasNote = true)))
        assertFalse(NotesLogic.matches(n, NoteFilter(query = "whale")))
    }

    @Test
    fun statsFollowEditsAndDeletes() {
        val stats = BookAnnotationStats(
            totalHighlights = 3,
            colorBreakdown = listOf(ColorCount("#FACC15", 2), ColorCount("#4ADE80", 1)),
            chaptersWithHighlights = 2,
            highlightsWithNotes = 1,
            chapterBreakdown = listOf(ChapterStat("One", 2), ChapterStat("Two", 1)),
        )
        val a = note(1, "Two", color = "#4ADE80", note = "n")
        val recoloured = NotesLogic.statsAfter(stats, a, a.copy(color = "#FACC15", note = null))
        assertEquals(listOf(ColorCount("#FACC15", 3)), recoloured.colorBreakdown)
        assertEquals(0, recoloured.highlightsWithNotes)
        val deleted = NotesLogic.statsAfter(stats, a, null)
        assertEquals(2, deleted.totalHighlights)
        assertEquals(1, deleted.chaptersWithHighlights)
        assertEquals(listOf(ChapterStat("One", 2)), deleted.chapterBreakdown)
    }

    @Test
    fun colorsAndFileNames() {
        assertEquals(0xFFFACC15.toInt(), HighlightColors.argb("#FACC15"))
        assertEquals(0xFFFF3300.toInt(), HighlightColors.argb("ff3300"))
        assertEquals(0xFFFACC15.toInt(), HighlightColors.argb("nope"))
        assertEquals("gray", HighlightColors.nameOf("#9ca3af"))
        assertEquals("Moby Dick highlights.md", NotesLogic.exportFileName("Moby/Dick"))
        assertEquals("Book highlights.md", NotesLogic.exportFileName(" "))
        assertEquals(listOf("#FACC15" to 0.75f, "#4ADE80" to 0.25f), NotesLogic.shares(listOf(ColorCount("#4ADE80", 1), ColorCount("#FACC15", 3))))
    }
}
