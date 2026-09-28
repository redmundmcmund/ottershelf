package io.github.ottershelf.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.CurrentlyReadingBook

// How a loaded card shows a book edited on this device (ReadingChanges.editedBooks). The edit carries
// the book as the server had it after the change, `updatedAt` included, which also versions the
// thumbnail URL (Api.thumbnailUrl), so an edited card asks for the new cover.
//
// Only cards loaded before the edit take it: a screen applies each edit to what it shows when the
// edit is made ([newEdits]), and to an answer that was on its way meanwhile (what was
// ReadingChanges.editedSince the editMark taken before asking). What it asks for afterwards is the
// server's word and shows as the server has it, even when someone changed the book again since.

/** One edit made here: [book] as the server had it right after; [seq] orders the edits ([ReadingChanges.editMark]). */
data class EditedBook(val book: BookDetail, val seq: Long)

/** This card as edited on this device, if [edits] has it; else itself. [inSeries]: see [withDetails]. */
fun BookCard.edited(edits: Map<Long, BookDetail>, inSeries: Long? = null): BookCard {
    if (edits.isEmpty()) return this
    val book = edits[id] ?: return this
    return withDetails(book, inSeries)
}

/**
 * The card, loaded before [book]'s edit, with [book]'s title, authors, series and cover. A card
 * loaded after the edit isn't given it ([ReadingChanges.editedSince]).
 *
 * [inSeries]: the card is on that series' own page, where the server gives each book its name and
 * number in that series rather than its main series' (`series/:id/books`). The card keeps showing
 * that series, with the number [book] has there now; if the book is no longer in it, the card's
 * series stay as they were (the page loads again without it).
 */
fun BookCard.withDetails(book: BookDetail, inSeries: Long? = null): BookCard {
    if (book.id != id) return this
    val (name, index) = if (inSeries == null) book.seriesName to book.seriesIndex else book.placeIn(inSeries) ?: (seriesName to seriesIndex)
    return copy(
        title = book.title,
        authors = book.authors.map { it.name },
        seriesName = name,
        seriesIndex = index,
        hasCover = book.coverSource != null,
        updatedAt = book.updatedAt ?: updatedAt,
    )
}

/** Whether [book][this] is in series [seriesId], as its main series or another. */
fun BookDetail.isInSeries(seriesId: Long): Boolean = placeIn(seriesId) != null

/** The book's series name and number in series [seriesId], or null when it isn't in it. */
private fun BookDetail.placeIn(seriesId: Long): Pair<String?, String?>? {
    seriesMemberships.firstOrNull { it.seriesId == seriesId }?.let { return it.seriesName to it.seriesIndex }
    // Not among them, or none were sent: the main series is all that's known.
    return if (this.seriesId == seriesId) seriesName to seriesIndex else null
}

/** A Currently Reading row with [book]'s title, authors and cover (the widget carries no dates). */
fun CurrentlyReadingBook.withDetails(book: BookDetail): CurrentlyReadingBook {
    if (book.id != bookId) return this
    return copy(title = book.title, authors = book.authors.map { it.name }, hasCover = book.coverSource != null)
}

/**
 * The books edited here from the moment this is collected, by id, each time one is edited (several
 * together when edits come faster than they're collected): what a screen applies to the cards it
 * shows, which were all loaded before these edits.
 */
fun ReadingChanges.newEdits(): Flow<Map<Long, BookDetail>> = flow {
    var mark = editMark
    editedBooks.collect { edits ->
        val fresh = edits.values.filter { it.seq > mark }
        if (fresh.isEmpty()) return@collect
        mark = maxOf(mark, fresh.maxOf { it.seq })
        emit(fresh.associate { it.book.id to it.book })
    }
}
