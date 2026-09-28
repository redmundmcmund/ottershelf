package io.github.ottershelf.core.format

import io.github.ottershelf.core.model.BookFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which file a book opens and keeps, and which reader opens it (the web's rules, audio left out). */
class BookFormatsTest {

    private fun file(id: Long, format: String?, role: String? = null) = BookFile(id, format, role)

    @Test
    fun everyReadingFormatHasItsReader() {
        listOf("epub", "kepub", "mobi", "azw3", "azw", "fb2").forEach { assertEquals(it, ReaderKind.Foliate, BookFormats.readerFor(it)) }
        listOf("cbz", "cbr", "cb7").forEach { assertEquals(it, ReaderKind.Comics, BookFormats.readerFor(it)) }
        assertEquals(ReaderKind.Pdf, BookFormats.readerFor("PDF"))
        listOf("m4b", "mp3", "djvu", "txt", null, "").forEach { assertNull(it, BookFormats.readerFor(it)) }
        assertTrue(BookFormats.isPaged("cbr"))
        assertTrue(BookFormats.isPaged("pdf"))
        assertFalse(BookFormats.isPaged("epub"))
    }

    @Test
    fun cbrAndCb7AreNeverKeptOffline() {
        assertTrue(BookFormats.canKeepOffline("cbz"))
        assertTrue(BookFormats.canKeepOffline("pdf"))
        assertTrue(BookFormats.canKeepOffline("mobi"))
        assertFalse(BookFormats.canKeepOffline("cbr"))
        assertFalse(BookFormats.canKeepOffline("cb7"))
        assertFalse(BookFormats.canKeepOffline("m4b"))
    }

    @Test
    fun thePrimaryOpenableFileWins() {
        val files = listOf(file(1, "epub"), file(2, "pdf", "primary"), file(3, "cbz"))
        assertEquals(2L, BookFormats.pickFile(files)?.id)
        // A primary audiobook can't be opened here: the best readable file instead.
        val audio = listOf(file(4, "m4b", "primary"), file(5, "pdf"), file(6, "epub"))
        assertEquals(6L, BookFormats.pickFile(audio)?.id)
    }

    @Test
    fun withoutAPrimaryTheLibrarysPriorityDecides() {
        val files = listOf(file(1, "mobi"), file(2, "pdf"), file(3, "epub"))
        assertEquals(3L, BookFormats.pickFile(files)?.id) // the web's default: epub first
        assertEquals(2L, BookFormats.pickFile(files, listOf("pdf", "epub"))?.id)
        // A format missing from the library's list goes last, files keep their order among equals.
        assertEquals(1L, BookFormats.pickFile(listOf(file(1, "mobi"), file(2, "mobi")), listOf("epub"))?.id)
        assertNull(BookFormats.pickFile(listOf(file(1, "m4b"), file(2, null))))
    }

    @Test
    fun theOfflinePickSkipsCbrAndCb7() {
        val comic = listOf(file(1, "cbr", "primary"), file(2, "cbz"))
        assertEquals(1L, BookFormats.pickFile(comic)?.id)
        assertEquals(2L, BookFormats.pickOfflineFile(comic)?.id)
        assertNull(BookFormats.pickOfflineFile(listOf(file(1, "cb7", "primary"))))
        assertEquals("book.cbz", "book.${BookFormats.extension("CBZ")}")
    }
}
