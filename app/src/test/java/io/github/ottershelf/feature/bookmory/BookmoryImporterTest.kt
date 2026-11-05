package io.github.ottershelf.feature.bookmory

import io.github.ottershelf.core.network.ApiJson
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole import (analysis, preview, run) of the synthetic export against [FakeBookOrbit]: what it
 * creates, that a second run creates nothing, and that a run cut off anywhere and run again ends
 * exactly where one clean run does.
 */
class BookmoryImporterTest {

    private val file = BookmoryCsv.parse(sampleCsv())
    private val backlogDay = LocalDate.of(2018, 1, 12)

    /** The user's library before the import: two books from the export, one with a reading already. */
    private fun server() = FakeBookOrbit().apply {
        addBook("Quiet Harbours", listOf("Somebody Else"), isbn13 = "9780000000019")
        val moons = addBook("Paper Moons", listOf("Odile Marsh"))
        addAttempt(moons.id, LocalDate.of(2019, 1, 1), LocalDate.of(2019, 1, 22), "completed")
        addBook("Unrelated Book", listOf("Nobody"))
    }

    private fun importOnce(server: FakeBookOrbit, choices: ImportChoices = ImportChoices(libraryId = 1)): Pair<ImportPreview, ImportSummary> = runBlocking {
        val importer = BookmoryImporter(server, pause = {})
        val preview = ImportPreviews.preview(analyse(file, server, importer), choices)
        preview to importer.run(preview.items)
    }

    @Test
    fun aCleanRun() {
        val server = server()
        val (preview, summary) = importOnce(server)
        val t = preview.totals
        assertEquals(16, t.rows)
        assertEquals(2, t.matched)
        assertEquals(13, t.stubs)
        assertEquals(0, t.ambiguous)
        assertEquals(1, t.problems)
        assertEquals(1, t.skipped)
        assertEquals(1, t.readingsPresent) // Paper Moons' 2019 reading
        assertFalse(preview.nothingNew)

        assertFalse(summary.interrupted)
        assertEquals(emptyList<ImportProblem>(), summary.problems)
        assertEquals(13, summary.stubsCreated.size)
        assertEquals(t.readingsToAdd, summary.readingsAdded)
        assertEquals(t.ratings, summary.ratingsSet)
        assertEquals(t.statuses, summary.statusesSet)
        assertEquals(0, server.attemptEdits)

        fun book(title: String) = server.books.single { it.title.startsWith(title) }
        fun readings(title: String) = server.attempts.filter { it.bookId == book(title).id }.sortedBy { it.id }
        // Matched by ISBN, despite the other author.
        assertEquals(1, readings("Quiet Harbours").size)
        assertEquals(4, server.ratings[book("Quiet Harbours").id])
        assertEquals("read", server.statusRows[book("Quiet Harbours").id])
        // The 2019 reading wasn't added again; the two others were, oldest first.
        assertEquals(listOf(2019, 2020, 2021), readings("Paper Moons").map { it.startedOn!!.year })
        // A reread still going.
        val lantern = book("The \"Lantern\" Keeper")
        assertEquals(listOf("Ivo Brand", "Lena Ferro"), lantern.authors)
        assertEquals(listOf("completed", null), readings("The \"Lantern\"").map { it.outcome })
        assertEquals("rereading", server.statusRows[lantern.id])
        assertEquals("abandoned", server.statusRows[book("I Haven't Slept").id])
        assertEquals("abandoned", server.statusRows[book("Cold Iron Stars").id])
        assertEquals("reading", server.statusRows[book("Fern & Feather").id])
        assertEquals("want_to_read", server.statusRows[book("Orchard of Glass").id])
        assertEquals("9780000000040", book("Orchard of Glass").isbn13)
        assertEquals("want_to_read", server.statusRows[book("Saltwater Atlas").id])
        assertEquals(listOf<LocalDate?>(null), readings("The Unlogged Shelf").map { it.startedOn })
        assertEquals(backlogDay, readings("Backlog Book One").single().startedOn)
        assertTrue(server.books.none { it.title == "Broken Row" })
    }

    @Test
    fun theSecondRunCreatesNothing() {
        val server = server()
        importOnce(server)
        val after = server.snapshot()
        val booksAfter = server.books.size
        val writesAfter = server.writes

        val (preview, summary) = importOnce(server)
        assertTrue(preview.nothingNew)
        assertEquals(15, preview.totals.matched)
        assertEquals(0, preview.totals.stubs)
        assertTrue(summary.nothingChanged)
        assertEquals(booksAfter, server.books.size)
        assertEquals(writesAfter, server.writes)
        assertEquals(after, server.snapshot())
    }

    @Test
    fun aRunCutOffAnywhereThenRunAgainEndsAsOneCleanRun() {
        val clean = server().also { importOnce(it) }
        val expected = clean.snapshot()
        val writes = clean.writes - server().writes
        for (applied in listOf(false, true)) {
            for (cut in 1..writes) {
                val server = server()
                server.failAfter = server.writes + cut
                server.failAfterApplying = applied
                val (_, first) = importOnce(server)
                assertTrue("cut at $cut ($applied)", first.interrupted)
                var runs = 0
                do {
                    val (_, again) = importOnce(server)
                    runs++
                } while (again.interrupted && runs < 3)
                assertEquals("cut at write $cut (applied: $applied)", expected, server.snapshot())
                assertEquals(0, server.attemptEdits)
            }
        }
    }

    @Test
    fun theUploadIsResumedWhenTheSessionsAreStillThere() = runBlocking {
        // Cut off right after the first stub's session was made and filled, before `complete`:
        // the analysis can't see a book yet, so the same key picks the session up.
        val server = server()
        val importer = BookmoryImporter(server, pause = {})
        val preview = ImportPreviews.preview(analyse(file, server, importer), ImportChoices(libraryId = 1))
        val stubItem = preview.items.first { it.target is ImportTarget.Stub }
        server.failAfter = server.writes + 3 // createUpload, chunk, then complete fails
        assertTrue(importer.run(listOf(stubItem)).interrupted)
        val booksBefore = server.books.size
        val summary = importer.run(listOf(stubItem))
        assertEquals(booksBefore + 1, server.books.size)
        assertEquals(1, summary.stubsCreated.size + summary.stubsFound)
    }

    @Test
    fun aDeletedStubIsReportedAndNotMadeAgain() = runBlocking {
        val server = server()
        val importer = BookmoryImporter(server, pause = {})
        val preview = ImportPreviews.preview(analyse(file, server, importer), ImportChoices(libraryId = 1))
        val stubItem = preview.items.first { it.target is ImportTarget.Stub }
        importer.run(listOf(stubItem))
        val stub = server.books.last()
        server.deleteBook(stub.id)
        val count = server.books.size
        val summary = importer.run(listOf(stubItem))
        assertEquals(count, server.books.size)
        assertEquals(ImportProblem.Kind.STUB_DELETED, summary.problems.single().kind)
    }

    @Test
    fun withoutALibraryNoStubsAreMade() {
        val server = server()
        val (preview, summary) = importOnce(server, ImportChoices(libraryId = null))
        assertEquals(0, preview.totals.stubs)
        assertEquals(14, preview.totals.skipped)
        assertEquals(3, server.books.size)
        assertTrue(summary.stubsCreated.isEmpty())
        assertTrue(preview.rows.count { it.kind == RowKind.NO_LIBRARY } == 13)
    }

    @Test
    fun onlyLibrariesTakingEpubAreOffered() = runBlocking {
        val server = server()
        val analysis = analyse(file, server, BookmoryImporter(server, pause = {}))
        assertEquals(listOf(1L), analysis.libraries.map { it.id })
        // A library the user can't upload to is no choice.
        assertEquals(0, ImportPreviews.preview(analysis, ImportChoices(libraryId = 2)).totals.stubs)
    }

    @Test
    fun backlogReadingsCanGoInWithoutDates() {
        val server = server()
        val (preview, _) = importOnce(server, ImportChoices(libraryId = 1, undated = setOf(backlogDay)))
        assertEquals(mapOf(backlogDay to 5), preview.backlog)
        val backlog = server.books.filter { it.title.startsWith("Backlog Book") }
        assertEquals(5, backlog.size)
        backlog.forEach { b ->
            val r = server.attempts.single { it.bookId == b.id }
            assertEquals(null, r.startedOn)
            assertEquals(null, r.endedOn)
            assertEquals("completed", r.outcome)
            assertEquals("read", server.statusRows[b.id])
        }
        // Run again either way: nothing new.
        assertTrue(importOnce(server, ImportChoices(libraryId = 1, undated = setOf(backlogDay))).first.nothingNew)
        assertTrue(importOnce(server, ImportChoices(libraryId = 1)).first.nothingNew)
    }

    @Test
    fun ambiguousRowsWaitForThePick() = runBlocking {
        val server = server()
        val first = server.addBook("Saltwater Atlas", listOf("Nico Pell"))
        server.addBook("Saltwater Atlas", listOf("Nico Pell"))
        val importer = BookmoryImporter(server, pause = {})
        val analysis = analyse(file, server, importer)
        val line = file.rows.single { it.title == "Saltwater Atlas" }.line
        val preview = ImportPreviews.preview(analysis, ImportChoices(libraryId = 1))
        assertEquals(1, preview.totals.ambiguous)
        assertEquals(RowKind.AMBIGUOUS, preview.rows.single { it.line == line }.kind)
        assertTrue(preview.items.none { it.plan.row.line == line })
        val picked = ImportPreviews.preview(analysis, ImportChoices(libraryId = 1, rows = mapOf(line to RowChoice.Book(first.id))))
        assertEquals(0, picked.totals.ambiguous)
        assertEquals(ImportTarget.Book(first.id), picked.items.single { it.plan.row.line == line }.target)
        val excluded = ImportPreviews.preview(analysis, ImportChoices(libraryId = 1, rows = mapOf(line to RowChoice.Exclude)))
        assertEquals(RowKind.EXCLUDED, excluded.rows.single { it.line == line }.kind)
    }

    @Test
    fun refusedRatingsAreReportedOnce() {
        val server = server().apply { mayRate = false }
        val (_, summary) = importOnce(server)
        assertEquals(1, summary.problems.count { it.kind == ImportProblem.Kind.RATING_REFUSED })
        assertEquals(0, summary.ratingsSet)
        // Tried once (the unexpected 403), then left out without asking.
        assertEquals(1, server.ratingCalls)
        assertEquals(importOnce(server()).first.totals.ratings, summary.ratingsLeftOut)
        assertFalse(summary.interrupted)
    }

    @Test
    fun theUploadBodyHasExactlyTheDtoFields() {
        val body = BookmoryBodies.createUpload(
            CreateUploadBody("a.epub", 10, "ottershelf-bookmory-0123456789abcdef", UploadTargetBody(libraryId = 3), StubEpub.CONTENT_TYPE, "0".repeat(64)),
        )
        assertEquals(setOf("filename", "sizeBytes", "idempotencyKey", "target", "contentType", "sha256"), body.keys)
        assertEquals(setOf("kind", "libraryId"), (body["target"] as JsonObject).keys)
        // The serializer gives the same JSON.
        assertEquals(
            body,
            ApiJson.encodeToJsonElement(
                CreateUploadBody.serializer(),
                CreateUploadBody("a.epub", 10, "ottershelf-bookmory-0123456789abcdef", UploadTargetBody(libraryId = 3), StubEpub.CONTENT_TYPE, "0".repeat(64)),
            ),
        )
    }

    @Test
    fun readingsBookOrbitHasWithOtherDatesAreLeftAloneAndTheSecondRunWritesNothing() {
        val server = server().apply {
            // Read in BookOrbit too: an open reading that started two days after Bookmory's, and a
            // finished one that started later but ended the same day with the same outcome.
            addAttempt(addBook("Fern & Feather", listOf("Juno Hale")).id, LocalDate.of(2023, 9, 11), null, null)
            addAttempt(books.single { it.title == "Quiet Harbours" }.id, LocalDate.of(2021, 3, 10), LocalDate.of(2021, 3, 15), "completed")
        }
        val (preview, first) = importOnce(server)
        assertEquals(2, preview.totals.readingsLeftAlone)
        assertEquals(2, first.readingsLeftAlone)
        fun readings(title: String) = server.attempts.filter { a -> a.bookId == server.books.single { it.title == title }.id }
        assertEquals(listOf(LocalDate.of(2023, 9, 11)), readings("Fern & Feather").map { it.startedOn })
        assertEquals(listOf(LocalDate.of(2021, 3, 10)), readings("Quiet Harbours").map { it.startedOn })
        assertEquals(0, server.attemptEdits)

        val writes = server.writes
        val snapshot = server.snapshot()
        val (again, second) = importOnce(server)
        assertTrue(again.nothingNew)
        assertEquals(0, again.totals.readingsToAdd)
        assertEquals(2, again.totals.readingsLeftAlone)
        assertEquals(listOf(0, 0, 0, 0), listOf(second.readingsAdded, second.statusesSet, second.ratingsSet, second.stubsCreated.size))
        assertEquals(writes, server.writes)
        assertEquals(snapshot, server.snapshot())
    }

    @Test
    fun aWriteTheServerAnswersWithoutMakingAReadingIsNotCountedAsAdded() {
        val server = server().apply { ignoreStartReading = true }
        val (preview, summary) = importOnce(server)
        val opens = preview.items.sumOf { item -> item.plan.readings.count { it.isOpen } }
        assertEquals(2, opens) // the Lantern Keeper's reread and Fern & Feather
        assertEquals(preview.totals.readingsToAdd - opens, summary.readingsAdded)
        assertEquals(opens, summary.readingsLeftAlone)
        assertEquals(server.attempts.size - 1, summary.readingsAdded) // the one before the import was Paper Moons'
    }

    @Test
    fun withoutThePermissionRatingsAreLeftOutUpFront() {
        val planned = importOnce(server()).first.totals.ratings
        val server = server().apply { access = RatingAccess(permitted = false) }
        val (preview, summary) = importOnce(server)
        assertTrue(preview.totals.ratingsNotPermitted)
        assertEquals(0, preview.totals.ratings)
        assertEquals(planned, preview.totals.ratingsBlocked)
        assertEquals(0, server.ratingCalls)
        assertEquals(planned, summary.ratingsLeftOut)
        assertTrue(summary.problems.isEmpty())
        // Everything else still went in.
        assertTrue(summary.readingsAdded > 0)
    }

    @Test
    fun ratingsOnlyInLibrariesWhereTheAccountIsAnEditor() {
        val server = server().apply {
            books.replaceAll { if (it.title == "Paper Moons") it.copy(libraryId = 3) else it }
            access = RatingAccess(editableLibraries = setOf(1), libraryNames = mapOf(1L to "Books", 3L to "Shared shelf"))
        }
        val (preview, summary) = importOnce(server)
        assertFalse(preview.totals.ratingsNotPermitted)
        assertEquals(1, preview.totals.ratingsBlocked)
        val moons = preview.rows.single { it.title.startsWith("Paper Moons") }
        assertEquals(RatingRule.NotEditor("Shared shelf"), moons.ratingBlockedBy)
        assertEquals(1, summary.ratingsLeftOut)
        assertEquals(listOf(ImportProblem.Kind.RATING_NOT_EDITOR), summary.problems.map { it.kind })
        assertEquals("Shared shelf", summary.problems.single().detail)
        assertEquals(preview.totals.ratings, summary.ratingsSet)
        assertEquals(summary.ratingsSet, server.ratingCalls) // no request was refused
        assertEquals(null, server.ratings[server.books.single { it.title == "Paper Moons" }.id])
    }

    @Test
    fun ratingRules() {
        val access = RatingAccess(editableLibraries = setOf(1), libraryNames = mapOf(2L to "Kids"))
        assertEquals(RatingRule.Allowed, access.rule(1))
        assertEquals(RatingRule.NotEditor("Kids"), access.rule(2))
        assertEquals(RatingRule.Allowed, access.rule(null))
        assertEquals(RatingRule.Allowed, RatingAccess().rule(9))
        assertEquals(RatingRule.NoPermission, RatingAccess(permitted = false).rule(1))
    }
}
