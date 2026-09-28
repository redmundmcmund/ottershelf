package io.github.ottershelf.devicetest

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.feature.scan.Isbn
import io.github.ottershelf.feature.scan.newScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import zxingcpp.BarcodeReader
import java.io.File

/**
 * The ISBN scanner's own zxing-cpp reader ([newScanner]: EAN-13 only, add-ons ignored) and its ISBN
 * rule ([Isbn.firstIn], as BarcodeCamera's analyser applies it to each frame: the first barcode
 * that is an ISBN) on the phone, with barcodes drawn from the standard's tables ([Barcodes]). The
 * pictures are saved to `device-tests/barcodes/` for a look, and each read's time goes to
 * `report.txt`.
 */
@RunWith(AndroidJUnit4::class)
class BarcodeDeviceTest {

    private val scanner = newScanner()

    private class Read(val raw: List<String?>, val formats: List<BarcodeReader.Format>, val isbn: Isbn?)

    /** One frame, as the analyser reads it: zxing-cpp's barcodes, then the first that is an ISBN. */
    private fun read(name: String, bitmap: Bitmap): Read {
        File(Device.outDir, "barcodes/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val start = System.nanoTime()
        val barcodes = scanner.read(bitmap)
        val ms = (System.nanoTime() - start) / 1_000_000
        val read = Read(barcodes.map { it.text }, barcodes.map { it.format }, Isbn.firstIn(barcodes.map { it.text }))
        Device.report("barcode $name (${bitmap.width}x${bitmap.height}): ${ms} ms raw=${read.raw} formats=${read.formats} isbn=${read.isbn}")
        return read
    }

    @Test
    fun aCleanIsbnBarcodeIsRead() {
        val code = "9780306406157"
        val read = read("clean", Barcodes.draw(code, label = "ISBN 978-0-306-40615-7"))
        assertEquals(Isbn("9780306406157", "0306406152"), read.isbn)
        assertEquals(listOf(BarcodeReader.Format.EAN_13), read.formats.distinct())
    }

    @Test
    fun aSmallBlurredTurnedBarcodeIsRead() {
        val code = Barcodes.withCheckDigit("978149207800")
        val picture = Barcodes.degrade(Barcodes.draw(code, module = 4), degrees = 14f, scale = 0.55f, blurPasses = 2)
        val read = read("small_blurred_rotated", picture)
        assertEquals(code, read.isbn?.isbn13)
    }

    @Test
    fun a979IsbnWithoutAnIsbn10IsRead() {
        val code = Barcodes.withCheckDigit("979104690219")
        val read = read("isbn_979", Barcodes.draw(code))
        assertEquals(Isbn(code, null), read.isbn)
    }

    @Test
    fun aMagazinesIssnBarcodeIsIgnored() {
        val code = Barcodes.withCheckDigit("977031784700")
        val read = read("issn_977", Barcodes.draw(code, label = "ISSN 0317-8471"))
        assertTrue("zxing-cpp didn't see the 977 code at all: ${read.raw}", read.raw.any { it?.startsWith("977") == true })
        assertNull(read.isbn)
    }

    @Test
    fun aUpcAPriceCodeIsIgnored() {
        // UPC-A 036000291452 is the EAN-13 0036000291452 (the same bars).
        val upc = Barcodes.withCheckDigit("003600029145")
        val read = read("upc_a", Barcodes.draw(upc))
        assertTrue("zxing-cpp didn't see the UPC-A code at all: ${read.raw}", upc in read.raw)
        assertNull("a UPC-A code was taken for an ISBN: ${read.raw}", read.isbn)
    }

    @Test
    fun anIsbnBesideAUpcAPriceCodeIsTheOneRead() {
        val isbn = "9780306406157"
        val upc = Barcodes.withCheckDigit("007100022007")
        val read = read("isbn_and_upc_a", Barcodes.sideBySide(Barcodes.draw(upc, module = 3), Barcodes.draw(isbn, module = 3)))
        assertEquals(isbn, read.isbn?.isbn13)
    }

    @Test
    fun anIsbnWithAFiveDigitAddOnIsRead() {
        val code = "9780306406157"
        val read = read("isbn_addon5", Barcodes.draw(code, addon = "51299"))
        // The add-on is ignored, not read: the text is the 13 digits alone.
        assertEquals(listOf(code), read.raw)
        assertEquals(Isbn("9780306406157", "0306406152"), read.isbn)
    }
}
