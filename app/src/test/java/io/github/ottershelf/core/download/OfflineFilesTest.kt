package io.github.ottershelf.core.download

import io.github.ottershelf.testing.declareSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The kept file's name and the cheap checks made before a download of any format is kept. */
class OfflineFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(vararg names: String): File = tmp.newFile().also { f ->
        ZipOutputStream(f.outputStream()).use { out ->
            names.forEach { out.putNextEntry(ZipEntry(it)); out.write(byteArrayOf(1, 2, 3)); out.closeEntry() }
        }
    }

    @Test
    fun theFileIsNamedAfterItsFormatAndOldDownloadsAreEpubs() {
        assertEquals("book.epub", OfflineFiles.name("epub"))
        assertEquals("book.cbz", OfflineFiles.name("CBZ"))
        assertEquals("book.epub", OfflineFiles.name(null))
        // A meta.json written before formats were kept has no format: it is the EPUB layout.
        val old = io.github.ottershelf.core.network.ApiJson.decodeFromString(DownloadedBook.serializer(), """{"bookId":1,"fileId":2}""")
        assertEquals("epub", old.format)
    }

    @Test
    fun aPdfNeedsItsHeader() {
        val pdf = tmp.newFile().apply { writeBytes("\n%PDF-1.7\n...".toByteArray()) }
        assertNull(OfflineFiles.problem("pdf", pdf))
        val html = tmp.newFile().apply { writeText("<html>login</html>") }
        assertNotNull(OfflineFiles.problem("pdf", html))
        assertNotNull(OfflineFiles.problem("pdf", tmp.newFile())) // empty
    }

    @Test
    fun aCbzNeedsPages() {
        assertNull(OfflineFiles.problem("cbz", zip("001.jpg", "002.PNG", "ComicInfo.xml")))
        assertNotNull(OfflineFiles.problem("cbz", zip("ComicInfo.xml")))
        assertNotNull(OfflineFiles.problem("cbz", tmp.newFile().apply { writeText("not a zip") }))
        assertNull(OfflineFiles.problem("kepub", zip("META-INF/container.xml")))
    }

    /** The comics reader pages what the server does: a download it couldn't open offline isn't kept. */
    @Test
    fun aCbzNeedsPagesTheReaderShows() {
        assertNull(OfflineFiles.problem("cbz", zip("Issue 1/001.jpg")))
        assertNotNull(OfflineFiles.problem("cbz", zip("001.jxl"))) // not a server page extension
        assertNotNull(OfflineFiles.problem("cbz", zip("__MACOSX/._001.jpg", ".thumbs/002.jpg")))
        assertFalse(OfflineFiles.isCbzPage("001.jpg", directory = false, method = 14, compressedSize = 10)) // LZMA
        assertFalse(OfflineFiles.isCbzPage("001.jpg", directory = false, method = ZipEntry.DEFLATED, compressedSize = 0))
        assertTrue(OfflineFiles.isCbzPage("001.JPG", directory = false, method = ZipEntry.STORED, compressedSize = 10))
    }

    /** A zip bomb: pages claiming to inflate to far more than a page or the file could hold. */
    @Test
    fun aCbzWhosePagesInflateTooFarIsRefused() {
        val huge = zip("001.jpg", "002.jpg").also { declareSize(it, "002.jpg", OfflineFiles.MAX_CBZ_PAGE_BYTES + 1) }
        assertEquals("A page in this CBZ file is too large", OfflineFiles.problem("cbz", huge))
        // Each page under the cap, but together hundreds of thousands of times the file.
        val bomb = zip("001.jpg", "002.jpg").also { declareSize(it, "002.jpg", 50L shl 20) }
        assertEquals("The pages of this CBZ file are too large", OfflineFiles.problem("cbz", bomb))
    }

    @Test
    fun otherFormatsOnlyNeedToBeThere() {
        assertNull(OfflineFiles.problem("mobi", tmp.newFile().apply { writeBytes(ByteArray(100)) }))
        assertNotNull(OfflineFiles.problem("fb2", tmp.newFile()))
    }
}
