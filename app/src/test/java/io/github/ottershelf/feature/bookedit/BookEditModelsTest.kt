package io.github.ottershelf.feature.bookedit

import kotlinx.serialization.json.JsonObject
import io.github.ottershelf.core.model.AuthorRef
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.network.ApiException
import io.github.ottershelf.core.network.ApiJson
import okhttp3.MultipartBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Change detection and the request bodies, checked against the server's DTOs (the server rejects
 * unknown fields): UpdateBookMetadataDto (title MaxLength 1000, authors string[], seriesName
 * MaxLength 500, seriesIndex matching SERIES_INDEX_PATTERN), UpdateBookMetadataLocksDto,
 * UploadCoverFromUrlDto, SearchCoversQueryDto and the multipart cover upload.
 */
class BookEditModelsTest {

    private val original = EditForm(title = "The Lost World", authors = listOf("Arthur Conan Doyle"), series = "Professor Challenger", seriesIndex = "4")

    /** The DTO fields this screen may send; anything else would be a 400. */
    private val dtoFields = setOf("title", "authors", "seriesName", "seriesIndex")

    private fun json(changes: MetadataChanges): String = ApiJson.encodeToString(JsonObject.serializer(), changes.body())

    @Test
    fun nothingChangedSendsNothing() {
        val changes = MetadataChanges.of(original, original)
        assertTrue(changes.isEmpty)
        assertEquals("{}", json(changes))
    }

    @Test
    fun onlyTheChangedFieldIsSentTrimmed() {
        val changes = MetadataChanges.of(original, original.copy(title = "  The White Company  "))
        assertEquals("""{"title":"The White Company"}""", json(changes))
    }

    @Test
    fun spacesAroundAnUnchangedTitleAreNoChange() {
        assertTrue(MetadataChanges.of(original, original.copy(title = " The Lost World ")).isEmpty)
        assertTrue(MetadataChanges.of(original.copy(title = "Title "), original.copy(title = "Title")).isEmpty)
    }

    @Test
    fun authorsAreSentAsNamesInOrderFirstStaysFirst() {
        val form = original.copy(authors = listOf("Arthur Conan Doyle", "  Tom   Smith "))
        assertEquals("""{"authors":["Arthur Conan Doyle","Tom Smith"]}""", json(MetadataChanges.of(original, form)))
    }

    @Test
    fun authorsInAnotherOrderAreAChange() {
        val two = original.copy(authors = listOf("A", "B"))
        assertEquals(listOf("B", "A"), MetadataChanges.of(two, two.copy(authors = listOf("B", "A"))).authors)
    }

    @Test
    fun repeatedAuthorsAreSentOnceAndSpacesNormalisedLikeTheServer() {
        assertEquals(listOf("Ann Radcliffe"), EditRules.authorsOf(listOf("Ann Radcliffe", "ann  radcliffe", " ", "ANN RADCLIFFE")))
        // The server's own copy with a double space is the same name.
        assertTrue(MetadataChanges.of(original.copy(authors = listOf("Arthur  Conan Doyle")), original).isEmpty)
    }

    @Test
    fun seriesNameAndNumberGoTogether() {
        assertEquals("""{"seriesName":"Professor Challenger","seriesIndex":"4.5"}""", json(MetadataChanges.of(original, original.copy(seriesIndex = "4.5"))))
        assertEquals("""{"seriesName":"Professor","seriesIndex":"4"}""", json(MetadataChanges.of(original, original.copy(series = "Professor"))))
    }

    @Test
    fun clearingTheSeriesSendsNullsForBoth() {
        val changes = MetadataChanges.of(original, original.copy(series = "", seriesIndex = ""))
        assertEquals("""{"seriesName":null,"seriesIndex":null}""", json(changes))
    }

    @Test
    fun aNumberWithoutASeriesIsNotSent() {
        val none = EditForm(title = "Solo")
        assertTrue(MetadataChanges.of(none, none.copy(seriesIndex = "3")).isEmpty)
    }

    @Test
    fun aSeriesWithoutANumberSendsANullNumber() {
        val none = EditForm(title = "Solo")
        assertEquals("""{"seriesName":"Sherlock Holmes","seriesIndex":null}""", json(MetadataChanges.of(none, none.copy(series = " Sherlock Holmes "))))
    }

    @Test
    fun aDecimalCommaIsReadAsAPoint() {
        assertEquals("2.5", MetadataChanges.of(original, original.copy(seriesIndex = "2,5")).series?.index)
    }

    @Test
    fun theBodyHoldsOnlyDtoFields() {
        val changes = MetadataChanges.of(original, EditForm(title = "X", authors = listOf("Y"), series = "Z", seriesIndex = "1"))
        assertEquals(dtoFields, changes.body().keys)
    }

    @Test
    fun lockedFieldsAreNotSent() {
        val form = EditForm(title = "X", authors = listOf("Y"), series = "Z", seriesIndex = "1")
        assertEquals(setOf("authors", "seriesName", "seriesIndex"), MetadataChanges.of(original, form, listOf("title")).body().keys)
        assertEquals(setOf("title", "authors"), MetadataChanges.of(original, form, listOf("seriesIndex")).body().keys)
        assertEquals(setOf("title", "authors"), MetadataChanges.of(original, form, listOf("seriesName")).body().keys)
        assertTrue(MetadataChanges.of(original, form, listOf("title", "authors", "seriesName", "cover")).isEmpty)
    }

    @Test
    fun seriesIndexIsValidatedAsTheServerDoes() {
        for (ok in listOf("2", "2.5", "10", "1.10", "0", "007", "12345678901234567890")) {
            assertTrue(ok, EditRules.isValidSeriesIndex(ok))
        }
        for (bad in listOf("", "2.", ".5", "-1", "+1", "2a", "1e3", "2..5", "1.2.3", " 2", "½", "123456789012345678901")) {
            assertFalse(bad, EditRules.isValidSeriesIndex(bad))
        }
    }

    @Test
    fun problemsStopTheSave() {
        assertEquals(setOf(FormProblem.TITLE_EMPTY), problemsOf(original, original.copy(title = "   ")))
        assertEquals(setOf(FormProblem.NO_AUTHORS), problemsOf(original, original.copy(authors = emptyList())))
        assertEquals(setOf(FormProblem.SERIES_INDEX_INVALID), problemsOf(original, original.copy(seriesIndex = "2.")))
        assertEquals(emptySet<FormProblem>(), problemsOf(original, original.copy(seriesIndex = "")))
        // A book without a title or authors isn't held to one it never had.
        val bare = EditForm()
        assertEquals(emptySet<FormProblem>(), problemsOf(bare, bare.copy(series = "S")))
        // Locked fields aren't sent, so they aren't checked.
        assertEquals(emptySet<FormProblem>(), problemsOf(original, original.copy(title = ""), listOf("title")))
    }

    @Test
    fun typedTitlesStayOneLineWithinTheLimit() {
        assertEquals("Two lines", EditRules.titleOf("Two\nlines"))
        assertEquals(EditRules.TITLE_MAX, EditRules.titleOf("x".repeat(1200)).length)
    }

    @Test
    fun aPastedListOfAuthorsBecomesChips() {
        val (authors, rest) = EditRules.typeAuthor(listOf("A"), "B, C;D, Ea")
        assertEquals(listOf("A", "B", "C", "D"), authors)
        assertEquals("Ea", rest)
        assertEquals(listOf("A") to "Conan D", EditRules.typeAuthor(listOf("A"), "Conan D"))
    }

    @Test
    fun addingAnAuthorTwiceInAnyCaseKeepsOne() {
        assertEquals(listOf("Ann Radcliffe"), EditRules.addAuthor(listOf("Ann Radcliffe"), " ann radcliffe "))
        assertEquals(listOf("Ann Radcliffe", "Edgar A. Poe"), EditRules.addAuthor(listOf("Ann Radcliffe"), "Edgar  A. Poe"))
        assertEquals(listOf("Ann Radcliffe"), EditRules.addAuthor(listOf("Ann Radcliffe"), "   "))
    }

    @Test
    fun lockedFieldsShowWhatTheyAre() {
        val typed = EditForm(title = "Typed", authors = listOf("Someone"), series = "Other", seriesIndex = "9")
        assertEquals(typed.copy(title = original.title), typed.keepingLocked(original, listOf("title", "rating")))
        assertEquals(typed.copy(series = original.series, seriesIndex = original.seriesIndex), typed.keepingLocked(original, listOf("seriesIndex")))
        assertEquals(typed, typed.keepingLocked(original, listOf("cover")))
    }

    @Test
    fun unlockingAGroupKeepsEveryOtherLock() {
        val locked = listOf("title", "seriesName", "seriesIndex", "rating", "cover")
        assertEquals(listOf("title", "rating", "cover"), LockGroup.SERIES.unlock(locked))
        assertEquals(listOf("seriesName", "seriesIndex", "rating", "cover"), LockGroup.TITLE.unlock(locked))
        assertTrue(LockGroup.SERIES.lockedIn(listOf("seriesIndex")))
        assertFalse(LockGroup.AUTHORS.lockedIn(locked))
    }

    @Test
    fun locksAndFromUrlBodiesMatchTheirDtos() {
        assertEquals("""{"lockedFields":["title","rating"]}""", ApiJson.encodeToString(JsonObject.serializer(), locksBody(listOf("title", "rating", "title"))))
        assertEquals("""{"url":"https://example.com/a.jpg"}""", ApiJson.encodeToString(JsonObject.serializer(), fromUrlBody("https://example.com/a.jpg")))
    }

    @Test
    fun theCoverSearchAsksWithTheQueryDtosFields() {
        assertEquals(
            "books/cover/search?title=The+Lost+World&author=Arthur+Conan+Doyle&isAudiobook=false&provider=duckduckgo",
            coverSearchQuery(" The Lost World ", "Arthur Conan Doyle", audiobook = false, provider = "duckduckgo"),
        )
        assertEquals("books/cover/search?title=Dracula&isAudiobook=true&provider=all", coverSearchQuery("Dracula", " ", audiobook = true, provider = "all"))
        assertEquals("duckduckgo", CoverProviders.of("something-else"))
        assertEquals("itunes", CoverProviders.of("itunes"))
    }

    @Test
    fun theUploadIsOneFilePartAsTheWebSendsIt() {
        val body = coverUploadBody(byteArrayOf(1, 2, 3))
        assertEquals(MultipartBody.FORM, body.type)
        assertEquals(1, body.parts.size)
        val part = body.parts.single()
        assertEquals("""form-data; name="file"; filename="cover.jpg"""", part.headers?.get("Content-Disposition"))
        assertEquals("image/jpeg", part.body.contentType().toString())
        val bytes = Buffer().also { part.body.writeTo(it) }.readByteArray()
        assertEquals(listOf<Byte>(1, 2, 3), bytes.toList())
    }

    @Test
    fun errorsAreWordedByKind() {
        assertEquals(EditError.Offline, EditError.of(IOException("timeout")))
        assertEquals(EditError.Forbidden, EditError.of(ApiException(403, "Forbidden resource")))
        assertEquals(EditError.Server("title must be shorter than or equal to 1000 characters"), EditError.of(ApiException(400, "title must be shorter than or equal to 1000 characters")))
        assertEquals(EditError.Server("Metadata fields are locked: title"), EditError.of(ApiException(409, "Metadata fields are locked: title")))
        assertEquals(EditError.Http(400), EditError.of(ApiException(400, "HTTP 400")))
        assertEquals(EditError.Http(502), EditError.of(ApiException(502, "Bad gateway")))
    }

    @Test
    fun theFormStartsFromTheBook() {
        val book = BookDetail(id = 1, title = "Dracula", authors = listOf(AuthorRef(1, "Bram Stoker")), seriesId = 2, seriesName = "Dracula", seriesIndex = "1")
        assertEquals(EditForm("Dracula", listOf("Bram Stoker"), "Dracula", "1"), EditForm.of(book))
        assertEquals(EditForm(), EditForm.of(BookDetail(id = 2)))
    }

    @Test
    fun audiobooksAreToldByThePrimaryFile() {
        val files = listOf(BookFile(1, format = "epub"), BookFile(2, format = "M4B", role = "primary"))
        assertTrue(isAudiobook(BookDetail(id = 1, files = files)))
        assertFalse(isAudiobook(BookDetail(id = 1, files = listOf(BookFile(1, format = "epub"), BookFile(2, format = "mp3")))))
        assertFalse(isAudiobook(BookDetail(id = 1)))
    }
}
