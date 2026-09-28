package io.github.ottershelf.feature.reader.lookup

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupTextTest {

    @Test
    fun cleanStripsSurroundingPunctuationAndQuotes() {
        assertEquals("Serendipity", LookupText.clean("“Serendipity,”"))
        assertEquals("Lestrade", LookupText.clean("(Lestrade)"))
        assertEquals("don't", LookupText.clean("'don't'"))
        assertEquals("well-being", LookupText.clean("—well-being—"))
        assertEquals("kick the bucket", LookupText.clean("  kick\nthe   bucket! "))
        assertEquals("Qué", LookupText.clean("¿Qué?"))
        assertEquals("", LookupText.clean(" …! "))
    }

    @Test
    fun cleanKeepsTheMarksOnAWordsLastLetter() {
        // Hindi's last vowel sign (U+0940, a spacing mark) and Thai's vowel and tone mark (U+0E35 U+0E48).
        assertEquals("हिंदी", LookupText.clean("“हिंदी”,"))
        assertEquals("हिंदी", LookupText.clean("हिंदी।"))
        assertEquals("ที่", LookupText.clean("ที่"))
        assertEquals("ที่", LookupText.clean("(ที่)"))
        // A decomposed accent is composed first: the word looked up is the one Wiktionary has.
        assertEquals("café", LookupText.clean("café,"))
        assertEquals("café", LookupText.display("café"))
        // A letter beyond the BMP (a surrogate pair) is a letter too.
        assertEquals("𠮷野家", LookupText.clean("「𠮷野家」"))
        assertEquals("野家𠮷", LookupText.clean("野家𠮷。"))
    }

    @Test
    fun cleanKeepsAnAbbreviationsLastFullStop() {
        assertEquals("A.I.", LookupText.clean("A.I."))
        assertEquals("U.S.", LookupText.clean("U.S.,"))
        assertEquals("Hello", LookupText.clean("Hello."))
    }

    @Test
    fun displayCollapsesWhitespaceAndDropsInvisibles() {
        assertEquals("seren dipity", LookupText.display("seren\n dipity"))
        assertEquals("serendipity", LookupText.display("seren${Char(0xAD)}dip${Char(0x200B)}ity"))
    }

    @Test
    fun wordCountIgnoresLoosePunctuation() {
        assertEquals(1, LookupText.wordCount("serendipity"))
        assertEquals(3, LookupText.wordCount("kick — the bucket"))
        assertEquals(17, LookupText.wordCount("It is a capital mistake to theorize before you have all the evidence. It biases the judgment."))
        assertEquals(0, LookupText.wordCount(" … "))
    }

    @Test
    fun wordCountInScriptsWithoutSpaces() {
        // A word or a name: the Dictionary (up to 5) and Wikipedia (up to 12).
        assertEquals(3, LookupText.wordCount("图书馆"))
        assertEquals(1, LookupText.wordCount("コーヒー")) // the length mark belongs to the kana before it
        assertEquals(2, LookupText.wordCount("สวัสดี")) // four letters, two vowel marks
        assertEquals(8, LookupText.wordCount("シャーロック・ホームズの冒険"))
        assertEquals(2, LookupText.wordCount("iPhone的"))
        // A sentence or a paragraph without a space is no longer one word, so it isn't sent.
        assertTrue(LookupText.wordCount("我们在图书馆里读了一整个下午的书，然后一起去吃晚饭。") > LookupText.MAX_WIKIPEDIA_WORDS)
        assertTrue(LookupText.wordCount("吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。") > LookupText.MAX_WIKIPEDIA_WORDS)
        assertTrue(LookupText.wordCount("ภาษาไทยเป็นภาษาที่มีระดับเสียงของคำแน่นอนหรือวรรณยุกต์เช่นเดียวกับภาษาจีน") > LookupText.MAX_WIKIPEDIA_WORDS)
    }

    @Test
    fun inflectionsUndoSimpleEnglishEndings() {
        assertEquals(listOf("berry", "berrie"), LookupText.inflections("berries"))
        assertEquals(listOf("box", "boxe"), LookupText.inflections("boxes"))
        assertEquals(listOf("book"), LookupText.inflections("books"))
        assertEquals(listOf("stop", "stopp", "stoppe"), LookupText.inflections("stopped"))
        assertEquals(listOf("bak", "bake"), LookupText.inflections("baked"))
        assertEquals(listOf("call", "calle"), LookupText.inflections("called"))
        assertEquals(listOf("carry"), LookupText.inflections("carried"))
        assertEquals(listOf("run", "runn", "runne"), LookupText.inflections("running"))
        assertEquals(listOf("mak", "make"), LookupText.inflections("making"))
        assertEquals(listOf("big", "bigg", "bigge"), LookupText.inflections("bigger"))
        assertEquals(listOf("happy"), LookupText.inflections("happier"))
        assertEquals(listOf("happy"), LookupText.inflections("happiest"))
        assertEquals(listOf("lat", "late"), LookupText.inflections("latest"))
        assertEquals(listOf("lestrade"), LookupText.inflections("Lestrade's"))
        assertEquals(listOf("lestrade"), LookupText.inflections("lestrade’s"))
    }

    @Test
    fun inflectionsLeaveAloneWhatIsNoInflection() {
        assertTrue(LookupText.inflections("bus").isEmpty())
        assertTrue(LookupText.inflections("glass").isEmpty())
        assertTrue(LookupText.inflections("crisis").isEmpty())
        assertTrue(LookupText.inflections("status").isEmpty())
        assertTrue(LookupText.inflections("ran").isEmpty())
        assertTrue(LookupText.inflections("kick the").isEmpty())
        assertTrue(LookupText.inflections("A1B2s").isEmpty())
    }

    @Test
    fun languageNormalisesTheBookMetadata() {
        assertEquals("en", LookupText.language(null))
        assertEquals("en", LookupText.language(""))
        assertEquals("en", LookupText.language("en-GB"))
        assertEquals("en", LookupText.language("eng"))
        assertEquals("en", LookupText.language("English"))
        assertEquals("fr", LookupText.language("fr_FR"))
        assertEquals("fr", LookupText.language("Français"))
        assertEquals("fr", LookupText.language("fre"))
        assertEquals("de", LookupText.language("ger"))
        assertEquals("de", LookupText.language("de; en"))
        assertEquals("ga", LookupText.language("gle"))
        assertEquals("mt", LookupText.language("mt"))
        assertEquals("en", LookupText.language("Lojban"))
        assertEquals("en", LookupText.language("x-unknown-1"))
        // Norwegian: Bokmål is no.wikipedia's; Nynorsk has its own. Serbo-Croatian's parts keep theirs.
        assertEquals("no", LookupText.language("nb-NO"))
        assertEquals("no", LookupText.language("nob"))
        assertEquals("no", LookupText.language("Bokmål"))
        assertEquals("nn", LookupText.language("nno"))
        assertEquals("hr", LookupText.language("hrv"))
        assertEquals("bs", LookupText.language("Bosnian"))
        assertEquals("sh", LookupText.language("hbs"))
        assertEquals("nn", LookupText.wikipediaLanguage("nn"))
        assertEquals("sh", LookupText.wikipediaLanguage("sh"))
    }

    @Test
    fun wikipediaOnlyForLanguagesThatHaveASizeableOne() {
        assertEquals("de", LookupText.wikipediaLanguage("de"))
        assertEquals("ga", LookupText.wikipediaLanguage("ga"))
        assertEquals("en", LookupText.wikipediaLanguage("jbo"))
        assertEquals("en", LookupText.wikipediaLanguage("en"))
    }

    // --- HTML -------------------------------------------------------------------------------------

    @Test
    fun htmlKeepsTextBoldAndItalicOnly() {
        val s = HtmlText.toAnnotated(
            "The <a rel=\"mw:WikiLink\" href=\"/wiki/phenomenon\" title=\"phenomenon\">phenomenon</a> of <b>making</b> an <i>unplanned</i>, fortunate discovery.",
        )
        assertEquals("The phenomenon of making an unplanned, fortunate discovery.", s.text)
        val bold = s.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("making", s.text.substring(bold.start, bold.end))
        val italic = s.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals("unplanned", s.text.substring(italic.start, italic.end))
    }

    @Test
    fun htmlDropsStylesScriptsCommentsAndVoidTags() {
        val s = HtmlText.toAnnotated(
            "<style data-mw-deduplicate=\"x\">.mw-parser-output .x{color:red}</style>A <!-- note --> sense<link rel=\"mw:PageProp/Category\" href=\"./Category:X\"><script>alert(1)</script>.",
        )
        assertEquals("A sense.", s.text)
    }

    @Test
    fun htmlDecodesEntitiesAndCollapsesWhitespace() {
        assertEquals("fish & chips — “now” 'ok' x é", HtmlText.toPlain("fish &amp; chips &mdash; &ldquo;now&rdquo; &#39;ok&#39;&nbsp;\n  x &#xE9;"))
        assertEquals("a < b", HtmlText.toPlain("a &lt; b"))
        assertEquals("&bogus; stays", HtmlText.toPlain("&bogus; stays"))
        assertEquals("one two", HtmlText.toPlain("<p>one</p><p>two</p>"))
        assertEquals("", HtmlText.toPlain("<span class=\"usage-label-sense\"></span> "))
    }

    @Test
    fun htmlTrailingSpaceIsCutWithItsSpan() {
        val s = HtmlText.toAnnotated("plural of <i>berry </i>")
        assertEquals("plural of berry", s.text)
        val italic = s.spanStyles.single()
        assertEquals(s.text.length, italic.end)
    }

    @Test
    fun htmlStrayAngleBracketIsText() {
        assertEquals("3 < 4", HtmlText.toPlain("3 < 4"))
    }
}
