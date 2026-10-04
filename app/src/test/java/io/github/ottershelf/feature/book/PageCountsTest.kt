package io.github.ottershelf.feature.book

import io.github.ottershelf.core.model.AuthorRef
import io.github.ottershelf.core.model.BookDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageCountsTest {
    private val dune = BookDetail(id = 1, title = "Dune", authors = listOf(AuthorRef(id = 1, name = "Frank Herbert")), isbn13 = "978-0-441-17271-9")

    @Test
    fun theCandidateWithTheBooksIsbnWins() {
        val candidates = listOf(
            PageCandidate(title = "Dune", authors = listOf("Frank Herbert"), pageCount = 896, isbn13 = "9780593099322"),
            PageCandidate(title = "Dune", authors = listOf("Frank Herbert"), pageCount = 604, isbn13 = "9780441172719"),
        )
        assertEquals(604, PageCounts.pick(candidates, dune))
    }

    @Test
    fun withoutAnIsbnMatchTheMedianOfTheSameTitleAndAuthor() {
        val candidates = listOf(
            PageCandidate(title = "Dune", authors = listOf("Frank Herbert"), pageCount = 896),
            PageCandidate(title = "Dune: Deluxe Edition", authors = listOf("Herbert, Frank"), pageCount = 412),
            PageCandidate(title = "DUNE", authors = listOf("F. Herbert"), pageCount = 604),
            PageCandidate(title = "Dune Messiah", authors = listOf("Frank Herbert"), pageCount = 256),
            PageCandidate(title = "Dune", authors = listOf("Someone Else"), pageCount = 120),
        )
        assertEquals(604, PageCounts.pick(candidates, dune.copy(isbn13 = null)))
    }

    @Test
    fun nothingFitsNothingIsGuessed() {
        val candidates = listOf(
            PageCandidate(title = "Dune Messiah", authors = listOf("Frank Herbert"), pageCount = 256),
            PageCandidate(title = "Dune", authors = listOf("Frank Herbert"), pageCount = 3),
            PageCandidate(title = "Dune", authors = listOf("Frank Herbert"), pageCount = null),
        )
        assertNull(PageCounts.pick(candidates, dune.copy(isbn13 = null)))
        assertNull(PageCounts.pick(emptyList(), dune))
    }
}
