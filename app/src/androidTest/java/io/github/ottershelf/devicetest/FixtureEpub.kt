package io.github.ottershelf.devicetest

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * A made-up EPUB 3 for the reader tests, written as a downloaded copy is kept on the phone
 * (`book.epub` + the server's `epub/:id/info` answer as `info.json`, see core.download.LocalEpub),
 * so the reader page reads it through the app's own ReaderRequests.
 *
 * Long chapters (headings, sub-headings, paragraphs of varied length, the book's own CSS with a
 * first-line indent) and a short afterword at the end, as many novels have. [pack] writes other
 * books the same way (ReaderHostileBookDeviceTest's).
 */
object FixtureEpub {

    data class Chapter(val id: String, val title: String, val paragraphs: Int, val sections: Int = 0)

    val chapters = listOf(
        Chapter("ch1", "The Harbour", paragraphs = 70, sections = 4),
        Chapter("ch2", "The Long Road", paragraphs = 70, sections = 4),
        Chapter("ch3", "Winter Quarters", paragraphs = 70, sections = 4),
        Chapter("ch4", "The Crossing", paragraphs = 70, sections = 4),
        Chapter("ch5", "Lanterns", paragraphs = 70, sections = 4),
        Chapter("ch6", "Homecoming", paragraphs = 70, sections = 4),
        Chapter("after", "Afterword", paragraphs = 4),
    )

    const val TITLE = "The Orbit Station"

    fun href(chapter: Chapter) = "OEBPS/${chapter.id}.xhtml"

    /** Writes `book.epub` and `info.json` into [dir] and returns it. */
    fun write(dir: File): File {
        val random = Random(20260926)
        val items = listOf(Item("css", "style.css", "text/css", CSS.toByteArray())) +
            chapters.map { Item(it.id, "${it.id}.xhtml", XHTML, chapter(it, random).toByteArray()) }
        return pack(dir, TITLE, items, spine = chapters.map { it.id }, toc = chapters.map { TocEntry(it.title, "${it.id}.xhtml") })
    }

    /** A file of a book: its manifest id, its path under `OEBPS/`, its media type and bytes. */
    class Item(val id: String, val href: String, val type: String, val bytes: ByteArray, val properties: String? = null)

    /** A contents entry: its label and the file's path under `OEBPS/`. */
    data class TocEntry(val label: String, val href: String)

    /**
     * Writes any book as a downloaded copy is kept: `book.epub` (the mimetype, the container, a nav
     * made from [toc], [items] under `OEBPS/`, the package document with [spine], the ids read in
     * order) and `info.json`. Returns [dir].
     */
    fun pack(dir: File, title: String, items: List<Item>, spine: List<String>, toc: List<TocEntry>): File {
        dir.mkdirs()
        val manifest = listOf(Item("nav", "nav.xhtml", XHTML, nav(toc).toByteArray(), "nav")) + items
        val files = linkedMapOf<String, ByteArray>()
        files["mimetype"] = "application/epub+zip".toByteArray()
        files["META-INF/container.xml"] = CONTAINER.toByteArray()
        for (item in manifest) files["OEBPS/${item.href}"] = item.bytes
        files["OEBPS/content.opf"] = opf(title, manifest, spine).toByteArray()

        File(dir, "book.epub").outputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                for ((name, bytes) in files) {
                    val entry = ZipEntry(name)
                    if (name == "mimetype") {
                        entry.method = ZipEntry.STORED
                        entry.size = bytes.size.toLong()
                        entry.compressedSize = bytes.size.toLong()
                        entry.crc = CRC32().apply { update(bytes) }.value
                    }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
        File(dir, "info.json").writeText(info(title, manifest, spine, toc).toString())
        return dir
    }

    /** The server's `epub/:bookId/info` for these files (epub.service.ts `getBookInfo`). */
    private fun info(title: String, manifest: List<Item>, spine: List<String>, toc: List<TocEntry>): JsonObject {
        val byId = manifest.associateBy { it.id }
        return buildJsonObject {
            put("containerPath", "OEBPS/content.opf")
            put("rootPath", "OEBPS/")
            putJsonArray("spine") {
                for (id in spine) addJsonObject {
                    val item = byId.getValue(id)
                    put("idref", id)
                    put("href", "OEBPS/${item.href}")
                    put("mediaType", item.type)
                    put("linear", true)
                }
            }
            putJsonArray("manifest") {
                for (item in manifest) addJsonObject {
                    put("id", item.id)
                    put("href", "OEBPS/${item.href}")
                    put("mediaType", item.type)
                    put("size", item.bytes.size)
                    if (item.properties != null) putJsonArray("properties") { add(item.properties) }
                }
            }
            put("optionalFiles", JsonArray(emptyList()))
            putJsonObject("toc") {
                put("label", "Table of Contents")
                put("children", buildJsonArray {
                    for (entry in toc) addJsonObject {
                        put("label", entry.label)
                        put("href", "OEBPS/${entry.href}")
                    }
                })
            }
            putJsonObject("metadata") {
                put("title", title)
                put("creator", "Device Test")
                put("language", "en")
                put("identifier", "bookorbit-device-test")
            }
            put("coverPath", null as String?)
        }
    }

    private fun opf(title: String, manifest: List<Item>, spine: List<String>): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id" xml:lang="en">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="id">bookorbit-device-test</dc:identifier>
    <dc:title>$title</dc:title>
    <dc:creator>Device Test</dc:creator>
    <dc:language>en</dc:language>
    <meta property="dcterms:modified">2026-09-26T00:00:00Z</meta>
  </metadata>
  <manifest>
""")
        for (i in manifest) {
            append("    <item id=\"${i.id}\" href=\"${i.href}\" media-type=\"${i.type}\"")
            if (i.properties != null) append(" properties=\"${i.properties}\"")
            append("/>\n")
        }
        append("  </manifest>\n  <spine>\n")
        for (id in spine) append("    <itemref idref=\"$id\"/>\n")
        append("  </spine>\n</package>\n")
    }

    private fun nav(toc: List<TocEntry>): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en"><head><title>Contents</title></head>
<body><nav epub:type="toc" id="toc"><h1>Contents</h1><ol>
""")
        for (entry in toc) append("<li><a href=\"${entry.href}\">${entry.label}</a></li>\n")
        append("</ol></nav></body></html>\n")
    }

    private fun chapter(c: Chapter, random: Random): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en"><head><title>${c.title}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body>
<section id="${c.id}-top">
<h1 id="${c.id}-title">${c.title}</h1>
""")
        val every = if (c.sections > 0) c.paragraphs / c.sections else Int.MAX_VALUE
        for (p in 1..c.paragraphs) {
            if (p > 1 && (p - 1) % every == 0) append("<h2 id=\"${c.id}-s${(p - 1) / every}\">${SECTIONS[((p - 1) / every) % SECTIONS.size]}</h2>\n")
            append("<p id=\"${c.id}-p$p\">")
            val sentences = 3 + random.nextInt(4)
            repeat(sentences) { s ->
                if (s > 0) append(' ')
                append(sentence(random))
            }
            append("</p>\n")
        }
        append("</section>\n</body></html>\n")
    }

    private fun sentence(random: Random): String {
        val words = 9 + random.nextInt(14)
        val out = StringBuilder()
        for (w in 0 until words) {
            val word = WORDS[random.nextInt(WORDS.size)]
            if (w == 0) out.append(word.replaceFirstChar { it.uppercase() }) else out.append(' ').append(word)
            if (w in 3 until words - 2 && random.nextInt(9) == 0) out.append(',')
        }
        out.append(if (random.nextInt(7) == 0) '?' else '.')
        return out.toString()
    }

    const val XHTML = "application/xhtml+xml"

    private val CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
"""

    /** The book's own styling, as a publisher's: a first-line indent and no paragraph margins. */
    const val CSS = """body { margin: 0 5%; }
h1 { font-size: 1.6em; text-align: center; margin: 1.5em 0 1em; letter-spacing: 0.02em; }
h2 { font-size: 1.2em; margin: 1.2em 0 0.6em; }
p { margin: 0; text-indent: 1.2em; }
"""

    private val SECTIONS = listOf("Morning", "The Ledger", "Night Watch", "Letters", "Salt", "The Signal")

    private val WORDS = (
        "the orbit station hummed quietly while crew went about slow work of evening checking gauges writing short notes " +
            "and watching planet turn below them in dark harbour lights ships rope salt wind lantern winter road river bridge " +
            "letter captain daughter market bread copper bell tower morning quiet careful distant silver window garden stone " +
            "north south harvest ledger signal answer memory long small bright heavy gentle patient through across between " +
            "under over before after because although whenever someone nobody everything remembered carried opened waited " +
            "listened followed returned promised wondered decided understood serendipity"
        ).split(' ').filter { it.isNotBlank() }
}
