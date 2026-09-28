package io.github.ottershelf.feature.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * PdfOutline on made-up PDFs: a classic cross-reference table, a PDF 1.5 file whose objects all sit
 * in a compressed object stream with a predicted cross-reference stream, a damaged startxref, and an
 * encrypted file. The outline covers explicit, action, named (/Names tree) destinations, nested
 * page trees, UTF-16 and PDFDocEncoding titles, escapes, and a heading without a destination.
 */
class PdfOutlineTest {

    private val objects = sortedMapOf(
        1 to "<< /Type /Catalog /Pages 2 0 R /Outlines 6 0 R /Names << /Dests 10 0 R >> >>",
        2 to "<< /Type /Pages /Kids [3 0 R 11 0 R] /Count 3 >>",
        3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>",
        4 to "<< /Type /Page /Parent 11 0 R /MediaBox [0 0 612 792] >>",
        5 to "<< /Type /Page /Parent 11 0 R /MediaBox [0 0 612 792] >>",
        6 to "<< /Type /Outlines /First 7 0 R /Last 12 0 R /Count 5 >>",
        7 to "<< /Title (Chapter 1) /Parent 6 0 R /Next 8 0 R /First 9 0 R /Last 9 0 R /Dest [3 0 R /XYZ 0 792 0] >>",
        8 to "<< /Title (Chapter \\(2\\) \\351t\\351) /Parent 6 0 R /Prev 7 0 R /Next 12 0 R /Dest (second) >>",
        9 to "<< /Title <FEFF0053006500630074006900" + "6F006E00200031002E0031> /Parent 7 0 R /A << /S /GoTo /D [4 0 R /Fit] >> >>",
        10 to "<< /Names [(second) [5 0 R /Fit]] >>",
        11 to "<< /Type /Pages /Parent 2 0 R /Kids [4 0 R 5 0 R] /Count 2 >>",
        12 to "<< /Title (Appendix) /Parent 6 0 R /Prev 8 0 R /First 13 0 R /Last 13 0 R >>",
        13 to "<< /Title (A.1) /Parent 12 0 R /Dest [5 0 R /Fit] >>",
    )

    private val expected = listOf(
        OutlineEntry("Chapter 1", 0, 0),
        OutlineEntry("Section 1.1", 1, 1),
        OutlineEntry("Chapter (2) été", 2, 0),
        OutlineEntry("Appendix", 2, 0),
        OutlineEntry("A.1", 2, 1),
    )

    @Test
    fun classicCrossReferenceTable() {
        assertEquals(expected, PdfOutline.read(classicPdf(objects)))
    }

    @Test
    fun objectStreamAndPredictedCrossReferenceStream() {
        assertEquals(expected, PdfOutline.read(compressedPdf(objects)))
    }

    @Test
    fun damagedStartXrefIsRepairedByScanning() {
        val good = String(classicPdf(objects), Charsets.ISO_8859_1)
        val broken = good.replace(Regex("startxref\\n\\d+"), "startxref\n9")
        assertEquals(expected, PdfOutline.read(broken.toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun encryptedDocumentsHaveNoOutline() {
        val pdf = String(classicPdf(objects), Charsets.ISO_8859_1).replace("/Root 1 0 R", "/Root 1 0 R /Encrypt << /Filter /Standard >>")
        assertTrue(PdfOutline.read(pdf.toByteArray(Charsets.ISO_8859_1)).isEmpty())
    }

    @Test
    fun noOutlineOrGarbage() {
        val none = objects.toSortedMap().apply { this[1] = "<< /Type /Catalog /Pages 2 0 R >>" }
        assertTrue(PdfOutline.read(classicPdf(none)).isEmpty())
        assertTrue(PdfOutline.read("not a pdf at all".toByteArray()).isEmpty())
    }

    @Test
    fun cyclesDoNotLoop() {
        val cyclic = objects.toSortedMap().apply { this[13] = "<< /Title (A.1) /Parent 12 0 R /Next 7 0 R /Dest [5 0 R /Fit] >>" }
        assertEquals(expected, PdfOutline.read(classicPdf(cyclic)))
    }

    /** /W [0 0 0] with /Size 2^31-1: rows of no bytes never used the data up (two billion entries, the heap full). */
    @Test(timeout = 20_000)
    fun aZeroWidthCrossReferenceStreamIsSkippedAndTheFileRepaired() {
        assertEquals(expected, PdfOutline.read(withXrefStream(objects, "/W [0 0 0] /Size 2147483647", byteArrayOf(0))))
        assertEquals(expected, PdfOutline.read(withXrefStream(objects, "/W [1 -4 2] /Size 2147483647", byteArrayOf(0))))
    }

    /** A predictor row longer than the whole stream is neither allocated nor read. */
    @Test(timeout = 20_000)
    fun aPredictorRowLongerThanTheDataIsIgnored() {
        val dict = "/W [1 4 2] /Size 22 /Root 1 0 R /Filter /FlateDecode /DecodeParms << /Predictor 12 /Columns 250000000 >>"
        assertEquals(expected, PdfOutline.read(withXrefStream(objects, dict, deflate(ByteArray(64)))))
        assertEquals(0, PdfObjects.unpredict(ByteArray(64), colors = 4, bits = 16, columns = Int.MAX_VALUE).size)
        // One byte of type and seven of row: exactly one row comes out.
        assertEquals(7, PdfObjects.unpredict(ByteArray(8), colors = 1, bits = 8, columns = 7).size)
    }

    /** An object stream of a few KB inflating to more values than the budget: as objects they would fill the heap. */
    @Test(timeout = 20_000)
    fun moreValuesThanTheBudgetAreNotRead() {
        fun outlinesWithJunk(count: Int) = objects.toSortedMap().apply {
            this[6] = "<< /Type /Outlines /First 7 0 R /Last 12 0 R /Count 5 /Junk [${" 1 0 R".repeat(count)}] >>"
        }
        val bomb = compressedPdf(outlinesWithJunk(PdfObjects.MAX_VALUES))
        assertTrue(bomb.size < 100_000)
        assertTrue(PdfOutline.read(bomb).isEmpty())
        // A large but sane object still reads.
        assertEquals(expected, PdfOutline.read(compressedPdf(outlinesWithJunk(20_000))))
    }

    // --- builders -------------------------------------------------------------------------------

    /** The objects with no table of their own, and startxref pointing at a cross-reference stream of [dict] over [data]. */
    private fun withXrefStream(objects: Map<Int, String>, dict: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        w("%PDF-1.5\n")
        for ((num, body) in objects) w("$num 0 obj\n$body\nendobj\n")
        val xref = out.size()
        w("30 0 obj\n<< /Type /XRef $dict /Length ${data.size} >>\nstream\n")
        out.write(data)
        w("\nendstream\nendobj\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    private fun classicPdf(objects: Map<Int, String>): ByteArray {
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        w("%PDF-1.4\n")
        val offsets = HashMap<Int, Int>()
        for ((num, body) in objects) {
            offsets[num] = out.size()
            w("$num 0 obj\n$body\nendobj\n")
        }
        val xref = out.size()
        val max = objects.keys.max()
        w("xref\n0 ${max + 1}\n0000000000 65535 f \n")
        for (n in 1..max) {
            val o = offsets[n]
            w(if (o == null) "0000000000 65535 f \n" else String.format("%010d 00000 n \n", o))
        }
        w("trailer\n<< /Size ${max + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    /** Every object in one Flate object stream (20), found through a PNG-Up-predicted cross-reference stream (21). */
    private fun compressedPdf(objects: Map<Int, String>): ByteArray {
        val header = StringBuilder()
        val bodies = StringBuilder()
        val index = HashMap<Int, Int>()
        objects.entries.forEachIndexed { i, (num, body) ->
            index[num] = i
            header.append("$num ${bodies.length} ")
            bodies.append(body).append('\n')
        }
        val first = header.length
        val stream = deflate((header.toString() + bodies).toByteArray(Charsets.ISO_8859_1))

        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        w("%PDF-1.5\n")
        val streamOffset = out.size()
        w("20 0 obj\n<< /Type /ObjStm /N ${objects.size} /First $first /Length ${stream.size} /Filter /FlateDecode >>\nstream\n")
        out.write(stream)
        w("\nendstream\nendobj\n")
        val xrefOffset = out.size()

        val size = 22
        val rows = ArrayList<ByteArray>()
        for (num in 0 until size) {
            val row = ByteArray(7)
            when {
                num in objects -> { row[0] = 2; put(row, 1, 4, 20L); put(row, 5, 2, index.getValue(num).toLong()) }
                num == 20 -> { row[0] = 1; put(row, 1, 4, streamOffset.toLong()) }
                num == 21 -> { row[0] = 1; put(row, 1, 4, xrefOffset.toLong()) }
                else -> row[0] = 0
            }
            rows += row
        }
        val predicted = ByteArrayOutputStream()
        var previous = ByteArray(7)
        for (row in rows) {
            predicted.write(2) // PNG "Up"
            for (j in row.indices) predicted.write((row[j] - previous[j]) and 0xff)
            previous = row
        }
        val xrefData = deflate(predicted.toByteArray())
        w("21 0 obj\n<< /Type /XRef /Size $size /W [1 4 2] /Root 1 0 R /Filter /FlateDecode /DecodeParms << /Predictor 12 /Columns 7 >> /Length ${xrefData.size} >>\nstream\n")
        out.write(xrefData)
        w("\nendstream\nendobj\nstartxref\n$xrefOffset\n%%EOF\n")
        return out.toByteArray()
    }

    private fun put(row: ByteArray, at: Int, width: Int, value: Long) {
        for (j in 0 until width) row[at + j] = (value shr (8 * (width - 1 - j))).toByte()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }
}
