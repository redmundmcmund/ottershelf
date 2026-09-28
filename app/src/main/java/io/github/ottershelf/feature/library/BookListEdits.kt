package io.github.ottershelf.feature.library

import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookSource
import io.github.ottershelf.core.sync.ReadingChanges
import io.github.ottershelf.core.sync.edited
import io.github.ottershelf.core.sync.isInSeries
import io.github.ottershelf.core.sync.newEdits

/**
 * A book list's cards as edited on this phone (feature.bookedit; ARCHITECTURE.md, "Editing a book's
 * details"). The cards showing when a book is edited take the edit at once ([follow]), and so does
 * a page that was on its way meanwhile ([page]). A page asked for after the edit is the server's
 * word, so a later change by someone else (on the web) shows as it is. An author's or a series' list
 * that the book joined or left loads again.
 */
internal class BookListEdits(private val changes: ReadingChanges, private val source: BookSource) {

    /** A series' own page: its cards show each book's name and number in this series, not its main one's. */
    private val inSeries = (source as? BookSource.InSeries)?.id

    /** A page loaded by [load], with what was edited while it was on its way. */
    suspend fun page(load: suspend () -> Pair<List<BookCard>, Int>): Pair<List<BookCard>, Int> {
        val asked = changes.editMark
        val (cards, total) = load()
        val edits = changes.editedSince(asked)
        return (if (edits.isEmpty()) cards else cards.map { it.edited(edits, inSeries) }) to total
    }

    /** Shows each edit from now on in [pager]'s cards, and loads it again when it gained or lost the book. Runs until cancelled. */
    suspend fun follow(pager: Pager<BookCard>) {
        changes.newEdits().collect { edits ->
            val shown = pager.state.value
            pager.update { it.edited(edits, inSeries) }
            if (moved(edits.values, shown)) pager.refresh()
        }
    }

    /** An author's or a series' list gained or lost one of [books]. */
    private fun moved(books: Collection<BookDetail>, shown: PagedState<BookCard>): Boolean {
        if (source !is BookSource.ByAuthor && source !is BookSource.InSeries) return false
        val listed = shown.items.mapTo(HashSet()) { it.id }
        return books.any { book ->
            val here = belongsHere(book)
            // Joined: only once every page is in (a later page may bring it anyway).
            (book.id in listed && !here) || (book.id !in listed && here && shown.endReached)
        }
    }

    private fun belongsHere(book: BookDetail): Boolean = when (val s = source) {
        is BookSource.ByAuthor -> book.authors.any { it.id == s.id }
        // Its main series or another: the series' page lists every book in it.
        is BookSource.InSeries -> book.isInSeries(s.id)
        else -> true
    }
}
