package io.github.ottershelf.feature.bookmory

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * A placeholder book for a Bookmory row that isn't in the user's library: BookOrbit has no books
 * without a file, so each one is a tiny EPUB 3 holding the row's details and a page saying it
 * stands for a book read elsewhere.
 *
 * The same row always gives the same bytes (and so the same size and SHA-256). That is what makes the
 * upload safe to repeat: the server hands back the finished upload for a repeated idempotency key
 * only when the filename, size and target are the same. So nothing in here depends on the time, the
 * locale, randomness or the platform's zlib: entries are in a fixed order with the DOS time
 * 1980-01-01 00:00, `mimetype` first and STORED, and every other entry DEFLATED as stored deflate
 * blocks written here (a real compressor's output differs between zlib builds, which would change
 * the size from one phone to another). The page text is fixed English for the same reason.
 */
data class StubBook(
    /** 32 hex characters: sha256(normalised title + "|" + normalised authors, sorted), from the row as exported. */
    val key: String,
    val title: String,
    val authors: List<String>,
    val isbn13: String?,
    val language: String,
    val publisher: String?,
    val date: String?,
    val bookType: String?,
) {
    val idempotencyKey: String get() = "$IDEMPOTENCY_PREFIX$key"

    val filename: String get() = StubEpub.filenameOf(title, authors)

    companion object {
        const val IDEMPOTENCY_PREFIX = "ottershelf-bookmory-"

        /**
         * The stub for [row]: U+FFFD shown as an apostrophe and the "..." kept (it is what the user
         * saw); the key comes from the row as exported, so it never depends on how the stub shows it.
         */
        fun of(row: BookmoryRow) = StubBook(
            key = StubEpub.keyOf(row.title, row.authors),
            title = row.title.replace('�', '\''),
            authors = row.authors.map { it.replace('�', '\'') },
            isbn13 = row.isbn?.takeIf { it.length == 13 },
            language = StubEpub.languageOf(row.language),
            publisher = row.publisher,
            date = row.publicationDate,
            bookType = row.bookType,
        )
    }
}

object StubEpub {

    const val CONTENT_TYPE = "application/epub+zip"
    const val MODIFIED = "2000-01-01T00:00:00Z"
    private const val MAX_FILENAME = 150

    fun keyOf(title: String, authors: List<String>): String {
        val t = BookmoryText.titleKey(title).replace(BookmoryText.WILDCARD.toString(), "")
        val a = authors.map { BookmoryText.normalize(it).replace(BookmoryText.WILDCARD.toString(), "") }
            .filter { it.isNotEmpty() }.sorted().joinToString(",")
        return sha256Hex("$t|$a".toByteArray(Charsets.UTF_8)).take(32)
    }

    /**
     * "<Title> - <First author>.epub", without the characters the server's own clean-up replaces
     * (`/\:*?"<>|` and control characters), spaces collapsed, at most [MAX_FILENAME] characters.
     */
    fun filenameOf(title: String, authors: List<String>): String {
        fun clean(s: String) = s.map { c -> if (c.code <= 31 || c.code == 127 || c in "/\\:*?\"<>|") ' ' else c }
            .joinToString("").replace(Regex("""\s+"""), " ").trim().trimEnd('.')
        val t = clean(title).ifEmpty { "Untitled" }
        val a = authors.firstOrNull()?.let(::clean)?.takeIf { it.isNotEmpty() }
        val stem = (if (a != null) "$t - $a" else t).take(MAX_FILENAME - ".epub".length).trim().trimEnd('.')
        return "$stem.epub"
    }

    /** A BCP 47-looking code as given (lower case), "English" as `en`, anything else `en`. */
    fun languageOf(text: String?): String {
        val t = text?.trim().orEmpty()
        if (Regex("""[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*""").matches(t)) return t.lowercase()
        return LANGUAGE_NAMES[t.lowercase()] ?: "en"
    }

    private val LANGUAGE_NAMES = mapOf(
        "english" to "en", "french" to "fr", "german" to "de", "spanish" to "es", "italian" to "it",
        "portuguese" to "pt", "dutch" to "nl", "irish" to "ga", "japanese" to "ja", "korean" to "ko", "chinese" to "zh",
    )

    fun build(stub: StubBook): ByteArray {
        val zip = DeterministicZip()
        zip.stored("mimetype", CONTENT_TYPE.toByteArray(Charsets.US_ASCII))
        zip.deflated("META-INF/container.xml", CONTAINER.toByteArray(Charsets.UTF_8))
        zip.deflated("OEBPS/content.opf", opf(stub, stub.key).toByteArray(Charsets.UTF_8))
        zip.deflated("OEBPS/nav.xhtml", nav(stub).toByteArray(Charsets.UTF_8))
        zip.deflated("OEBPS/placeholder.xhtml", page(stub).toByteArray(Charsets.UTF_8))
        return zip.finish()
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** XML text and attribute escaping; characters XML 1.0 can't hold are dropped. */
    fun xml(text: String): String = buildString {
        for (c in text) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\'' -> append("&apos;")
                c == '\t' || c == '\n' || c == '\r' -> append(c)
                c.code < 0x20 || c == '￾' || c == '￿' -> Unit
                else -> append(c)
            }
        }
    }

    private const val CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

    internal fun opf(stub: StubBook, key: String): String = buildString {
        val lang = xml(stub.language)
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="$lang">""").append('\n')
        append("""  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""").append('\n')
        append("""    <dc:identifier id="bookid">urn:ottershelf:bookmory:${xml(key)}</dc:identifier>""").append('\n')
        stub.isbn13?.let { append("""    <dc:identifier id="isbn">urn:isbn:${xml(it)}</dc:identifier>""").append('\n') }
        append("""    <dc:title>${xml(stub.title)}</dc:title>""").append('\n')
        stub.authors.forEachIndexed { i, a -> append("""    <dc:creator id="creator${i + 1}">${xml(a)}</dc:creator>""").append('\n') }
        append("""    <dc:language>$lang</dc:language>""").append('\n')
        stub.publisher?.let { append("""    <dc:publisher>${xml(it)}</dc:publisher>""").append('\n') }
        stub.date?.let { append("""    <dc:date>${xml(it)}</dc:date>""").append('\n') }
        append("""    <meta property="dcterms:modified">$MODIFIED</meta>""").append('\n')
        append("  </metadata>\n")
        append("  <manifest>\n")
        append("""    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""").append('\n')
        append("""    <item id="placeholder" href="placeholder.xhtml" media-type="application/xhtml+xml"/>""").append('\n')
        append("  </manifest>\n")
        append("  <spine>\n")
        append("""    <itemref idref="placeholder"/>""").append('\n')
        append("  </spine>\n")
        append("</package>\n")
    }

    private fun xhtmlHead(stub: StubBook) =
        """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="${xml(stub.language)}" lang="${xml(stub.language)}">
<head><title>${xml(stub.title)}</title></head>
"""

    internal fun nav(stub: StubBook): String = xhtmlHead(stub) +
        """<body>
<nav epub:type="toc" id="toc">
<h1>Contents</h1>
<ol><li><a href="placeholder.xhtml">${xml(stub.title)}</a></li></ol>
</nav>
</body>
</html>
"""

    internal fun page(stub: StubBook): String = buildString {
        append(xhtmlHead(stub))
        append("<body>\n")
        append("<h1>").append(xml(stub.title)).append("</h1>\n")
        if (stub.authors.isNotEmpty()) append("<p>").append(xml(stub.authors.joinToString(", "))).append("</p>\n")
        val type = stub.bookType?.takeIf { it.isNotBlank() }?.let { " (${xml(it)})" }.orEmpty()
        append("<p>This is a placeholder for a book read elsewhere$type. It was imported from Bookmory to keep its reading history.</p>\n")
        append("</body>\n</html>\n")
    }
}

/**
 * A minimal ZIP writer whose output depends only on what is written: no extra fields, no data
 * descriptors, DOS time 1980-01-01 00:00, version 2.0, UTF-8 names (flag bit 11). DEFLATED entries
 * hold stored deflate blocks (RFC 1951 BTYPE 00), which every inflater reads.
 */
internal class DeterministicZip {
    private val out = ByteArrayOutputStream()
    private val central = ByteArrayOutputStream()
    private var count = 0

    fun stored(name: String, data: ByteArray) = entry(name, data, method = 0, compressed = data)

    fun deflated(name: String, data: ByteArray) = entry(name, data, method = 8, compressed = storedDeflate(data))

    private fun entry(name: String, data: ByteArray, method: Int, compressed: ByteArray) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(data) }.value
        val offset = out.size()
        // local file header
        out.int32(0x04034b50); out.int16(20); out.int16(FLAGS); out.int16(method)
        out.int16(DOS_TIME); out.int16(DOS_DATE)
        out.int32(crc); out.int32(compressed.size.toLong()); out.int32(data.size.toLong())
        out.int16(nameBytes.size); out.int16(0)
        out.write(nameBytes); out.write(compressed)
        // central directory record
        central.int32(0x02014b50); central.int16(20); central.int16(20); central.int16(FLAGS); central.int16(method)
        central.int16(DOS_TIME); central.int16(DOS_DATE)
        central.int32(crc); central.int32(compressed.size.toLong()); central.int32(data.size.toLong())
        central.int16(nameBytes.size); central.int16(0); central.int16(0)
        central.int16(0); central.int16(0); central.int32(0); central.int32(offset.toLong())
        central.write(nameBytes)
        count++
    }

    fun finish(): ByteArray {
        val cdOffset = out.size()
        val cd = central.toByteArray()
        out.write(cd)
        out.int32(0x06054b50); out.int16(0); out.int16(0); out.int16(count); out.int16(count)
        out.int32(cd.size.toLong()); out.int32(cdOffset.toLong()); out.int16(0)
        return out.toByteArray()
    }

    private fun storedDeflate(data: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        var pos = 0
        do {
            val len = minOf(0xFFFF, data.size - pos)
            val last = pos + len >= data.size
            o.write(if (last) 1 else 0)
            o.int16(len); o.int16(len.inv() and 0xFFFF)
            o.write(data, pos, len)
            pos += len
        } while (!last)
        return o.toByteArray()
    }

    private fun ByteArrayOutputStream.int16(v: Int) { write(v and 0xFF); write((v ushr 8) and 0xFF) }
    private fun ByteArrayOutputStream.int32(v: Long) { int16((v and 0xFFFF).toInt()); int16(((v ushr 16) and 0xFFFF).toInt()) }
    private fun ByteArrayOutputStream.int32(v: Int) = int32(v.toLong() and 0xFFFFFFFFL)

    companion object {
        private const val FLAGS = 0x0800
        private const val DOS_TIME = 0
        /** 1980-01-01: (1980 - 1980) shl 9 or (1 shl 5) or 1. */
        private const val DOS_DATE = (1 shl 5) or 1
    }
}
