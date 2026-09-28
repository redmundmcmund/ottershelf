package io.github.ottershelf.feature.scan

/**
 * A book's ISBN, as the scanner found it or the user typed it: always the ISBN-13, plus the ISBN-10 for
 * a 978 code (979 codes have none). Both are digits only (an ISBN-10 may end in `X`), as the
 * server stores them (`book_metadata.isbn13` / `.isbn10`: varchar(13) / varchar(10), filled with
 * separators stripped by its parsers). Pure, so the rules are unit-tested (`IsbnTest`).
 */
data class Isbn(val isbn13: String, val isbn10: String?) {

    companion object {
        /**
         * The ISBN an EAN-13 barcode carries, or null for any other barcode: a price code, a
         * magazine's 977, a shop's own label, a misread with a wrong check digit. Only Bookland codes
         * (978, 979) are ISBNs. A 2- or 5-digit add-on after the 13 digits (the price on many
         * paperbacks) is dropped, should the reader report it.
         */
        fun fromBarcode(raw: String?): Isbn? {
            val digits = raw?.trim().orEmpty()
            if (digits.isEmpty() || !digits.all(Char::isDigit)) return null
            val code = when (digits.length) {
                13 -> digits
                15, 18 -> digits.take(13)
                else -> return null
            }
            return of13(code)
        }

        /**
         * The ISBN among the barcodes one camera frame holds ([codes]: their text, in the order the
         * reader found them), else null: the first that [fromBarcode] takes, so a UPC-A price code
         * (read as a 13-digit code starting with 0) or a magazine's 977 beside or before the book's
         * EAN is passed over, whichever the reader reported first.
         */
        fun firstIn(codes: Iterable<String?>): Isbn? = codes.firstNotNullOfOrNull(::fromBarcode)

        /** [digits] as an ISBN-13 (13 digits, 978 or 979, a valid check digit), else null. */
        fun of13(digits: String): Isbn? {
            if (digits.length != 13 || !digits.all(Char::isDigit)) return null
            if (!digits.startsWith("978") && !digits.startsWith("979")) return null
            if (ean13Check(digits.take(12)) != digits[12]) return null
            return Isbn(digits, if (digits.startsWith("978")) toIsbn10(digits) else null)
        }

        /** [value] as an ISBN-10 (9 digits and a check digit, `X` for ten), else null. */
        fun of10(value: String): Isbn? {
            val v = value.uppercase()
            if (v.length != 10 || !v.take(9).all(Char::isDigit)) return null
            if (isbn10Check(v.take(9)) != v[9]) return null
            val body = "978" + v.take(9)
            return Isbn(body + ean13Check(body), v)
        }

        /**
         * What the user typed, checked: an ISBN-10 or ISBN-13 with any hyphens and spaces (as printed
         * inside a book or on a receipt), optionally after "ISBN", "ISBN:" or "ISBN-13:".
         */
        fun parse(input: String): IsbnInput {
            val text = LABEL.replace(input.trim(), "")
            if (text.isBlank()) return IsbnInput.Empty
            val compact = StringBuilder()
            for (c in text) {
                when {
                    c in '0'..'9' -> compact.append(c)
                    c == 'x' || c == 'X' -> compact.append('X')
                    c.isWhitespace() || c in SEPARATORS -> Unit
                    else -> return IsbnInput.BadCharacter
                }
            }
            val s = compact.toString()
            if (s.isEmpty()) return IsbnInput.Empty
            // Only an ISBN-10's check digit, the tenth character, can be X.
            val x = s.indexOf('X')
            if (x >= 0 && !(s.length == 10 && x == 9)) return IsbnInput.MisplacedX
            return when {
                s.length < 10 -> IsbnInput.Incomplete(s.length)
                s.length == 10 -> of10(s)?.let(IsbnInput::Valid)
                    // Could be the start of a 13-digit one: only final once it can't be.
                    ?: IsbnInput.BadCheckDigit(final = !s.startsWith("978") && !s.startsWith("979"))
                s.length < 13 -> IsbnInput.Incomplete(s.length)
                s.length == 13 -> when {
                    !s.startsWith("978") && !s.startsWith("979") -> IsbnInput.NotABook
                    else -> of13(s)?.let(IsbnInput::Valid) ?: IsbnInput.BadCheckDigit(final = true)
                }
                else -> IsbnInput.TooLong
            }
        }

        /** The ISBN-10 of a 978 ISBN-13 (its middle nine digits and their own check digit). */
        internal fun toIsbn10(isbn13: String): String {
            val body = isbn13.substring(3, 12)
            return body + isbn10Check(body)
        }

        /** EAN-13's check digit for 12 digits: weights 1, 3, 1, 3..., rounded up to ten. */
        internal fun ean13Check(twelve: String): Char {
            val sum = twelve.withIndex().sumOf { (i, c) -> (c - '0') * if (i % 2 == 0) 1 else 3 }
            return '0' + (10 - sum % 10) % 10
        }

        /**
         * ISBN-10's check digit for 9 digits: the one that makes 1*d1 + 2*d2 + ... + 10*d10 a
         * multiple of 11, which is 1*d1 + ... + 9*d9 mod 11 (`X` for ten), as the server checks it.
         */
        internal fun isbn10Check(nine: String): Char {
            val sum = nine.withIndex().sumOf { (i, c) -> (i + 1) * (c - '0') }
            val check = sum % 11
            return if (check == 10) 'X' else '0' + check
        }

        /** "ISBN", "ISBN:", "ISBN-10:", "ISBN 13" before the number (not the "13" an ISBN-10 starts with). */
        private val LABEL = Regex("^isbn(?:[\\s-]*1[03](?!\\d))?\\s*:?\\s*", RegexOption.IGNORE_CASE)

        /** Hyphens and dashes as printed (and pasted) between the groups. */
        private const val SEPARATORS = "-‐‑‒–—."
    }
}

/** A typed ISBN, checked ([Isbn.parse]). */
sealed interface IsbnInput {
    data object Empty : IsbnInput

    /** Not enough digits yet (fewer than 10, or 11 or 12 on the way to 13). */
    data class Incomplete(val digits: Int) : IsbnInput

    /** More than 13 digits. */
    data object TooLong : IsbnInput

    /** Something other than digits, hyphens, spaces and an ISBN-10's final X. */
    data object BadCharacter : IsbnInput

    /** An X anywhere but as an ISBN-10's last character. */
    data object MisplacedX : IsbnInput

    /** Thirteen digits, but not starting 978 or 979: not a book's number. */
    data object NotABook : IsbnInput

    /**
     * The last digit doesn't match the others: a typo. [final] false for ten digits that could
     * still be the start of an ISBN-13 (978..., 979...), so it isn't flagged while the user types.
     */
    data class BadCheckDigit(val final: Boolean) : IsbnInput

    data class Valid(val isbn: Isbn) : IsbnInput

    /** Worth saying while the user types: it won't become an ISBN by typing more. */
    val showWhileTyping: Boolean
        get() = when (this) {
            TooLong, BadCharacter, MisplacedX, NotABook -> true
            is BadCheckDigit -> final
            else -> false
        }
}
