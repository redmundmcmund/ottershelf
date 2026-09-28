package io.github.ottershelf.feature.scan

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.ottershelf.core.model.MetadataCandidate
import io.github.ottershelf.core.network.ApiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The books query a scan sends (the server rejects unknown fields), and how its answers are read. */
class ScanQueryTest {

    private val plain = Isbn.of13("9780306406157")!!
    private val withX = Isbn.of13("9780804429573")!!
    private val nine79 = Isbn.of13("9791090636071")!!

    private fun json(body: ScanQueryBody): JsonObject =
        ApiJson.encodeToJsonElement(ScanQueryBody.serializer(), body).jsonObject

    @Test
    fun theIsbnQueryIsExactlyTheServersShape() {
        val expected = """
            {"sort":[{"field":"title","dir":"asc"}],"pagination":{"page":0,"size":20},
             "filter":{"type":"group","join":"OR","rules":[
               {"type":"rule","field":"isbn","operator":"eq","value":"9780306406157"},
               {"type":"rule","field":"isbn","operator":"eq","value":"0306406152"}]}}
        """.trimIndent().replace(Regex("\\s"), "")
        assertEquals(expected, ApiJson.encodeToString(ScanQueryBody.serializer(), isbnQuery(plain)))
    }

    @Test
    fun onlyFieldsTheBookQuerySchemaKnows() {
        // server/src/modules/book/pipes/book-query.pipe.ts and utils/group-rule.validator.ts
        val topLevel = setOf("collapseSeries", "filter", "q", "randomSeed", "sort", "pagination")
        val ruleKeys = setOf("type", "field", "operator", "value", "valueTo", "provider")
        for (body in listOf(isbnQuery(withX), titleQuery("Dracula"))) {
            val sent = json(body)
            assertTrue(sent.keys.toString(), topLevel.containsAll(sent.keys))
            sent["filter"]?.jsonObject?.let { group ->
                assertEquals(setOf("type", "join", "rules"), group.keys)
                group["rules"]!!.jsonArray.forEach { rule ->
                    assertTrue(ruleKeys.containsAll(rule.jsonObject.keys))
                    // FIELD_OPERATORS.isbn: isEmpty, isNotEmpty, eq; the value a string.
                    assertEquals("isbn", rule.jsonObject["field"]!!.jsonPrimitive.content)
                    assertEquals("eq", rule.jsonObject["operator"]!!.jsonPrimitive.content)
                    assertTrue(rule.jsonObject["value"]!!.jsonPrimitive.isString)
                }
            }
        }
    }

    @Test
    fun everyStoredSpellingIsAsked() {
        assertEquals(listOf("9780306406157", "0306406152"), storedForms(plain))
        // The OPF parser keeps an ISBN-10's x as written.
        assertEquals(listOf("9780804429573", "080442957X", "080442957x"), storedForms(withX))
        // A 979 code has only its 13 digits.
        assertEquals(listOf("9791090636071"), storedForms(nine79))
        val rules = json(isbnQuery(nine79))["filter"]!!.jsonObject["rules"] as JsonArray
        assertEquals(1, rules.size)
    }

    @Test
    fun theTitleSearchUsesTheMainTitleByRelevance() {
        val sent = json(titleQuery("Middlemarch: A Study of Provincial Life"))
        assertEquals("Middlemarch", sent["q"]!!.jsonPrimitive.content)
        assertNull(sent["filter"])
        assertEquals("relevance", sent["sort"]!!.jsonArray[0].jsonObject["field"]!!.jsonPrimitive.content)
        assertEquals("desc", sent["sort"]!!.jsonArray[0].jsonObject["dir"]!!.jsonPrimitive.content)
        // The server takes at most 200 characters of q.
        assertEquals(200, titleQuery("x".repeat(300)).q!!.length)
        assertEquals(":Odd", mainTitle(":Odd"))
    }

    @Test
    fun cardsDecodeFromTheServersBookCard() {
        val page = ApiJson.decodeFromString(
            ScanBooksPage.serializer(),
            """{"items":[{"id":7,"status":"present","title":"Dracula","authors":["Bram Stoker"],"seriesName":"Dracula","seriesIndex":1,
               "files":[{"id":1,"format":"PDF","role":"alternative"},{"id":2,"format":"EPUB","role":"primary"},{"id":3,"format":"epub","role":"alternative"}],
               "publishedYear":1897,"isbn13":"9780441013593","hasCover":true,"addedAt":"2025-01-01T00:00:00.000Z","updatedAt":null,
               "readStatus":{"status":"reading"},"genres":[],"narrators":[],"tags":[],"customMetadata":[]}],"total":1,"page":0,"size":20}""",
        )
        val card = page.items.single()
        assertEquals(listOf("epub", "pdf"), card.formats) // primary first, each once
        assertEquals(1897, card.publishedYear)
        assertEquals("1", card.seriesIndex)
        assertEquals("reading", card.toBookCard().readStatus?.status)
    }

    // --- providers -------------------------------------------------------------------------------

    private fun candidate(title: String?, isbn13: String? = null, isbn10: String? = null, cover: String? = null, year: Int? = null, authors: List<String>? = null) =
        MetadataCandidate(provider = "p", title = title, isbn13 = isbn13, isbn10 = isbn10, coverUrl = cover, publishedYear = year, authors = authors)

    @Test
    fun theBestCandidateCarriesThisIsbnThenIsMostComplete() {
        val otherEdition = candidate("Dracula", isbn13 = "9780441172719", cover = "c", year = 1897, authors = listOf("Bram Stoker"))
        val thisOne = candidate("Dracula", isbn13 = "978-0-306-40615-7")
        assertEquals(thisOne, bestCandidate(listOf(otherEdition, thisOne), plain))
        // By its ISBN-10, too.
        val byTen = candidate("Dracula", isbn10 = "0306406152")
        assertEquals(byTen, bestCandidate(listOf(otherEdition, byTen), plain))
        // Neither carries it: the more complete, then the first.
        val bare = candidate("Dracula")
        assertEquals(otherEdition, bestCandidate(listOf(bare, otherEdition), plain))
        val bare2 = candidate("Dracula's Guest")
        assertEquals(bare, bestCandidate(listOf(bare, bare2), plain))
        // Untitled ones don't count.
        assertNull(bestCandidate(listOf(candidate(null, isbn13 = "9780306406157"), candidate("  ")), plain))
    }

    @Test
    fun otherEditionsNeedTheTitleAndAnAuthor() {
        fun card(title: String?, vararg authors: String) = ScanCard(id = 1, title = title, authors = authors.toList())
        val stoker = listOf("Bram Stoker")
        assertTrue(sameWork("Dracula", stoker, card("Dracula", "Bram Stoker")))
        assertTrue(sameWork("DRACULA", stoker, card("Dracula", "Stoker, Bram")))
        assertTrue(sameWork("Dracula: Deluxe Edition", stoker, card("Dracula", "B. Stoker")))
        assertTrue(sameWork("Dracula", stoker, card("Dracula: The Graphic Novel", "Bram Stoker")))
        assertTrue(sameWork("Les Misérables", listOf("Victor Hugo"), card("Les Miserables", "Victor Hugo")))
        assertTrue(sameWork("Dracula", emptyList(), card("Dracula", "Anyone")))
        assertTrue(sameWork("Dracula", stoker, card("Dracula")))
        assertTrue(sameWork("Treasure Island", listOf("R. L. Stevenson"), card("Treasure Island", "R.L. Stevenson")))
        assertFalse(sameWork("Dracula", stoker, card("Dracula", "Someone Else")))
        assertFalse(sameWork("Dracula", stoker, card("Dracula's Guest", "Bram Stoker")))
        assertFalse(sameWork("Dracula", stoker, card(null, "Bram Stoker")))
    }
}
