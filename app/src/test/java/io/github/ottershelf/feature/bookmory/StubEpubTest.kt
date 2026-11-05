package io.github.ottershelf.feature.bookmory

import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StubEpubTest {

    private val row = BookmoryRow(
        line = 7, title = "Fern & <Feather> \"Tales\" (Wood, #1)", authors = listOf("Juno <Hale>", "Ada O'Vane"),
        publisher = "Moss & Co", publicationDate = "2016-02-28", language = "English", isbn = "9780000000019",
        bookType = "Paperback", status = BookmoryStatus.READ, wishlist = false, logs = emptyList(),
    )

    private fun entries(bytes: ByteArray): List<Pair<ZipEntry, String>> {
        val out = mutableListOf<Pair<ZipEntry, String>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out += e to zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    @Test
    fun theSameRowGivesTheSameBytesAndHash() {
        val a = StubEpub.build(StubBook.of(row))
        val b = StubEpub.build(StubBook.of(row.copy(line = 99)))
        assertArrayEquals(a, b)
        assertEquals(StubEpub.sha256Hex(a), StubEpub.sha256Hex(b))
        assertEquals(64, StubEpub.sha256Hex(a).length)
        // A different book is a different file.
        assertNotEquals(StubEpub.sha256Hex(a), StubEpub.sha256Hex(StubEpub.build(StubBook.of(row.copy(title = "Other")))))
    }

    @Test
    fun aValidEpubLayout() {
        val list = entries(StubEpub.build(StubBook.of(row)))
        assertEquals(
            listOf("mimetype", "META-INF/container.xml", "OEBPS/content.opf", "OEBPS/nav.xhtml", "OEBPS/placeholder.xhtml"),
            list.map { it.first.name },
        )
        val (mimetype, text) = list.first()
        assertEquals(ZipEntry.STORED, mimetype.method)
        assertEquals("application/epub+zip", text)
        assertTrue(list.drop(1).all { it.first.method == ZipEntry.DEFLATED })
        // DOS time 1980-01-01 00:00, read back in the local zone.
        list.forEach { (e, _) -> assertEquals(java.time.LocalDateTime.of(1980, 1, 1, 0, 0), e.timeLocal) }
        // mimetype's bytes start at offset 38 (30 + 8 for the name), as OCF wants.
        val raw = StubEpub.build(StubBook.of(row))
        assertEquals("application/epub+zip", String(raw, 38, 20, Charsets.US_ASCII))
    }

    @Test
    fun theOpfCarriesTheRowEscaped() {
        val stub = StubBook.of(row)
        val opf = entries(StubEpub.build(stub)).single { it.first.name.endsWith(".opf") }.second
        assertTrue(opf, opf.contains("<dc:identifier id=\"bookid\">urn:ottershelf:bookmory:${stub.key}</dc:identifier>"))
        assertTrue(opf.contains("<dc:identifier id=\"isbn\">urn:isbn:9780000000019</dc:identifier>"))
        assertTrue(opf.contains("<dc:title>Fern &amp; &lt;Feather&gt; &quot;Tales&quot; (Wood, #1)</dc:title>"))
        assertTrue(opf.contains("<dc:creator id=\"creator1\">Juno &lt;Hale&gt;</dc:creator>"))
        assertTrue(opf.contains("<dc:creator id=\"creator2\">Ada O&apos;Vane</dc:creator>"))
        assertTrue(opf.contains("<dc:language>en</dc:language>"))
        assertTrue(opf.contains("<dc:publisher>Moss &amp; Co</dc:publisher>"))
        assertTrue(opf.contains("<dc:date>2016-02-28</dc:date>"))
        assertTrue(opf.contains("<meta property=\"dcterms:modified\">2000-01-01T00:00:00Z</meta>"))
        val page = entries(StubEpub.build(stub)).single { it.first.name.endsWith("placeholder.xhtml") }.second
        assertTrue(page.contains("placeholder for a book read elsewhere (Paperback)"))
        // Every document parses as XML.
        entries(StubEpub.build(stub)).filter { it.first.name != "mimetype" }.forEach { (_, xml) ->
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        }
    }

    @Test
    fun noIsbnNoSecondIdentifier() {
        val opf = StubEpub.opf(StubBook.of(row.copy(isbn = null, publisher = null, publicationDate = null)), "k")
        assertFalse(opf.contains("urn:isbn"))
        assertFalse(opf.contains("dc:publisher"))
        assertFalse(opf.contains("dc:date"))
    }

    @Test
    fun theKeyIgnoresHowTheTitleAndAuthorsAreWritten() {
        val key = StubEpub.keyOf("Paper Moons (Tidewater Saga, #2)", listOf("Odile Marsh", "Ivo Brand"))
        assertEquals(32, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
        assertEquals(key, StubEpub.keyOf("paper moons", listOf("ivo brand", "ODILE MARSH")))
        assertNotEquals(key, StubEpub.keyOf("Paper Moons", listOf("Odile Marsh")))
        val stub = StubBook.of(row)
        assertEquals("ottershelf-bookmory-${stub.key}", stub.idempotencyKey)
        assertTrue(Regex("^[A-Za-z0-9._:-]{8,100}$").matches(stub.idempotencyKey))
    }

    @Test
    fun theReplacementCharacterShowsAsAnApostropheButKeysAsExported() {
        val r = row.copy(title = "I Haven�t Slept")
        val stub = StubBook.of(r)
        assertEquals("I Haven't Slept", stub.title)
        assertEquals(StubEpub.keyOf("I Haven�t Slept", r.authors), stub.key)
    }

    @Test
    fun filenamesAreCleanAndShort() {
        assertEquals("Fern & Feather Tales (Wood, #1) - Juno Hale.epub", StubBook.of(row).filename)
        val long = StubEpub.filenameOf("A".repeat(400), listOf("B"))
        assertEquals(150, long.length)
        assertTrue(long.endsWith(".epub"))
        assertEquals("Untitled.epub", StubEpub.filenameOf("???", emptyList()))
        assertEquals("What - Who.epub", StubEpub.filenameOf("What?", listOf("Who")))
    }

    @Test
    fun languages() {
        assertEquals("en", StubEpub.languageOf(null))
        assertEquals("en", StubEpub.languageOf("English"))
        assertEquals("fr", StubEpub.languageOf("FR"))
        assertEquals("pt-br", StubEpub.languageOf("pt-BR"))
        assertEquals("en", StubEpub.languageOf("Klingon dialect"))
    }

    @Test
    fun xmlEscapingDropsWhatXmlCannotHold() {
        assertEquals("a&amp;b&lt;c&gt;&quot;&apos;", StubEpub.xml("a&b<c>\"'"))
        assertEquals("ab", StubEpub.xml("a\u0000\u0007b"))
    }
}
