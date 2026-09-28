package io.github.ottershelf.ui.nav

import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.feature.reader.ReaderRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every book is opened through ReaderRouter: the format picks the screen. */
class ReaderRouterTest {

    @Test
    fun eachFormatOpensItsReader() {
        assertEquals(Route.Reader(1, 2, "T", format = "epub"), ReaderRouter.route(1, 2, "EPUB", "T"))
        assertEquals(Route.Reader(1, 2, "T", format = "mobi"), ReaderRouter.route(1, 2, "mobi", "T"))
        assertEquals(Route.Comics(1, 2, "T"), ReaderRouter.route(1, 2, "cbr", "T"))
        assertEquals(Route.Pdf(1, 2, "T"), ReaderRouter.route(1, 2, "pdf", "T"))
        assertNull(ReaderRouter.route(1, 2, "m4b", "T"))
        assertTrue(Route.Comics(1, 2, "T").isReader)
        assertTrue(Route.Pdf(1, 2, "T").isReader)
        assertFalse(Route.BookDetail(1).isReader)
    }

    @Test
    fun theFormatsReadWholeReachTheFoliateReaderWithTheirFormat() {
        for (format in listOf("kepub", "mobi", "azw3", "azw", "fb2")) {
            val route = ReaderRouter.route(1, 2, format.uppercase(), "T")
            assertEquals(Route.Reader(1, 2, "T", format = format), route)
            assertTrue(ReaderRequests.readsWholeFile((route as Route.Reader).format))
        }
        assertFalse(ReaderRequests.readsWholeFile((ReaderRouter.route(1, 2, "epub", "T") as Route.Reader).format))
        assertEquals(
            Route.Reader(1, 2, "T", cfi = "epubcfi(/6/4!/4)", format = "fb2"),
            ReaderRouter.annotation(1, 2, "FB2", "T", "epubcfi(/6/4!/4)", null),
        )
    }

    @Test
    fun aBookOpensTheFileTheWebWould() {
        val book = BookDetail(
            id = 7,
            title = "Little Nemo",
            files = listOf(BookFile(1, "epub"), BookFile(2, "cbz")),
            formatPriority = listOf("cbz", "epub"),
        )
        assertEquals(Route.Comics(7, 2, "Little Nemo"), ReaderRouter.route(book))
    }

    @Test
    fun aHighlightOpensAtItsPlace() {
        assertEquals(Route.Reader(1, 2, "T", cfi = "epubcfi(/6/4!/4)", format = "epub"), ReaderRouter.annotation(1, 2, "epub", "T", "epubcfi(/6/4!/4)", null))
        assertEquals(Route.Pdf(1, 3, "T", page = 12), ReaderRouter.annotation(1, 3, "pdf", "T", null, 12))
        assertNull(ReaderRouter.annotation(1, 3, "pdf", "T", "epubcfi(/6/4!/4)", null))
        assertNull(ReaderRouter.annotation(1, 2, "epub", "T", null, 12))
        assertNull(ReaderRouter.annotation(1, 4, "cbz", "T", null, 3))
    }
}
