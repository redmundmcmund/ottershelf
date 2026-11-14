package io.github.ottershelf.feature.bookedit

import io.github.ottershelf.core.model.AuthorRef
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.ComicMetadata
import io.github.ottershelf.core.model.SeriesMembership
import io.github.ottershelf.core.network.ApiJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The full editor's fields (Settings > Full metadata editing) against UpdateBookMetadataDto: only
 * what changed is sent, a cleared field goes as null, `seriesMemberships` replaces the main series
 * fields, and a comic's changes go in one `comicMetadata` object.
 */
class FullEditModelsTest {

    private val book = BookDetail(
        id = 1,
        title = "Watchmen",
        subtitle = "Absolute Edition",
        description = "<p>Who watches <i>the</i> watchmen?</p><p>A &amp; B<br>line two</p>",
        authors = listOf(AuthorRef(1, "Alan Moore")),
        seriesId = 4,
        seriesName = "Watchmen",
        seriesIndex = "1",
        seriesMemberships = listOf(SeriesMembership(7, "DC Black Label", "12"), SeriesMembership(4, "Watchmen", "1")),
        publisher = "DC",
        publishedDate = "1987-09-01",
        publishedYear = 1987,
        pageCount = 416,
        language = "en",
        isbn13 = "9781401245252",
        genres = listOf("Superheroes"),
        tags = listOf("Classic"),
        comicMetadata = ComicMetadata(issueNumber = "1", pencillers = listOf("Dave Gibbons")),
    )

    private val original = EditForm.of(book)

    /** Every field UpdateBookMetadataDto has that this app may send. */
    private val dtoFields = setOf(
        "title", "authors", "seriesName", "seriesIndex", "subtitle", "description", "publisher", "publishedDate",
        "publishedYear", "language", "pageCount", "isbn13", "isbn10", "genres", "tags", "seriesMemberships", "comicMetadata",
    )

    private fun json(form: EditForm, locked: List<String> = emptyList()): String =
        ApiJson.encodeToString(JsonObject.serializer(), MetadataChanges.of(original, form, locked).body())

    @Test
    fun loadsEveryFieldFromTheBook() {
        assertEquals("Absolute Edition", original.subtitle)
        assertEquals("Who watches the watchmen?\n\nA & B\nline two", original.description)
        assertEquals("1987-09-01", original.published)
        assertEquals("416", original.pages)
        assertEquals(listOf(SeriesEntry("DC Black Label", "12")), original.otherSeries)
        assertEquals("1", original.comic.issueNumber)
        assertEquals(listOf("Dave Gibbons"), original.comic.list(ComicList.PENCILLERS))
    }

    @Test
    fun anUntouchedFormSendsNothing() {
        assertTrue(MetadataChanges.of(original, original).isEmpty)
        assertEquals("{}", json(original))
    }

    @Test
    fun changedFieldsOnlyAndClearedOnesAsNull() {
        val body = json(original.copy(subtitle = "  ", publisher = "DC Comics", pages = "420", isbn13 = "978-1-4012-4525-2 "))
        assertEquals("""{"subtitle":null,"publisher":"DC Comics","pageCount":420}""", body)
    }

    @Test
    fun aYearOrAFullDate() {
        assertEquals("""{"publishedYear":1986}""", json(original.copy(published = "1986")))
        assertEquals("""{"publishedDate":"1986-12-01"}""", json(original.copy(published = "1986-12-01")))
        assertEquals("""{"publishedDate":null,"publishedYear":null}""", json(original.copy(published = "")))
        assertTrue(FormProblem.PUBLISHED_INVALID in problemsOf(original, original.copy(published = "1986-02-30")))
        assertTrue(FormProblem.PUBLISHED_INVALID in problemsOf(original, original.copy(published = "86")))
        assertTrue(FormProblem.PAGES_INVALID in problemsOf(original, original.copy(pages = "0")))
    }

    @Test
    fun anEditedDescriptionGoesBackAsParagraphs() {
        val body = json(original.copy(description = "First <para>.\n\nSecond\nline"))
        assertEquals("""{"description":"<p>First &lt;para&gt;.</p><p>Second<br>line</p>"}""", body)
        assertEquals("""{"description":null}""", json(original.copy(description = "")))
    }

    @Test
    fun namesAreNormalisedAndSentWhole() {
        assertEquals(
            """{"genres":["Superheroes","Crime"],"tags":[]}""",
            json(original.copy(genres = listOf("Superheroes", " Crime ", "crime"), tags = emptyList())),
        )
    }

    @Test
    fun otherSeriesSendEveryMembershipMainFirst() {
        val body = json(original.copy(seriesIndex = "2", otherSeries = listOf(SeriesEntry("DC Black Label", "12"), SeriesEntry("Alan Moore Library", ""))))
        assertEquals(
            """{"seriesMemberships":[{"seriesName":"Watchmen","seriesIndex":"2"},{"seriesName":"DC Black Label","seriesIndex":"12"},""" +
                """{"seriesName":"Alan Moore Library","seriesIndex":null}]}""",
            body,
        )
        // Only the main series changed: the light editor's two fields, as before.
        assertEquals("""{"seriesName":"Watchmen","seriesIndex":"2"}""", json(original.copy(seriesIndex = "2")))
        assertTrue(FormProblem.OTHER_SERIES_INVALID in problemsOf(original, original.copy(otherSeries = listOf(SeriesEntry("X", "1.")))))
    }

    @Test
    fun aComicsChangesGoInOneObject() {
        val form = original.copy(comic = original.comic.copy(volumeName = "Absolute").withList(ComicList.COLORISTS, listOf("John Higgins")))
        assertEquals("""{"comicMetadata":{"volumeName":"Absolute","colorists":["John Higgins"]}}""", json(form))
    }

    @Test
    fun lockedFieldsAreNotSentAndShowAsLoaded() {
        val locked = listOf("publisher", "comicColorists", "seriesName")
        val form = original.copy(
            publisher = "Other",
            comic = original.comic.withList(ComicList.COLORISTS, listOf("X")),
            otherSeries = emptyList(),
            language = "fr",
        )
        assertEquals("""{"language":"fr"}""", json(form, locked))
        val kept = form.keepingLocked(original, locked)
        assertEquals("DC", kept.publisher)
        assertEquals(original.otherSeries, kept.otherSeries)
        assertEquals(emptyList<String>(), kept.comic.list(ComicList.COLORISTS))
        assertEquals("fr", kept.language)
    }

    @Test
    fun lockAddsAndUnlockRemovesTheGroupsFields() {
        assertEquals(listOf("title", "isbn13"), LockGroup.ISBN13.lock(listOf("title")))
        assertEquals(listOf("title"), LockGroup.ISBN13.unlock(listOf("title", "isbn13")))
        assertEquals(LockGroup.COMIC_TEAMS, LockGroup.of(ComicList.TEAMS))
    }

    @Test
    fun onlyKnownDtoFieldsAreEverSent() {
        val everything = original.copy(
            title = "W", authors = listOf("A"), subtitle = "s", description = "d", publisher = "p", published = "2000",
            language = "de", pages = "1", isbn13 = "1", isbn10 = "1", genres = listOf("g"), tags = listOf("t"),
            otherSeries = emptyList(), comic = ComicForm(issueNumber = "2"),
        )
        val body = MetadataChanges.of(original, everything).body()
        assertTrue(body.keys.toString(), dtoFields.containsAll(body.keys))
    }

    @Test
    fun plainDescriptionsComeThroughAsTheyAre() {
        assertEquals("Just text", DescriptionText.fromHtml("Just text"))
        assertEquals("", DescriptionText.fromHtml(""))
        assertNull(DescriptionText.toHtml("  \n\n "))
        assertEquals("• one\n\n• two", DescriptionText.fromHtml("<ul><li>one</li><li>two</li></ul>"))
    }

    @Test
    fun aSavedFormFromAnOlderVersionReadsBack() {
        val old = """{"title":"T","authors":["A"],"series":"","seriesIndex":""}"""
        val form = ApiJson.decodeFromString(EditForm.serializer(), old)
        assertEquals("T", form.title)
        assertTrue(form.comic.credits.isEmpty())
    }
}
