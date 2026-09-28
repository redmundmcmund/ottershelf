package io.github.ottershelf.feature.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISBN parsing, validation and conversion. The pairs are the textbook ones: 0-306-40615-2 /
 * 978-0-306-40615-7, 0-8044-2957-X / 978-0-8044-2957-3 (a check digit of ten), and
 * 979-10-90636-07-1 (a 979 code has no ISBN-10).
 */
class IsbnTest {

    private val plain = Isbn("9780306406157", "0306406152")
    private val withX = Isbn("9780804429573", "080442957X")
    private val nine79 = Isbn("9791090636071", null)

    // --- barcodes -------------------------------------------------------------------------------

    @Test
    fun aBooklandBarcodeIsAnIsbnWithItsIsbn10() {
        assertEquals(plain, Isbn.fromBarcode("9780306406157"))
        assertEquals(withX, Isbn.fromBarcode("9780804429573"))
    }

    @Test
    fun a979BarcodeHasNoIsbn10() {
        assertEquals(nine79, Isbn.fromBarcode("9791090636071"))
    }

    @Test
    fun aWrongCheckDigitIsRejected() {
        assertNull(Isbn.fromBarcode("9780306406158"))
        assertNull(Isbn.fromBarcode("9780306406150"))
    }

    @Test
    fun otherBarcodesAreNotIsbns() {
        assertNull(Isbn.fromBarcode("4006381333931")) // a valid EAN-13, not a book
        assertNull(Isbn.fromBarcode("9771234567003")) // a magazine (ISSN, 977)
        assertNull(Isbn.fromBarcode("0012345678905")) // a UPC-A read as EAN-13
        assertNull(Isbn.fromBarcode("978030640615")) // 12 digits
        assertNull(Isbn.fromBarcode("97803064061570")) // 14
        assertNull(Isbn.fromBarcode("978-0306406157"))
        assertNull(Isbn.fromBarcode(""))
        assertNull(Isbn.fromBarcode(null))
    }

    @Test
    fun aPriceAddOnAfterTheCodeIsDropped() {
        assertEquals(plain, Isbn.fromBarcode("978030640615751299")) // five-digit add-on
        assertEquals(plain, Isbn.fromBarcode("97803064061570" + "5")) // two-digit add-on
        assertNull(Isbn.fromBarcode("978030640615851299")) // the code itself still has to check out
    }

    // --- one frame's barcodes (BarcodeCamera's analyser) ------------------------------------------

    @Test
    fun theIsbnBesideAUpcAPriceCodeIsTheOneTaken() {
        // zxing-cpp, asked for EAN-13 only, reports a UPC-A as its 13-digit EAN form (leading 0).
        assertEquals(plain, Isbn.firstIn(listOf("0071000220071", "9780306406157")))
        assertEquals(plain, Isbn.firstIn(listOf("9780306406157", "0071000220071")))
    }

    @Test
    fun aFrameWithoutAnIsbnHasNone() {
        assertNull(Isbn.firstIn(emptyList()))
        assertNull(Isbn.firstIn(listOf("0036000291452"))) // a UPC-A price code alone
        assertNull(Isbn.firstIn(listOf("9770317847001", null, ""))) // a magazine's ISSN, no text
        assertNull(Isbn.firstIn(listOf("9780306406158"))) // a misread: wrong check digit
    }

    @Test
    fun theFirstIsbnInAFrameIsTaken() {
        assertEquals(nine79, Isbn.firstIn(listOf("9770317847001", "9791090636071", "9780306406157")))
        assertEquals(plain, Isbn.firstIn(listOf("9780306406158", "978030640615751299")))
    }

    // --- conversion -----------------------------------------------------------------------------

    @Test
    fun isbn10AndIsbn13ConvertBothWays() {
        assertEquals(plain, Isbn.of10("0306406152"))
        assertEquals(withX, Isbn.of10("080442957X"))
        assertEquals(withX, Isbn.of10("080442957x"))
        assertEquals("0306406152", Isbn.toIsbn10("9780306406157"))
        assertEquals("080442957X", Isbn.toIsbn10("9780804429573"))
        // Round trip over a spread of real ISBN-10s.
        for (ten in listOf("0140449132", "0571094910", "1400079985", "0747532699", "043942089X", "0198534531")) {
            val isbn = Isbn.of10(ten)!!
            assertEquals(ten, isbn.isbn10)
            assertEquals(isbn, Isbn.of13(isbn.isbn13))
        }
    }

    @Test
    fun checkDigits() {
        assertEquals('7', Isbn.ean13Check("978030640615"))
        assertEquals('3', Isbn.ean13Check("978080442957"))
        assertEquals('0', Isbn.ean13Check("978030640614")) // a sum already a multiple of ten
        assertEquals('2', Isbn.isbn10Check("030640615"))
        assertEquals('X', Isbn.isbn10Check("080442957"))
    }

    @Test
    fun of13And10RejectWhatIsNotAnIsbn() {
        assertNull(Isbn.of13("9780306406158"))
        assertNull(Isbn.of13("4006381333931"))
        assertNull(Isbn.of13("97803064061"))
        assertNull(Isbn.of10("0306406153"))
        assertNull(Isbn.of10("X306406152"))
        assertNull(Isbn.of10("030640615"))
    }

    // --- typed ----------------------------------------------------------------------------------

    @Test
    fun typedIsbnsWithSeparatorsAndLabels() {
        val valid = IsbnInput.Valid(plain)
        assertEquals(valid, Isbn.parse("978-0-306-40615-7"))
        assertEquals(valid, Isbn.parse("  978 0 306 40615 7 "))
        assertEquals(valid, Isbn.parse("978–0–306–40615–7")) // en dashes, as pasted
        assertEquals(valid, Isbn.parse("0-306-40615-2"))
        assertEquals(valid, Isbn.parse("ISBN 0-306-40615-2"))
        assertEquals(valid, Isbn.parse("ISBN-13: 978-0-306-40615-7"))
        assertEquals(valid, Isbn.parse("isbn:9780306406157"))
        assertEquals(valid, Isbn.parse("ISBN 13 978 0306406157"))
        assertEquals(IsbnInput.Valid(withX), Isbn.parse("0-8044-2957-x"))
        assertEquals(IsbnInput.Valid(nine79), Isbn.parse("979-10-90636-07-1"))
        // An ISBN-10 that starts with 1-0 or 1-3 isn't taken for the label's "10" or "13".
        assertEquals(IsbnInput.Valid(Isbn.of10("1306406153")!!), Isbn.parse("ISBN 1306406153"))
        assertEquals(IsbnInput.Valid(Isbn.of10("1400079985")!!), Isbn.parse("ISBN 1-4000-7998-5"))
        assertEquals(IsbnInput.Valid(Isbn.of10("1400079985")!!), Isbn.parse("ISBN-10: 1-4000-7998-5"))
    }

    @Test
    fun typedProblems() {
        assertEquals(IsbnInput.Empty, Isbn.parse(""))
        assertEquals(IsbnInput.Empty, Isbn.parse("   "))
        assertEquals(IsbnInput.Empty, Isbn.parse("ISBN "))
        assertEquals(IsbnInput.Incomplete(9), Isbn.parse("978-030-640"))
        assertEquals(IsbnInput.Incomplete(11), Isbn.parse("97803064061"))
        assertEquals(IsbnInput.TooLong, Isbn.parse("97803064061570"))
        assertEquals(IsbnInput.BadCharacter, Isbn.parse("978-0-306-4061a-7"))
        assertEquals(IsbnInput.BadCharacter, Isbn.parse("978/0306406157"))
        assertEquals(IsbnInput.MisplacedX, Isbn.parse("X306406152"))
        assertEquals(IsbnInput.MisplacedX, Isbn.parse("97803064061X7"))
        assertEquals(IsbnInput.MisplacedX, Isbn.parse("030640X"))
        assertEquals(IsbnInput.NotABook, Isbn.parse("4006381333931"))
        assertEquals(IsbnInput.BadCheckDigit(final = true), Isbn.parse("978-0-306-40615-8"))
        assertEquals(IsbnInput.BadCheckDigit(final = true), Isbn.parse("0-306-40615-3"))
    }

    @Test
    fun tenDigitsStartingLikeAnIsbn13AreNotFlaggedWhileTyping() {
        // "9780306406" is on its way to 978-0-306-40615-7, and isn't a valid ISBN-10 itself.
        val partial = Isbn.parse("9780306406")
        assertEquals(IsbnInput.BadCheckDigit(final = false), partial)
        assertFalse(partial.showWhileTyping)
        assertTrue(Isbn.parse("0306406153").showWhileTyping)
        assertTrue(Isbn.parse("12a").showWhileTyping)
        assertTrue(Isbn.parse("40063813339311").showWhileTyping) // too long
        assertFalse(Isbn.parse("97803").showWhileTyping)
    }
}
