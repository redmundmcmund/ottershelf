package io.github.ottershelf.feature.bookmory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmoryMatchTest {

    private fun row(title: String, authors: List<String> = listOf("Mara Quill"), isbn: String? = null) = BookmoryRow(
        line = 2, title = title, authors = authors, publisher = null, publicationDate = null, language = null,
        isbn = isbn, bookType = null, status = BookmoryStatus.READ, wishlist = false, logs = emptyList(),
    )

    private fun book(id: Long, title: String, authors: List<String> = listOf("Mara Quill"), subtitle: String? = null, isbn13: String? = null) =
        ServerBook(id, title, subtitle, authors, isbn13)

    private fun found(result: MatchResult): Long = (result as MatchResult.Found).book.id

    @Test
    fun normalisationFoldsAccentsCaseAndPunctuation() {
        assertEquals("cafe society and the moon", BookmoryText.normalize("Café Society & the Moon!"))
        assertEquals("i havent been", BookmoryText.normalize("I Haven’t been"))
        assertEquals("i haven\u0001t been", BookmoryText.normalize("I Haven�t been"))
        assertEquals("lights out", BookmoryText.titleKey("Lights Out (Into Darkness, #1)"))
        assertEquals("lights out", BookmoryText.titleKey("Lights Out (Into Darkness, #1.5)"))
        assertEquals("never enough the powerful", BookmoryText.titleKey("Never Enough: The Powerful..."))
        assertEquals("never enough", BookmoryText.shortTitleKey("Never Enough: The Powerful..."))
        assertEquals("quill", BookmoryText.surname("Mara Quill"))
        assertEquals("brand", BookmoryText.surname("Ivo Brand Jr."))
    }

    @Test
    fun theIsbnWinsOverTitles() {
        val matcher = BookmoryMatcher(listOf(book(1, "Other Title", listOf("Someone Else"), isbn13 = "9780000000019"), book(2, "Quiet Harbours")))
        val result = matcher.match(row("Quiet Harbours", isbn = "9780000000019"))
        assertEquals(1L, found(result))
        assertEquals(MatchedBy.ISBN, (result as MatchResult.Found).by)
    }

    @Test
    fun anIsbn10MatchesItsIsbn13() {
        val matcher = BookmoryMatcher(listOf(book(1, "Orchard", isbn13 = "978-0-00-000004-0")))
        assertEquals(1L, found(matcher.match(row("Something else", isbn = "0000000043"))))
    }

    @Test
    fun anUnknownIsbnFallsBackToTheTitle() {
        val matcher = BookmoryMatcher(listOf(book(1, "Quiet Harbours", isbn13 = "9780000000026")))
        assertEquals(1L, found(matcher.match(row("Quiet Harbours", isbn = "9780000000019"))))
    }

    @Test
    fun titleNeedsAnAuthorSurnameInCommon() {
        val matcher = BookmoryMatcher(listOf(book(1, "Quiet Harbours", listOf("Q. Somebody"))))
        assertEquals(MatchResult.NotFound, matcher.match(row("Quiet Harbours")))
        val withAuthor = BookmoryMatcher(listOf(book(1, "Quiet Harbours", listOf("M. Quill", "Ivo Brand"))))
        assertEquals(1L, found(withAuthor.match(row("quiet harbours!", listOf("Mara Quill")))))
    }

    @Test
    fun theSeriesSuffixIsIgnoredOnEitherSide() {
        val matcher = BookmoryMatcher(listOf(book(1, "Paper Moons")))
        assertEquals(1L, found(matcher.match(row("Paper Moons (Tidewater Saga, #2)"))))
        val other = BookmoryMatcher(listOf(book(1, "Paper Moons (Tidewater Saga, #2)")))
        assertEquals(1L, found(other.match(row("Paper Moons"))))
    }

    @Test
    fun theReplacementCharacterIsAWildcard() {
        val matcher = BookmoryMatcher(listOf(book(1, "I Haven’t Slept in Weeks"), book(2, "I Have Slept in Weeks")))
        assertEquals(1L, found(matcher.match(row("I Haven�t Slept in Weeks"))))
        val accented = BookmoryMatcher(listOf(book(1, "Café Nights")))
        assertEquals(1L, found(accented.match(row("Caf� Nights"))))
    }

    @Test
    fun aTruncatedTitleMatchesByPrefixIncludingTheSubtitle() {
        val matcher = BookmoryMatcher(
            listOf(book(1, "Never Quite Finished", subtitle = "A Long and Winding Subtitle About Many Things That Go On and On")),
        )
        assertEquals(1L, found(matcher.match(row("Never Quite Finished: a long and winding subtitle about many things that go on and..."))))
    }

    @Test
    fun aTruncatedTitleMatchesTheTitleWithoutItsSubtitle() {
        val matcher = BookmoryMatcher(listOf(book(1, "Never Quite Finished")))
        val result = matcher.match(row("Never Quite Finished: a long and winding subtitle about many things that go on and..."))
        assertEquals(1L, found(result))
        assertEquals(MatchedBy.SHORT_TITLE, (result as MatchResult.Found).by)
    }

    @Test
    fun aSubtitleOnTheServerOnly() {
        val matcher = BookmoryMatcher(listOf(book(1, "Saltwater Atlas: Maps of the Coast")))
        assertEquals(1L, found(matcher.match(row("Saltwater Atlas"))))
    }

    @Test
    fun aShortTruncatedTitleDoesNotMatchEverything() {
        val matcher = BookmoryMatcher(listOf(book(1, "The Long Way Round")))
        assertEquals(MatchResult.NotFound, matcher.match(row("The...")))
    }

    @Test
    fun twoCandidatesAreAmbiguous() {
        val matcher = BookmoryMatcher(listOf(book(1, "Paper Moons"), book(2, "Paper Moons"), book(3, "Paper Moons", listOf("Other Person"))))
        val result = matcher.match(row("Paper Moons"))
        assertTrue(result is MatchResult.Ambiguous)
        assertEquals(listOf(1L, 2L), (result as MatchResult.Ambiguous).candidates.map { it.id })
        val byIsbn = BookmoryMatcher(listOf(book(1, "A", isbn13 = "9780000000019"), book(2, "B", isbn13 = "9780000000019")))
        assertTrue(byIsbn.match(row("C", isbn = "9780000000019")) is MatchResult.Ambiguous)
    }

    @Test
    fun noCandidateIsNotFound() {
        assertEquals(MatchResult.NotFound, BookmoryMatcher(emptyList()).match(row("Anything")))
        assertFalse(BookmoryText.titleMatches("", "x", prefix = false))
    }

    @Test
    fun aRowWithoutAuthorsNeedsTheExactTitle() {
        val matcher = BookmoryMatcher(listOf(book(1, "Fern & Feather")))
        assertEquals(1L, found(matcher.match(row("Fern and Feather", authors = emptyList()))))
    }

    @Test
    fun aNoteInBracketsOnTheLibrarySide() {
        val author = listOf("Wren Halloway")
        for (library in listOf(
            "Wed to the Starfarer Rancher (Rancher Colony Mail-Order Brides Book 1)",
            "Wed to the Starfarer Rancher (Rancher Colony, Book 1)",
            "Wed to the Starfarer Rancher (Rancher Colony #1)",
            "Wed to the Starfarer Rancher (A Rancher Colony Novel)",
            "Wed to the Starfarer Rancher [Rancher Colony 1]",
            "Wed to the Starfarer Rancher (Rancher Colony) (Large Print)",
            "Wed to the Starfarer Rancher (Rancher Colony Book 1",
        )) {
            val matcher = BookmoryMatcher(listOf(book(1, library, author), book(2, "Wed to the Starfarer", author)))
            assertEquals(library, 1L, found(matcher.match(row("Wed to the Starfarer Rancher", author))))
        }
    }

    @Test
    fun aNoteInBracketsOnTheRowSide() {
        val author = listOf("Wren Halloway")
        val matcher = BookmoryMatcher(listOf(book(1, "Wed to the Starfarer Rancher", author)))
        assertEquals(1L, found(matcher.match(row("Wed to the Starfarer Rancher (Rancher Colony Mail-Order Brides Book 1)", author))))
        assertEquals(1L, found(matcher.match(row("Wed to the Starfarer Rancher (A Rancher Colony Novel)", author))))
        // Different notes on both sides still name the same book.
        val both = BookmoryMatcher(listOf(book(1, "Wed to the Starfarer Rancher (Rancher Colony Book 1)", author)))
        assertEquals(1L, found(both.match(row("Wed to the Starfarer Rancher (Rancher Colony, #1)", author))))
    }

    @Test
    fun aNoteNeverMatchesWithoutAnAuthorInCommon() {
        val matcher = BookmoryMatcher(listOf(book(1, "Wed to the Starfarer Rancher (Rancher Colony Book 1)", listOf("Wren Halloway"))))
        assertEquals(MatchResult.NotFound, matcher.match(row("Wed to the Starfarer Rancher", listOf("Pell Okafor"))))
        assertEquals(MatchResult.NotFound, matcher.match(row("Wed to the Starfarer Rancher", emptyList())))
    }

    @Test
    fun theExactTitleWinsOverOneWithANote() {
        val author = listOf("Wren Halloway")
        val matcher = BookmoryMatcher(listOf(book(1, "Wed to the Starfarer Rancher", author), book(2, "Wed to the Starfarer Rancher (Rancher Colony Book 2)", author)))
        assertEquals(1L, found(matcher.match(row("Wed to the Starfarer Rancher", author))))
        assertEquals("wed to the starfarer rancher", BookmoryText.notelessKey("Wed to the Starfarer Rancher (Rancher Colony Book 1) [Large Print]"))
    }
}
