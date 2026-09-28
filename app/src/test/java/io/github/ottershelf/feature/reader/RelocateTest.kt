package io.github.ottershelf.feature.reader

import io.github.ottershelf.core.network.ApiJson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page's relocates as reader.js sends them. Plain JVM. */
class RelocateTest {

    private fun decode(json: String) = ApiJson.decodeFromString(Relocate.serializer(), json)

    @Test
    fun onlyTheUsersOwnMovesCountAsATurn() {
        // A page turn, swipe or scroll.
        assertTrue(decode("""{"fraction":0.41,"page":3,"pages":20,"turned":true}""").turned)
        // foliate settling the section the user opened at a highlight: a new fraction, but not a turn.
        assertFalse(decode("""{"fraction":0.43,"page":3,"pages":22,"turned":false}""").turned)
        // The full location (onLocation, readerLocation) doesn't say; it never ends a fresh start.
        assertFalse(decode("""{"cfi":"epubcfi(/6/14!/4/2/1:0)","fraction":0.43,"tocLabel":"One"}""").turned)
    }

    @Test
    fun theBottomOfAScrolledBookIsSaidOutright() {
        // Scrolled to the end of the last section (the phone's test book): the fraction is short of 1.
        val end = decode("""{"fraction":0.9927360435201654,"turned":true,"chapterEnd":false,"bookEnd":true}""")
        assertTrue(end.bookEnd)
        assertFalse(end.chapterEnd)
        // The end of another chapter doesn't say it, nor does the full location.
        assertFalse(decode("""{"fraction":0.6,"turned":true,"chapterEnd":true,"bookEnd":false}""").bookEnd)
        assertFalse(decode("""{"cfi":"epubcfi(/6/14!/4/2/1:0)","fraction":0.99}""").bookEnd)
    }
}
