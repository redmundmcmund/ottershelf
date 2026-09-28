package io.github.ottershelf.feature.pdf

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.min

/** One entry of a PDF's outline (its bookmarks): [page] 0-based, [depth] 0 at the top level. */
data class OutlineEntry(val title: String, val page: Int, val depth: Int)

/**
 * Reads a PDF's outline (the document's own table of contents). The platform PdfRenderer (pdfium)
 * doesn't expose it, so this is a small read-only PDF object reader that goes only as far as the
 * outline needs: the cross-reference tables (classic tables, cross-reference streams, hybrid files,
 * incremental updates via /Prev), object streams, Flate with PNG predictors, the page tree, the
 * outline items (/Dest or a /GoTo action; explicit, named (/Dests or the /Names tree) and
 * dictionary destinations) and text strings (PDFDocEncoding, UTF-16, UTF-8). A file whose
 * cross-references don't parse is scanned for its objects, as viewers repair it.
 *
 * Encrypted documents (their strings are encrypted too) and anything unexpected give an empty list:
 * the reader then offers page thumbnails instead. Pure JVM; runs off the main thread.
 */
object PdfOutline {
    const val MAX_ENTRIES = 3000

    fun read(file: File): List<OutlineEntry> = try {
        RandomAccessFile(file, "r").use { raf ->
            val size = min(raf.length(), Int.MAX_VALUE.toLong())
            val buffer = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
            PdfObjects(buffer).outline()
        }
    } catch (e: Exception) {
        emptyList()
    } catch (e: StackOverflowError) {
        emptyList()
    } catch (e: OutOfMemoryError) {
        emptyList()
    }

    internal fun read(bytes: ByteArray): List<OutlineEntry> = PdfObjects(ByteBuffer.wrap(bytes)).outline()
}

internal data class PdfName(val name: String)
internal data class PdfRef(val num: Int, val gen: Int)
internal class PdfString(val bytes: ByteArray)
internal class PdfKeyword(val word: String)
internal class PdfStream(val dict: Map<String, Any?>, val start: Int, val length: Int)

/** The PDF objects of one file, read on demand. */
internal class PdfObjects(private val buf: ByteBuffer) {
    private val size = buf.limit()
    private val offsets = HashMap<Int, Int>()
    private val compressed = HashMap<Int, Pair<Int, Int>>()
    private val known = HashSet<Int>()
    private val trailer = HashMap<String, Any?>()
    private val cache = HashMap<Int, Any?>()
    private val loading = HashSet<Int>()
    private val objectStreams = HashMap<Int, Pair<ByteArray, Map<Int, Int>>?>()
    /** The decoded bytes [objectStreams] holds, at most [MAX_HELD_STREAM_BYTES]. */
    private var heldStreamBytes = 0L
    /** Stream bytes read and inflated so far, at most about [MAX_TOTAL_DECODED]: a crafted file can't keep the parser busy. */
    private var decodedTotal = 0L
    /** What every [Lexer] of this file may still parse into arrays and dictionaries, at most [MAX_VALUES] in all. */
    private val values = ValueBudget(MAX_VALUES)
    private var pageIndex: Map<Int, Int>? = null
    private var namedDests: Map<String, Any?>? = null

    fun outline(max: Int = PdfOutline.MAX_ENTRIES): List<OutlineEntry> {
        readCrossReferences()
        if (trailer["Encrypt"] != null) return emptyList()
        var catalog = resolve(trailer["Root"]) as? Map<*, *>
        if (catalog == null) {
            trailer.remove("Root")
            reconstruct()
            if (trailer["Encrypt"] != null) return emptyList()
            catalog = resolve(trailer["Root"]) as? Map<*, *> ?: return emptyList()
        }
        val outlines = resolve(catalog["Outlines"]) as? Map<*, *> ?: return emptyList()
        val pages = pages(catalog)
        val raw = ArrayList<OutlineEntry>()
        val seen = HashSet<Int>()
        fun walk(first: Any?, depth: Int) {
            var ref = first
            while (ref is PdfRef && raw.size < max && seen.add(ref.num)) {
                val item = resolve(ref) as? Map<*, *> ?: break
                val title = (resolve(item["Title"]) as? PdfString)?.let { decodeText(it.bytes) }.orEmpty()
                val page = destinationPage(item["Dest"], pages, 0) ?: actionPage(item["A"], pages)
                raw += OutlineEntry(title, page ?: -1, depth)
                if (depth < MAX_DEPTH) walk(item["First"], depth + 1)
                ref = item["Next"]
            }
        }
        walk(outlines["First"], 0)
        // An entry without a destination of its own (a heading) opens at its first child's page.
        val out = ArrayList<OutlineEntry>(raw.size)
        raw.forEachIndexed { i, entry ->
            if (entry.page >= 0) out += entry
            else {
                var j = i + 1
                var page = -1
                while (j < raw.size && raw[j].depth > entry.depth) {
                    if (raw[j].page >= 0) { page = raw[j].page; break }
                    j++
                }
                if (page >= 0) out += entry.copy(page = page)
            }
        }
        return out
    }

    // --- cross references -------------------------------------------------------------------

    private fun readCrossReferences() {
        val start = startXref() ?: return reconstruct()
        try {
            readXref(start, HashSet())
        } catch (e: Exception) {
            // Keep whatever parsed; a missing Root falls back to the scan.
        }
        if (trailer["Root"] == null) reconstruct()
    }

    private fun startXref(): Int? {
        val key = "startxref".toByteArray(Charsets.ISO_8859_1)
        var i = size - key.size
        val stop = (size - 2048).coerceAtLeast(0)
        while (i >= stop) {
            if (matches(i, key)) {
                val lexer = Lexer(buf, i + key.size, size, values)
                return (lexer.next() as? Long)?.toInt()
            }
            i--
        }
        return null
    }

    private fun readXref(offset: Int, visited: MutableSet<Int>) {
        if (offset !in 0 until size || !visited.add(offset)) return
        val lexer = Lexer(buf, offset, size, values)
        lexer.skipSpace()
        if (matches(lexer.pos, XREF)) {
            lexer.pos += XREF.size
            while (true) {
                val first = lexer.next()
                if (first is PdfKeyword) break // "trailer"
                val count = lexer.next() as? Long ?: return
                val from = (first as? Long)?.toInt() ?: return
                for (k in 0 until count.toInt()) {
                    val off = lexer.next() as? Long ?: return
                    lexer.next() // generation
                    val type = (lexer.next() as? PdfKeyword)?.word
                    val num = from + k
                    if (known.size < MAX_OBJECTS && known.add(num) && type == "n") offsets[num] = off.toInt()
                }
            }
            val dict = lexer.next() as? Map<*, *> ?: return
            mergeTrailer(dict)
            (dict["XRefStm"] as? Long)?.let { readXref(it.toInt(), visited) }
            (dict["Prev"] as? Long)?.let { readXref(it.toInt(), visited) }
        } else {
            val stream = readIndirect(offset) as? PdfStream ?: return
            val dict = stream.dict
            val data = decode(stream) ?: return
            // A field is at most 8 bytes (a Long); a row of none (/W [0 0 0]) would never use up the
            // data, and /Size could then add two billion entries.
            val widths = (dict["W"] as? List<*>)?.map { w -> if (w !is Long) 0 else if (w in 0..8) w.toInt() else return } ?: return
            if (widths.size < 3) return
            val sizeEntry = (dict["Size"] as? Long)?.toInt() ?: 0
            val index = (dict["Index"] as? List<*>)?.map { (it as? Long)?.toInt() ?: 0 } ?: listOf(0, sizeEntry)
            val rowLength = widths.sum()
            if (rowLength <= 0) return
            var at = 0
            var i = 0
            while (i + 1 < index.size) {
                val from = index[i]
                val count = index[i + 1]
                for (k in 0 until count) {
                    if (at + rowLength > data.size || known.size >= MAX_OBJECTS) break
                    val type = if (widths[0] == 0) 1L else field(data, at, widths[0])
                    val f2 = field(data, at + widths[0], widths[1])
                    val f3 = field(data, at + widths[0] + widths[1], widths[2])
                    at += rowLength
                    val num = from + k
                    if (!known.add(num)) continue
                    when (type) {
                        1L -> offsets[num] = f2.toInt()
                        2L -> compressed[num] = f2.toInt() to f3.toInt()
                    }
                }
                i += 2
            }
            mergeTrailer(dict)
            (dict["Prev"] as? Long)?.let { readXref(it.toInt(), visited) }
        }
    }

    private fun field(data: ByteArray, at: Int, width: Int): Long {
        var v = 0L
        for (j in 0 until width) v = (v shl 8) or (data[at + j].toLong() and 0xff)
        return v
    }

    private fun mergeTrailer(dict: Map<*, *>) {
        for (key in listOf("Root", "Encrypt", "Info")) {
            if (trailer[key] == null && dict[key] != null) trailer[key] = dict[key]
        }
    }

    /** The damaged-file fallback: every "n g obj" in the file (later ones win), and the last trailer or catalog. */
    private fun reconstruct() {
        offsets.clear()
        compressed.clear()
        known.clear()
        cache.clear()
        var i = 0
        val obj = "obj".toByteArray(Charsets.ISO_8859_1)
        val trailerKey = "trailer".toByteArray(Charsets.ISO_8859_1)
        var lastTrailer = -1
        while (i < size - 3) {
            val b = buf.get(i)
            if (b == 'o'.code.toByte() && matches(i, obj) && (i + 3 >= size || isDelimiterOrSpace(buf.get(i + 3)))) {
                objectStart(i)?.let { (num, start) -> if (offsets.size < MAX_OBJECTS || num in offsets) offsets[num] = start }
            } else if (b == 't'.code.toByte() && matches(i, trailerKey)) {
                lastTrailer = i + trailerKey.size
            }
            i++
        }
        if (lastTrailer >= 0) {
            runCatching { (Lexer(buf, lastTrailer, size, values).next() as? Map<*, *>)?.let(::mergeTrailer) }
        }
        if (trailer["Root"] == null) {
            for (num in offsets.keys.sorted()) {
                val o = runCatching { resolve(PdfRef(num, 0)) }.getOrNull()
                val dict = (o as? Map<*, *>) ?: (o as? PdfStream)?.dict
                if (dict != null && dict["Type"] == PdfName("XRef")) mergeTrailer(dict)
                if (dict != null && dict["Type"] == PdfName("Catalog")) {
                    trailer["Root"] = PdfRef(num, 0)
                    break
                }
            }
        }
    }

    /** For "12 0 obj" ending before [objAt]: (12, offset of "12"). */
    private fun objectStart(objAt: Int): Pair<Int, Int>? {
        var p = objAt - 1
        fun skipBack() { while (p >= 0 && isSpace(buf.get(p))) p-- }
        fun digitsBack(): Int? {
            val end = p
            while (p >= 0 && buf.get(p) in DIGIT_0..DIGIT_9) p--
            if (p == end) return null
            var v = 0
            for (j in p + 1..end) v = v * 10 + (buf.get(j) - DIGIT_0)
            return v
        }
        skipBack()
        digitsBack() ?: return null
        skipBack()
        val num = digitsBack() ?: return null
        return num to p + 1
    }

    // --- objects ----------------------------------------------------------------------------

    fun resolve(value: Any?): Any? = if (value is PdfRef) objectNumber(value.num) else value

    private fun objectNumber(num: Int): Any? {
        if (cache.containsKey(num)) return cache[num]
        if (!loading.add(num)) return null
        try {
            val value = offsets[num]?.let { runCatching { readIndirect(it) }.getOrNull() }
                ?: compressed[num]?.let { (stream, index) -> runCatching { fromObjectStream(stream, num, index) }.getOrNull() }
            cache[num] = value
            return value
        } finally {
            loading.remove(num)
        }
    }

    /** "n g obj <object> [stream ...]" at [offset]. */
    private fun readIndirect(offset: Int): Any? {
        val lexer = Lexer(buf, offset, size, values)
        lexer.next() as? Long ?: return null
        lexer.next() as? Long ?: return null
        if ((lexer.next() as? PdfKeyword)?.word != "obj") return null
        val value = lexer.next()
        if (value !is Map<*, *>) return value
        val afterDict = lexer.pos
        val keyword = lexer.next()
        if (keyword !is PdfKeyword || keyword.word != "stream") {
            lexer.pos = afterDict
            return value
        }
        var start = lexer.pos
        if (start < size && buf.get(start) == CR) start++
        if (start < size && buf.get(start) == LF) start++
        @Suppress("UNCHECKED_CAST")
        val dict = value as Map<String, Any?>
        var length = (resolve(dict["Length"]) as? Long)?.toInt() ?: -1
        if (length < 0 || start + length > size || !endsStream(start + length)) {
            length = findEndStream(start) - start
        }
        return PdfStream(dict, start, length.coerceAtLeast(0))
    }

    private fun endsStream(at: Int): Boolean {
        var p = at
        while (p < size && isSpace(buf.get(p))) p++
        return matches(p, ENDSTREAM)
    }

    private fun findEndStream(from: Int): Int {
        var p = from
        while (p < size - ENDSTREAM.size) {
            if (buf.get(p) == 'e'.code.toByte() && matches(p, ENDSTREAM)) {
                var end = p
                if (end > from && buf.get(end - 1) == LF) end--
                if (end > from && buf.get(end - 1) == CR) end--
                return end
            }
            p++
        }
        return size
    }

    private fun fromObjectStream(streamNum: Int, num: Int, index: Int): Any? {
        val held = if (objectStreams.containsKey(streamNum)) objectStreams[streamNum] else {
            val loaded = loadObjectStream(streamNum)
            val bytes = loaded?.first?.size ?: 0
            // Kept for the next objects in it, but only so many bytes at once: past that the older
            // ones go (decoded again if asked for, within the decoding budget).
            if (heldStreamBytes + bytes > MAX_HELD_STREAM_BYTES) {
                objectStreams.clear()
                heldStreamBytes = 0
            }
            objectStreams[streamNum] = loaded
            heldStreamBytes += bytes
            loaded
        }
        val (data, table) = held ?: return null
        val at = table[num] ?: return null
        if (at !in data.indices) return null
        return Lexer(ByteBuffer.wrap(data), at, data.size, values).next()
    }

    private fun loadObjectStream(streamNum: Int): Pair<ByteArray, Map<Int, Int>>? {
        val stream = objectNumber(streamNum) as? PdfStream ?: return null
        val bytes = decode(stream) ?: return null
        val count = (stream.dict["N"] as? Long)?.toInt() ?: 0
        val first = (stream.dict["First"] as? Long)?.toInt() ?: 0
        val header = Lexer(ByteBuffer.wrap(bytes), 0, bytes.size, values)
        val offsetsIn = HashMap<Int, Int>()
        repeat(count) {
            val n = (header.next() as? Long)?.toInt() ?: return@repeat
            val o = (header.next() as? Long)?.toInt() ?: return@repeat
            offsetsIn.putIfAbsent(n, first + o)
        }
        return bytes to offsetsIn
    }

    /**
     * The stream's data with its filters undone; null for a filter this reader doesn't know, a
     * stream larger than [MAX_INFLATED], or once this file has decoded [MAX_TOTAL_DECODED] in all.
     */
    private fun decode(stream: PdfStream): ByteArray? {
        if (stream.length > MAX_INFLATED || decodedTotal >= MAX_TOTAL_DECODED) return null
        decodedTotal += stream.length
        val raw = ByteArray(stream.length)
        for (i in 0 until stream.length) raw[i] = buf.get(stream.start + i)
        val filters = when (val f = resolve(stream.dict["Filter"])) {
            null -> emptyList()
            is PdfName -> listOf(f.name)
            is List<*> -> f.map { (resolve(it) as? PdfName)?.name ?: return null }
            else -> return null
        }
        val params = when (val p = resolve(stream.dict["DecodeParms"])) {
            is Map<*, *> -> listOf(p)
            is List<*> -> p.map { resolve(it) as? Map<*, *> }
            else -> emptyList()
        }
        var data = raw
        filters.forEachIndexed { i, filter ->
            if (filter != "FlateDecode" && filter != "Fl") return null
            val budget = MAX_TOTAL_DECODED - decodedTotal
            if (budget <= 0) return null
            data = inflate(data, max = min(budget, MAX_INFLATED.toLong()).toInt())
            decodedTotal += data.size
            val p = params.getOrNull(i)
            val predictor = (p?.get("Predictor") as? Long)?.toInt() ?: 1
            if (predictor >= 10) {
                data = unpredict(
                    data,
                    colors = (p?.get("Colors") as? Long)?.toInt() ?: 1,
                    bits = (p?.get("BitsPerComponent") as? Long)?.toInt() ?: 8,
                    columns = (p?.get("Columns") as? Long)?.toInt() ?: 1,
                )
            } else if (predictor != 1) return null
        }
        return data
    }

    // --- pages, destinations ----------------------------------------------------------------

    /** Page object number -> 0-based index, in the page tree's order. */
    private fun pages(catalog: Map<*, *>): Map<Int, Int> {
        pageIndex?.let { return it }
        val map = HashMap<Int, Int>()
        val seen = HashSet<Int>()
        var next = 0
        fun walk(ref: Any?, depth: Int) {
            if (depth > 64 || next > MAX_PAGES) return
            if (ref is PdfRef && !seen.add(ref.num)) return
            val node = resolve(ref) as? Map<*, *> ?: return
            val kids = resolve(node["Kids"]) as? List<*>
            if (kids != null && node["Type"] != PdfName("Page")) {
                for (kid in kids) walk(kid, depth + 1)
            } else if (ref is PdfRef) {
                map[ref.num] = next++
            }
        }
        walk(catalog["Pages"], 0)
        return map.also { pageIndex = it }
    }

    private fun actionPage(action: Any?, pages: Map<Int, Int>): Int? {
        val dict = resolve(action) as? Map<*, *> ?: return null
        if (dict["S"] != PdfName("GoTo")) return null
        return destinationPage(dict["D"], pages, 0)
    }

    private fun destinationPage(dest: Any?, pages: Map<Int, Int>, depth: Int): Int? {
        if (depth > 4) return null
        return when (val d = resolve(dest)) {
            is List<*> -> when (val target = d.firstOrNull()) {
                is PdfRef -> pages[target.num]
                is Long -> target.toInt() // some writers put the page index here
                else -> null
            }
            is Map<*, *> -> destinationPage(d["D"], pages, depth + 1)
            is PdfName -> named(d.name)?.let { destinationPage(it, pages, depth + 1) }
            is PdfString -> named(String(d.bytes, Charsets.ISO_8859_1))?.let { destinationPage(it, pages, depth + 1) }
            else -> null
        }
    }

    /** A named destination: the catalog's /Dests dictionary (PDF 1.1) or the /Names /Dests tree. */
    private fun named(name: String): Any? {
        val all = namedDests ?: run {
            val map = HashMap<String, Any?>()
            val catalog = resolve(trailer["Root"]) as? Map<*, *>
            (resolve(catalog?.get("Dests")) as? Map<*, *>)?.forEach { (k, v) -> if (k is String) map[k] = v }
            val names = resolve(catalog?.get("Names")) as? Map<*, *>
            val seen = HashSet<Int>()
            fun walk(node: Any?, depth: Int) {
                if (depth > 32 || map.size > MAX_NAMES) return
                if (node is PdfRef && !seen.add(node.num)) return
                val dict = resolve(node) as? Map<*, *> ?: return
                (resolve(dict["Names"]) as? List<*>)?.chunked(2)?.forEach { pair ->
                    val key = resolve(pair.getOrNull(0)) as? PdfString ?: return@forEach
                    map.putIfAbsent(String(key.bytes, Charsets.ISO_8859_1), pair.getOrNull(1))
                }
                (resolve(dict["Kids"]) as? List<*>)?.forEach { walk(it, depth + 1) }
            }
            walk(names?.get("Dests"), 0)
            map.also { namedDests = it }
        }
        return all[name]
    }

    private fun matches(at: Int, bytes: ByteArray): Boolean {
        if (at < 0 || at + bytes.size > size) return false
        for (i in bytes.indices) if (buf.get(at + i) != bytes[i]) return false
        return true
    }

    companion object {
        private const val MAX_DEPTH = 12
        private const val MAX_PAGES = 200_000
        private const val MAX_NAMES = 100_000
        private const val CR: Byte = 13
        private const val LF: Byte = 10
        private const val DIGIT_0: Byte = 48
        private const val DIGIT_9: Byte = 57
        private val XREF = "xref".toByteArray(Charsets.ISO_8859_1)
        private val ENDSTREAM = "endstream".toByteArray(Charsets.ISO_8859_1)
        /** Decompressed stream data is capped (a stream that inflates past this is cut there). */
        private const val MAX_INFLATED = 64 * 1024 * 1024
        /** What one file's streams (cross-references, object streams) may decode to in all. */
        private const val MAX_TOTAL_DECODED = 256L * 1024 * 1024
        /** Decoded object streams kept at once. */
        private const val MAX_HELD_STREAM_BYTES = 64L * 1024 * 1024
        /**
         * Cross-reference entries kept (a book has thousands to tens of thousands of objects); a
         * crafted table can't fill the heap with them.
         */
        private const val MAX_OBJECTS = 500_000
        /**
         * Array elements, dictionary keys and dictionary values parsed from one file, each an object
         * on the heap (up to about 60 bytes): tens of MB at most. A long book's page tree, outline
         * and named destinations come to a few hundred thousand at most, while 64 MB of object stream
         * (inflated from a few KB) of `1 0 R 1 0 R ...` would be ten million.
         */
        internal const val MAX_VALUES = 1_000_000

        fun inflate(data: ByteArray, max: Int = MAX_INFLATED): ByteArray {
            val inflater = Inflater()
            inflater.setInput(data)
            val out = ByteArrayOutputStream(min(data.size.toLong() * 3, max.toLong()).toInt())
            val chunk = ByteArray(16 * 1024)
            try {
                while (!inflater.finished() && out.size() < max) {
                    val n = inflater.inflate(chunk)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(chunk, 0, n)
                }
            } catch (e: DataFormatException) {
                // Keep what inflated before the damage.
            } finally {
                inflater.end()
            }
            return out.toByteArray()
        }

        /** Undoes PNG row predictors (Predictor >= 10), as cross-reference streams use them. */
        fun unpredict(data: ByteArray, colors: Int, bits: Int, columns: Int): ByteArray {
            // In Longs: /Columns comes from the file, and a row longer than the data (two billion
            // columns) must not be allocated.
            val longRow = (colors.toLong() * bits * columns + 7) / 8
            if (longRow <= 0) return data
            if (longRow + 1 > data.size) return ByteArray(0) // not one whole row
            val rowLength = longRow.toInt()
            val bpp = ((colors.toLong() * bits + 7) / 8).coerceIn(1, longRow).toInt()
            val out = ByteArrayOutputStream(data.size)
            var previous = ByteArray(rowLength)
            var i = 0
            while (i + 1 + rowLength <= data.size) {
                val type = data[i].toInt()
                val row = data.copyOfRange(i + 1, i + 1 + rowLength)
                for (j in 0 until rowLength) {
                    val left = if (j >= bpp) row[j - bpp].toInt() and 0xff else 0
                    val up = previous[j].toInt() and 0xff
                    val upLeft = if (j >= bpp) previous[j - bpp].toInt() and 0xff else 0
                    val add = when (type) {
                        1 -> left
                        2 -> up
                        3 -> (left + up) / 2
                        4 -> paeth(left, up, upLeft)
                        else -> 0
                    }
                    row[j] = ((row[j].toInt() and 0xff) + add).toByte()
                }
                out.write(row)
                previous = row
                i += 1 + rowLength
            }
            return out.toByteArray()
        }

        private fun paeth(a: Int, b: Int, c: Int): Int {
            val p = a + b - c
            val pa = abs(p - a)
            val pb = abs(p - b)
            val pc = abs(p - c)
            return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
        }

        /** A text string: UTF-16 with a byte order mark, UTF-8 with one (PDF 2.0), else PDFDocEncoding. */
        fun decodeText(b: ByteArray): String {
            val text = when {
                b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
                b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
                b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> String(b, 3, b.size - 3, Charsets.UTF_8)
                else -> buildString(b.size) { b.forEach { append(pdfDocChar(it.toInt() and 0xff)) } }
            }
            return text.map { if (it < ' ' || it == '\u007f') ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim()
        }

        private const val PDF_DOC_HIGH = "•†‡…—–ƒ⁄‹›−‰„“”‘’‚™ﬁﬂŁŒŠŸŽıłœšž�€"

        private fun pdfDocChar(c: Int): Char = when (c) {
            in 0x80..0xA0 -> PDF_DOC_HIGH[c - 0x80]
            0x18 -> '˘'
            0x19 -> 'ˇ'
            0x1A -> 'ˆ'
            0x1B -> '˙'
            0x1C -> '˝'
            0x1D -> '˛'
            0x1E -> '˚'
            0x1F -> '˜'
            else -> c.toChar()
        }

        fun isSpace(b: Byte): Boolean = b == 0.toByte() || b == 9.toByte() || b == 10.toByte() || b == 12.toByte() || b == 13.toByte() || b == 32.toByte()

        fun isDelimiterOrSpace(b: Byte): Boolean = isSpace(b) || b.toInt().toChar() in "()<>[]{}/%"
    }
}

/**
 * How many more values (array elements, dictionary keys and values) a file's [Lexer]s may parse,
 * shared by all of them. Once it runs out every parse throws, as "Nested too deep" does, so the
 * outline comes out empty or cut short instead of the heap filling up.
 */
internal class ValueBudget(private var left: Int) {
    fun take() {
        check(left > 0) { "Too many values" }
        left--
    }
}

/** Reads PDF tokens and objects from [buf] (absolute positions), starting at [pos], at most [values] of them in containers. */
internal class Lexer(private val buf: ByteBuffer, var pos: Int, private val end: Int, private val values: ValueBudget) {

    private fun peek(offset: Int = 0): Int = if (pos + offset < end) buf.get(pos + offset).toInt() and 0xff else -1

    fun skipSpace() {
        while (pos < end) {
            val c = peek()
            if (c == '%'.code) {
                while (pos < end && peek() != 10 && peek() != 13) pos++
            } else if (PdfObjects.isSpace(c.toByte())) pos++
            else return
        }
    }

    /** The next object (a number, name, string, array, dictionary, reference, keyword...), or null at the end. */
    fun next(depth: Int = 0): Any? {
        if (depth > 64) throw IllegalStateException("Nested too deep")
        skipSpace()
        if (pos >= end) return null
        val c = peek()
        return when {
            c == '/'.code -> name()
            c == '('.code -> literal()
            c == '<'.code && peek(1) == '<'.code -> dictionary(depth)
            c == '<'.code -> hex()
            c == '['.code -> array(depth)
            c == '>'.code || c == ']'.code || c == ')'.code || c == '{'.code || c == '}'.code -> {
                pos++
                PdfKeyword(c.toChar().toString())
            }
            c == '+'.code || c == '-'.code || c == '.'.code || c in '0'.code..'9'.code -> numberOrRef()
            else -> keyword()
        }
    }

    private fun regularRun(): String {
        val start = pos
        while (pos < end && !PdfObjects.isDelimiterOrSpace(peek().toByte())) pos++
        val bytes = ByteArray(pos - start) { buf.get(start + it) }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun keyword(): Any? = when (val word = regularRun().ifEmpty { pos++; "?" }) {
        "true" -> true
        "false" -> false
        "null" -> null
        else -> PdfKeyword(word)
    }

    private fun name(): PdfName {
        pos++
        val raw = regularRun()
        if ('#' !in raw) return PdfName(raw)
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '#' && i + 3 <= raw.length) {
                val code = raw.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    i += 3
                    continue
                }
            }
            out.append(ch)
            i++
        }
        return PdfName(out.toString())
    }

    private fun numberOrRef(): Any? {
        val text = regularRun()
        if ('.' in text) return text.toDoubleOrNull() ?: 0.0
        val value = text.toLongOrNull() ?: return 0L
        // "12 0 R": a reference.
        val save = pos
        skipSpace()
        if (peek() in '0'.code..'9'.code) {
            val genText = regularRun()
            val gen = genText.toIntOrNull()
            skipSpace()
            if (gen != null && peek() == 'R'.code && (pos + 1 >= end || PdfObjects.isDelimiterOrSpace(buf.get(pos + 1)))) {
                pos++
                return PdfRef(value.toInt(), gen)
            }
        }
        pos = save
        return value
    }

    private fun literal(): PdfString {
        pos++
        val out = ByteArrayOutputStream()
        var nesting = 1
        while (pos < end) {
            val c = peek()
            pos++
            when (c) {
                '('.code -> { nesting++; out.write(c) }
                ')'.code -> { nesting--; if (nesting == 0) break; out.write(c) }
                '\\'.code -> {
                    val e = peek()
                    pos++
                    when (e) {
                        'n'.code -> out.write(10)
                        'r'.code -> out.write(13)
                        't'.code -> out.write(9)
                        'b'.code -> out.write(8)
                        'f'.code -> out.write(12)
                        13 -> if (peek() == 10) pos++ // line continuation
                        10 -> {}
                        in '0'.code..'7'.code -> {
                            var v = e - '0'.code
                            repeat(2) {
                                val d = peek()
                                if (d in '0'.code..'7'.code) {
                                    v = v * 8 + (d - '0'.code)
                                    pos++
                                }
                            }
                            out.write(v and 0xff)
                        }
                        -1 -> {}
                        else -> out.write(e)
                    }
                }
                else -> out.write(c)
            }
        }
        return PdfString(out.toByteArray())
    }

    private fun hex(): PdfString {
        pos++
        val out = ByteArrayOutputStream()
        var high = -1
        while (pos < end) {
            val c = peek()
            pos++
            if (c == '>'.code) break
            val v = Character.digit(c, 16)
            if (v < 0) continue
            if (high < 0) high = v else {
                out.write(high * 16 + v)
                high = -1
            }
        }
        if (high >= 0) out.write(high * 16)
        return PdfString(out.toByteArray())
    }

    private fun array(depth: Int): List<Any?> {
        pos++
        val out = ArrayList<Any?>()
        while (true) {
            skipSpace()
            if (pos >= end) break
            if (peek() == ']'.code) {
                pos++
                break
            }
            values.take()
            val before = pos
            val v = next(depth + 1)
            if (pos == before) pos++ // never loop on a stray byte
            out += v
        }
        return out
    }

    private fun dictionary(depth: Int): Map<String, Any?> {
        pos += 2
        val out = LinkedHashMap<String, Any?>()
        while (true) {
            skipSpace()
            if (pos >= end) break
            if (peek() == '>'.code && peek(1) == '>'.code) {
                pos += 2
                break
            }
            values.take()
            val before = pos
            val key = next(depth + 1)
            if (key !is PdfName) {
                if (pos == before) pos++
                continue
            }
            values.take()
            out[key.name] = next(depth + 1)
        }
        return out
    }
}
