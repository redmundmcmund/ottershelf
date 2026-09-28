package io.github.ottershelf.ui.nav

import io.github.ottershelf.core.format.BookFormats
import io.github.ottershelf.core.format.ReaderKind
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile

/**
 * The one place a book is turned into a reader screen: which reader opens a file
 * ([BookFormats.readerFor]) and its route. Every entry point goes through here (the book page's Read,
 * the Dashboard's play buttons and widgets, Downloaded, the notes' jump-to), so a new reader only
 * needs its route here. Which file a book opens is [BookFormats.pickFile].
 */
object ReaderRouter {

    /** The reader screen for [fileId] of [bookId] in [format], or null when no reader here opens it. */
    fun route(bookId: Long, fileId: Long, format: String?, title: String): Route? =
        when (BookFormats.readerFor(format)) {
            ReaderKind.Foliate -> Route.Reader(bookId, fileId, title, format = BookFormats.normalize(format))
            ReaderKind.Comics -> Route.Comics(bookId, fileId, title)
            ReaderKind.Pdf -> Route.Pdf(bookId, fileId, title)
            null -> null
        }

    fun route(bookId: Long, file: BookFile, title: String): Route? = route(bookId, file.id, file.format, title)

    /** Read on a book page: the file the web would open ([BookFormats.pickFile]). */
    fun route(book: BookDetail, title: String = book.title.orEmpty()): Route? =
        BookFormats.pickFile(book)?.let { route(book.id, it, title) }

    /**
     * A highlight's place in its file ([fileId] in [format]): a CFI opens the foliate reader there,
     * a page number the PDF reader. Null when neither fits the file (a quote's placeholder CFI is the
     * caller's to filter out, see feature.quotes.QuotePosition).
     */
    fun annotation(bookId: Long, fileId: Long, format: String?, title: String, cfi: String?, page: Int?): Route? =
        when (BookFormats.readerFor(format)) {
            ReaderKind.Foliate -> cfi?.let { Route.Reader(bookId, fileId, title, cfi = it, format = BookFormats.normalize(format)) }
            ReaderKind.Pdf -> page?.let { Route.Pdf(bookId, fileId, title, page = it) }
            else -> null
        }
}
