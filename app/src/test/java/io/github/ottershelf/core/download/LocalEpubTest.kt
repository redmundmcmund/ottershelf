package io.github.ottershelf.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Plain JVM: LocalEpub has no Android dependencies. */
class LocalEpubTest {

    @get:Rule val folder = TemporaryFolder()

    @Test
    fun normalizesLikeTheServer() {
        assertEquals("OEBPS/text/ch1.xhtml", LocalEpub.normalize("OEBPS\\text\\ch1.xhtml"))
        assertEquals("OEBPS/images/a b.png", LocalEpub.normalize("OEBPS/text/../images/a%20b.png"))
        assertEquals("a/b", LocalEpub.normalize("/a/./b/"))
    }

    @Test
    fun decodeUriKeepsReservedEscapesAndRejectsBadOnes() {
        assertEquals("a b/é", LocalEpub.decodeUri("a%20b/%C3%A9"))
        assertEquals("a%2Fb", LocalEpub.decodeUri("a%2Fb"))
        assertEquals("100%", LocalEpub.decodeUri("100%")) // not validly escaped: unchanged
    }

    @Test
    fun percentDecodeDecodesEverythingLikeUriDecode() {
        assertEquals("a/b c+é", LocalEpub.percentDecode("a%2Fb%20c+%C3%A9"))
        assertEquals("50% off", LocalEpub.percentDecode("50% off"))
        assertEquals("plain", LocalEpub.percentDecode("plain"))
    }

    @Test
    fun servesEntriesExactlyThenCaseInsensitively() {
        val dir = book(
            entries = mapOf("META-INF/container.xml" to "<container/>", "OEBPS/Text/Ch1.xhtml" to "<html/>"),
            info = """{"manifest":[{"href":"OEBPS/Text/Ch1.xhtml","mediaType":"application/xhtml+xml","size":7}]}""",
        )
        LocalEpub(dir).use { epub ->
            val exact = required(epub.open("OEBPS/Text/Ch1.xhtml"))
            assertEquals("application/xhtml+xml", exact.mimeType)
            assertEquals("<html/>", exact.stream.use { it.readBytes().decodeToString() })
            required(epub.open("oebps/text/ch1.xhtml"))
            required(epub.open("OEBPS%2FText%2FCh1.xhtml")) // escapes foliate left in
            assertEquals("application/xml", epub.open("META-INF/container.xml")!!.mimeType)
            assertNull(epub.open("OEBPS/missing.xhtml"))
            assertTrue(epub.matchesInfo())
        }
    }

    @Test
    fun infoForADifferentFileDoesNotMatch() {
        val dir = book(
            entries = mapOf("OEBPS/ch1.xhtml" to "<html/>"),
            info = """{"manifest":[{"href":"OEBPS/ch1.xhtml","size":99}]}""",
        )
        LocalEpub(dir).use { assertFalse(it.matchesInfo()) }
    }

    private fun book(entries: Map<String, String>, info: String): File {
        val dir = folder.newFolder()
        ZipOutputStream(File(dir, "book.epub").outputStream()).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        File(dir, "info.json").writeText(info)
        return dir
    }

    private fun <T> required(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
