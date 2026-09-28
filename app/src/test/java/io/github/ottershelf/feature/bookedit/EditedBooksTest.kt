package io.github.ottershelf.feature.bookedit

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.github.ottershelf.core.model.AuthorRef
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.CurrentlyReadingBook
import io.github.ottershelf.core.model.SeriesMembership
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.sync.ReadingChanges
import io.github.ottershelf.core.sync.edited
import io.github.ottershelf.core.sync.isInSeries
import io.github.ottershelf.core.sync.newEdits
import io.github.ottershelf.core.sync.withDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** How loaded cards show a book edited here, and which cached images a cover change forgets. */
class EditedBooksTest {

    private val card = BookCard(
        id = 7,
        title = "The Wonderful Wizard of Oz",
        authors = listOf("Frank Baum"),
        seriesName = "Oz",
        seriesIndex = "1",
        hasCover = false,
        addedAt = "2026-01-01T00:00:00.000Z",
        updatedAt = "2026-03-01T10:00:00.000Z",
        readingProgress = 42.0,
    )

    private val edited = BookDetail(
        id = 7,
        title = "The Wonderful Wizard of Oz",
        authors = listOf(AuthorRef(3, "L. Frank Baum")),
        seriesId = 9,
        seriesName = "The Oz Books",
        seriesIndex = "1",
        coverSource = "custom",
        updatedAt = "2026-09-26T12:00:00.000Z",
    )

    @Test
    fun anEditedCardShowsTheEditAndItsNewCoverVersion() {
        val shown = card.edited(mapOf(7L to edited))
        assertEquals(listOf("L. Frank Baum"), shown.authors)
        assertEquals("The Oz Books", shown.seriesName)
        assertTrue(shown.hasCover)
        // The thumbnail URL is versioned by this, so the new cover is asked for.
        assertEquals("2026-09-26T12:00:00.000Z", shown.updatedAt)
        // What the edit doesn't carry is kept.
        assertEquals(42.0, shown.readingProgress!!, 0.0)
        assertEquals(card.addedAt, shown.addedAt)
    }

    @Test
    fun otherBooksAreLeftAlone() {
        assertSame(card, card.edited(emptyMap()))
        assertSame(card, card.edited(mapOf(8L to edited.copy(id = 8))))
    }

    @Test
    fun aCardOnTheOtherSeriesPageKeepsThatSeriesAndItsNumber() {
        // #1 in The Oz Books (its main series, 9) and #7 in Oz Omnibus (21), whose page shows it
        // as Oz Omnibus 7 (series/:id/books gives each card the page's series).
        val inBoth = edited.copy(
            title = "The Wonderful Wizard of Oz!",
            seriesMemberships = listOf(SeriesMembership(9, "The Oz Books", "1"), SeriesMembership(21, "Oz Omnibus", "7")),
        )
        val onOmnibus = card.copy(seriesName = "Oz Omnibus", seriesIndex = "7")
        val shown = onOmnibus.edited(mapOf(7L to inBoth), inSeries = 21)
        assertEquals("The Wonderful Wizard of Oz!", shown.title)
        assertEquals("Oz Omnibus" to "7", shown.seriesName to shown.seriesIndex)
        // Anywhere else (all books, an author's): the main series.
        assertEquals("The Oz Books" to "1", onOmnibus.edited(mapOf(7L to inBoth)).let { it.seriesName to it.seriesIndex })
        // The number changed in the page's own series: the new one.
        val renumbered = inBoth.copy(seriesIndex = "2", seriesMemberships = listOf(SeriesMembership(9, "The Oz Books", "2")))
        assertEquals("2", card.withDetails(renumbered, inSeries = 9).seriesIndex)
        // No memberships sent: the main series decides.
        assertEquals("1", card.withDetails(edited, inSeries = 9).seriesIndex)
        // Taken out of the omnibus: the card stays as it was (the page loads again without it).
        val left = inBoth.copy(seriesMemberships = listOf(SeriesMembership(9, "The Oz Books", "1")))
        assertEquals("Oz Omnibus" to "7", onOmnibus.withDetails(left, inSeries = 21).let { it.seriesName to it.seriesIndex })
        assertTrue(inBoth.isInSeries(21))
        assertFalse(left.isInSeries(21))
        assertTrue(edited.isInSeries(9))
    }

    @Test
    fun theDetailCarriesEverySeriesTheBookIsIn() {
        val json = """{"id":7,"seriesId":9,"seriesName":"The Oz Books","seriesIndex":"1","seriesMemberships":[""" +
            """{"seriesId":9,"seriesName":"The Oz Books","seriesIndex":"1","displayOrder":0,"expectedBookCount":9},""" +
            """{"seriesId":21,"seriesName":"Oz Omnibus","seriesIndex":null,"displayOrder":1,"expectedBookCount":null}]}"""
        val book = ApiJson.decodeFromString(BookDetail.serializer(), json)
        assertEquals(listOf(SeriesMembership(9, "The Oz Books", "1"), SeriesMembership(21, "Oz Omnibus", null)), book.seriesMemberships)
    }

    @Test
    fun onlyWhatWasAskedForBeforeAnEditTakesIt() {
        val changes = ReadingChanges()
        val before = changes.editMark
        // Authors only: the server leaves the book's updatedAt as it was, so it can't tell the edit
        // from a later change on the web.
        changes.bookEdited(edited.copy(updatedAt = card.updatedAt))
        val after = changes.editMark
        // Asked for before the edit: the answer may be from before it.
        assertEquals(listOf("L. Frank Baum"), card.edited(changes.editedSince(before)).authors)
        // Asked for since: the server's word, even with the same updatedAt (someone changed the
        // authors back on the web meanwhile).
        assertTrue(changes.editedSince(after).isEmpty())
        assertSame(card, card.edited(changes.editedSince(after)))
        // Edited again: only that edit is new since the first.
        changes.bookEdited(edited.copy(title = "The Wonderful Wizard of Oz, again"))
        assertEquals("The Wonderful Wizard of Oz, again", changes.editedSince(after).getValue(7).title)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun newEditsGivesEachEditOnceFromWhenItIsCollected() = runTest {
        val changes = ReadingChanges()
        changes.bookEdited(edited) // before: the cards a screen loads from now on already have it
        val seen = mutableListOf<Map<Long, String?>>()
        val collecting = launch { changes.newEdits().collect { edits -> seen += edits.mapValues { it.value.title } } }
        runCurrent()
        assertTrue(seen.isEmpty())
        changes.bookEdited(edited.copy(title = "Second"))
        runCurrent()
        changes.bookEdited(edited.copy(id = 8, title = "Another"))
        runCurrent()
        assertEquals(listOf(mapOf(7L to "Second"), mapOf(8L to "Another")), seen)
        // Two before it looks: both at once.
        changes.bookEdited(edited.copy(title = "Third"))
        changes.bookEdited(edited.copy(id = 8, title = "Another again"))
        runCurrent()
        assertEquals(mapOf(7L to "Third", 8L to "Another again"), seen.last())
        changes.reset()
        runCurrent()
        assertEquals(3, seen.size)
        collecting.cancel()
    }

    @Test
    fun aRemovedCoverShowsTheGeneratedOne() {
        assertFalse(card.copy(hasCover = true).withDetails(edited.copy(coverSource = null)).hasCover)
    }

    @Test
    fun currentlyReadingRowsFollowToo() {
        val row = CurrentlyReadingBook(bookId = 7, title = "Old", authors = listOf("A"), progress = 10.0, hasCover = false)
        val shown = row.withDetails(edited)
        assertEquals("The Wonderful Wizard of Oz", shown.title)
        assertEquals(listOf("L. Frank Baum"), shown.authors)
        assertTrue(shown.hasCover)
        assertEquals(10.0, shown.progress, 0.0)
    }

    @Test
    fun readingChangesKeepsTheLatestEditPerBookAndTellsTheDashboard() {
        val changes = ReadingChanges()
        val before = changes.changes.value
        changes.bookEdited(edited)
        changes.bookEdited(edited.copy(title = "The Wonderful Wizard of Oz, again"))
        assertEquals("The Wonderful Wizard of Oz, again", changes.editedBooks.value.getValue(7).book.title)
        assertEquals(before + 2, changes.changes.value)
        changes.reset()
        assertTrue(changes.editedBooks.value.isEmpty())
    }

    // --- the image caches -----------------------------------------------------------------------------

    private val unversioned = "https://books.example.net/api/v1/books/12/thumbnail"
    private val prefix = CoverCache.bookPrefix(unversioned)

    @Test
    fun everyImageOfTheBookIsForgottenAndNoOtherBooks() {
        assertEquals("https://books.example.net/api/v1/books/12/", prefix)
        val forgotten = listOf(
            "https://books.example.net/api/v1/books/12/thumbnail?t=1700000000000",
            "https://books.example.net/api/v1/books/12/cover?t=1700000000000",
            "https://books.example.net/api/v1/books/12/thumbnail",
            "https://books.example.net/api/v1/books/12/thumbnail#20357",
            "tint:https://books.example.net/api/v1/books/12/thumbnail?t=1700000000000",
        )
        val kept = listOf(
            "https://books.example.net/api/v1/books/123/thumbnail?t=1",
            "https://books.example.net/api/v1/books/1/thumbnail",
            "https://books.example.net/api/v1/authors/12/thumbnail?t=1",
            "/data/user/0/app/files/downloads/a/12/cover.jpg:1700000000000",
        )
        forgotten.forEach { assertTrue(it, CoverCache.isForBook(it, prefix)) }
        kept.forEach { assertFalse(it, CoverCache.isForBook(it, prefix)) }
    }

    @Test
    fun theDiskLosesTheUnversionedCopiesTheirDayKeysAndTheOldVersions() {
        val day = 20357L
        val now = day * 24 * 60 * 60 * 1000L + 5_000
        val old = "https://books.example.net/api/v1/books/12/thumbnail?t=1700000000000"
        assertEquals(
            listOf(unversioned, "$unversioned#$day", "$unversioned#${day - 1}", old),
            CoverCache.diskKeys(unversioned, listOf(old, old), now),
        )
    }
}
