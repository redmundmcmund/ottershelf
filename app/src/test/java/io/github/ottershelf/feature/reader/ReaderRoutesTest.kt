package io.github.ottershelf.feature.reader

import io.github.ottershelf.feature.reader.annotations.SelectionPopupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which page requests the reader answers, and which TOC entry is current. Plain JVM. */
class ReaderRoutesTest {

    private fun route(path: String, fileId: String? = "9") =
        ReaderRequests.isReaderRoute(path.trim('/').split('/'), fileId, bookId = 5, fileId = 9)

    @Test
    fun answersThisBooksInfoAndFiles() {
        assertTrue(route("/api/v1/epub/5/info"))
        assertTrue(route("/api/v1/epub/5/file/OEBPS/chapter1.xhtml"))
        assertTrue(route("/api/v1/epub/5/file/cover.jpg"))
    }

    @Test
    fun refusesEverythingElse() {
        assertFalse(route("/api/v1/epub/6/info")) // another book
        assertFalse(route("/api/v1/epub/5/info", fileId = "10")) // another file
        assertFalse(route("/api/v1/epub/5/info", fileId = null))
        assertFalse(route("/api/v1/epub/5/file")) // no path
        assertFalse(route("/api/v1/epub/5/other"))
        assertFalse(route("/api/v1/epub/5/info/extra"))
        assertFalse(route("/api/v1/books/5"))
        assertFalse(route("/api/v1/auth/me"))
        assertFalse(route("/api/v1/epub/5/file/../../../auth/me"))
        assertFalse(route("/api/v2/epub/5/info"))
    }

    private fun fileRoute(path: String) = ReaderRequests.isFileRoute(path.trim('/').split('/'), fileId = 9)

    @Test
    fun wholeFileRouteIsThisFilesServeOnly() {
        assertTrue(fileRoute("/api/v1/books/files/9/serve"))
        assertFalse(fileRoute("/api/v1/books/files/10/serve")) // another file
        assertFalse(fileRoute("/api/v1/books/files/9/download"))
        assertFalse(fileRoute("/api/v1/books/files/9/progress"))
        assertFalse(fileRoute("/api/v1/books/files/9/serve/extra"))
        assertFalse(fileRoute("/api/v1/books/files/9"))
        assertFalse(fileRoute("/api/v1/books/files/../files/9/serve"))
        assertFalse(fileRoute("/api/v2/books/files/9/serve"))
    }

    @Test
    fun formatsReadAsOneFile() {
        listOf("kepub", "mobi", "azw3", "azw", "fb2", "MOBI", " Azw3 ").forEach {
            assertTrue(it, ReaderRequests.readsWholeFile(it))
        }
        // EPUB streams through the epub routes (null: an older route, an EPUB); the rest aren't foliate's.
        listOf(null, "", "epub", "EPUB", "pdf", "cbz", "cbr", "m4b", "txt").forEach {
            assertFalse(it.toString(), ReaderRequests.readsWholeFile(it))
        }
    }

    @Test
    fun selectionWithoutPlaceCantBeHighlighted() {
        fun popup(cfi: String?, id: Long? = null) = SelectionPopupState("text", cfi, null, null, annotationId = id)
        assertTrue(popup("epubcfi(/6/4!/4/2,/1:0,/1:4)").canHighlight)
        assertTrue(popup(null, id = 3).canHighlight)
        assertFalse(popup(null).canHighlight)
        assertFalse(popup(" ").canHighlight)
    }

    @Test
    fun currentTocEntry() {
        val toc = listOf(
            TocEntry("One", "text/c1.xhtml"),
            TocEntry("One, part 2", "text/c1.xhtml#p2", depth = 1),
            TocEntry("Two", "text/c2.xhtml"),
        )
        assertEquals(1, currentTocIndex(toc, "text/c1.xhtml#p2"))
        assertEquals(2, currentTocIndex(toc, "text/c2.xhtml"))
        assertEquals(2, currentTocIndex(toc, "text/c2.xhtml#somewhere"))
        assertEquals(-1, currentTocIndex(toc, "text/c3.xhtml"))
        assertEquals(-1, currentTocIndex(toc, null))
    }
}
