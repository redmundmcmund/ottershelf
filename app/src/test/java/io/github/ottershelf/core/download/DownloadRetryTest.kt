package io.github.ottershelf.core.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.core.network.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream

/**
 * A download is tried again only when that can help: a dropped connection or a server briefly
 * unable to answer, not a whole file that can't be kept (fetched three times over for nothing).
 */
@RunWith(AndroidJUnit4::class)
class DownloadRetryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun downloaded(epub: ByteArray): File = tmp.newFolder().apply {
        File(this, "info.json").writeText("""{"manifest":[]}""")
        File(this, "book.epub").writeBytes(epub)
    }

    @Test
    fun aServerBrieflyUnableToAnswerIsTriedAgain() {
        for (code in listOf(408, 429, 502, 503, 504)) assertTrue("$code", DownloadWorker.isTransient(ApiException(code, "busy")))
        // A 500 is the server's answer for an EPUB it can't parse; the rest are refusals.
        for (code in listOf(400, 401, 403, 404, 500)) assertFalse("$code", DownloadWorker.isTransient(ApiException(code, "no")))
        assertTrue(DownloadWorker.isTransient(SocketTimeoutException()))
        assertTrue(DownloadWorker.isTransient(IOException("The download was cut short")))
        assertFalse(DownloadWorker.isTransient(PermanentDownloadException("Not enough free space")))
        assertFalse(DownloadWorker.isTransient(IllegalStateException("bug")))
    }

    @Test
    fun aWholeFileThePhoneCantOpenFailsAtOnce() {
        val dir = downloaded("<html>Not found</html>".toByteArray())
        val e = assertThrows(PermanentDownloadException::class.java) { Downloads.openDownloadedEpub(dir, whole = true) }
        assertFalse(DownloadWorker.isTransient(e))
    }

    @Test
    fun aFileWhoseEndMayBeMissingIsTriedAgain() {
        // The start of a zip, its directory (at the end) never arrived.
        val dir = downloaded(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(60))
        val e = assertThrows(ZipException::class.java) { Downloads.openDownloadedEpub(dir, whole = false) }
        assertTrue(DownloadWorker.isTransient(e))
    }

    /** A CBZ or KEPUB the same way: a zip that won't open fails for good only once it is known to be whole. */
    @Test
    fun aComicOrKepubWhoseEndMayBeMissingIsTriedAgain() {
        val cut = tmp.newFile().apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(60)) }
        for (format in listOf("cbz", "kepub")) {
            val e = assertThrows(ZipException::class.java) { OfflineFiles.problem(format, cut, whole = false) }
            assertTrue(DownloadWorker.isTransient(e))
            assertEquals("Not a ${format.uppercase()} file", OfflineFiles.problem(format, cut, whole = true))
        }
    }

    @Test
    fun anEpubOpens() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("META-INF/container.xml")); it.write("<container/>".toByteArray()) }
        }.toByteArray()
        Downloads.openDownloadedEpub(downloaded(zip), whole = true).use { epub ->
            assertNotNull(epub.open("META-INF/container.xml")?.also { it.stream.close() })
        }
    }
}
