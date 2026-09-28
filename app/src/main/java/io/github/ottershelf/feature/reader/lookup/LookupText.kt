package io.github.ottershelf.feature.reader.lookup

import java.text.Normalizer

/**
 * What a selection becomes before it is looked up (pure, `LookupTextTest`): whitespace collapsed,
 * surrounding punctuation and quotes stripped, and the words tried when the word as selected has
 * no dictionary entry (lower case, then simple English inflections).
 */
internal object LookupText {

    /** Up to this many words ([wordCount]) the Dictionary tab is offered; a longer selection is a phrase or a passage. */
    const val MAX_DICTIONARY_WORDS = 5

    /** Longer than this ([wordCount]), nothing is sent to Wikipedia either (only the apps get the passage). */
    const val MAX_WIKIPEDIA_WORDS = 12

    private val WHITESPACE = Regex("\\s+")

    /** Soft hyphens, zero-width spaces and joiners, the BOM: invisible in the book, but part of the text. */
    private val INVISIBLE = Regex("[\\u00AD\\u200B\\u200C\\u200D\\u2060\\uFEFF]")

    /** The selection as shown and handed to other apps: one line, single spaces, composed (NFC). */
    fun display(selection: String): String =
        Normalizer.normalize(selection.replace(INVISIBLE, "").replace(WHITESPACE, " ").trim(), Normalizer.Form.NFC)

    /**
     * How many words [text] is, for what may be sent ([MAX_DICTIONARY_WORDS], [MAX_WIKIPEDIA_WORDS]).
     * Spaces separate words, except in scripts written without them (Chinese, Japanese, Thai, Lao,
     * Khmer, Myanmar), where a run of letters counts as several ([LETTERS_PER_WORD]: a Han character
     * is one, two kana, three letters of the others; vowel signs and tone marks aren't letters), so
     * a sentence or a paragraph there isn't one "word" sent whole.
     */
    fun wordCount(text: String): Int = display(text).split(' ').sumOf(::words)

    private fun words(token: String): Int {
        var spaced = false
        var count = 0
        var run: Character.UnicodeScript? = null
        var letters = 0
        fun endRun() {
            val per = run?.let { LETTERS_PER_WORD[it] }
            if (per != null) count += (letters + per - 1) / per
            run = null
            letters = 0
        }
        var i = 0
        while (i < token.length) {
            val cp = token.codePointAt(i)
            i += Character.charCount(cp)
            val script = Character.UnicodeScript.of(cp)
            when {
                // A mark, or a length mark (the katakana ー), belongs to the letters before it.
                isMark(cp) || (Character.getType(cp) == Character.MODIFIER_LETTER.toInt() && script == Character.UnicodeScript.COMMON) -> {}
                !Character.isLetterOrDigit(cp) -> endRun()
                script in LETTERS_PER_WORD -> {
                    if (script != run) endRun()
                    run = script
                    letters++
                }
                else -> {
                    endRun()
                    spaced = true
                }
            }
        }
        endRun()
        return count + if (spaced) 1 else 0
    }

    /** Scripts written without spaces between words, and how many of their letters make a word here (erring long). */
    private val LETTERS_PER_WORD = mapOf(
        Character.UnicodeScript.HAN to 1,
        Character.UnicodeScript.HIRAGANA to 2,
        Character.UnicodeScript.KATAKANA to 2,
        Character.UnicodeScript.THAI to 3,
        Character.UnicodeScript.LAO to 3,
        Character.UnicodeScript.KHMER to 3,
        Character.UnicodeScript.MYANMAR to 3,
    )

    /**
     * The term to look up: [display], without the punctuation and quotes around it
     * (`“Serendipity,”` is `Serendipity`, `(Lestrade)` is `Lestrade`). Inner punctuation stays
     * (`don't`, `well-being`, `A.I.`: a trailing full stop after a capital is kept), and so do the
     * marks on a word's last letter (a Hindi or Thai vowel sign, a tone mark, a combining accent).
     */
    fun clean(selection: String): String {
        val text = display(selection)
        var start = 0
        var end = text.length
        while (start < end) {
            val cp = text.codePointAt(start)
            if (isWordPart(cp)) break
            start += Character.charCount(cp)
        }
        while (end > start) {
            val cp = text.codePointBefore(end)
            if (isWordPart(cp)) break
            end -= Character.charCount(cp)
        }
        // "A.I." keeps its last full stop: an abbreviation, not the end of a sentence.
        if (end < text.length && text[end] == '.' && end - start >= 3 && text[end - 2] == '.') end++
        return text.substring(start, end)
    }

    /** A letter or a digit (by code point, so beyond the BMP too), or a mark on one. */
    private fun isWordPart(cp: Int): Boolean = Character.isLetterOrDigit(cp) || isMark(cp)

    /** Unicode's combining marks: Hindi's vowel signs (`ी`), Thai's vowels and tone marks (`ี่`), combining accents. */
    private fun isMark(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    /**
     * Simple English inflections of [word] (lower case), most likely first: plurals and third
     * persons (-s, -es, -ies, 's), past forms (-ed, -ied), -ing, comparatives and superlatives
     * (-er, -est, -ier, -iest), with a doubled final consonant undone (`stopped`, `bigger`) and a
     * silent e put back (`making`, `later`). Only for a single word of letters; nothing shorter
     * than two letters is proposed.
     */
    fun inflections(word: String): List<String> {
        val w = word.lowercase()
        if (w.length < 4 || !w.all { it.isLetter() || it == '\'' || it == '’' }) return emptyList()
        val out = LinkedHashSet<String>()
        fun stem(s: String) {
            if (s.length >= 2 && s != w && s.any { it in VOWELS || it == 'y' }) out += s
        }
        fun undouble(s: String) {
            if (s.length >= 3 && s[s.length - 1] == s[s.length - 2] && s.last() !in VOWELS && s.last() !in "lsz") stem(s.dropLast(1))
        }
        when {
            w.endsWith("'s") || w.endsWith("’s") -> stem(w.dropLast(2))
            w.endsWith("ies") -> { stem(w.dropLast(3) + "y"); stem(w.dropLast(1)) }
            w.endsWith("es") -> { stem(w.dropLast(2)); stem(w.dropLast(1)) }
            w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") -> stem(w.dropLast(1))
            w.endsWith("ied") -> stem(w.dropLast(3) + "y")
            w.endsWith("ed") -> { undouble(w.dropLast(2)); stem(w.dropLast(2)); stem(w.dropLast(1)) }
            w.endsWith("ing") -> { undouble(w.dropLast(3)); stem(w.dropLast(3)); stem(w.dropLast(3) + "e") }
            w.endsWith("iest") -> stem(w.dropLast(4) + "y")
            w.endsWith("est") -> { undouble(w.dropLast(3)); stem(w.dropLast(3)); stem(w.dropLast(2)) }
            w.endsWith("ier") -> stem(w.dropLast(3) + "y")
            w.endsWith("er") -> { undouble(w.dropLast(2)); stem(w.dropLast(2)); stem(w.dropLast(1)) }
        }
        return out.toList()
    }

    private const val VOWELS = "aeiou"

    // --- languages -------------------------------------------------------------------------------

    /**
     * The book's metadata language as a language code: `en`, `eng`, `English`, `en-GB`, `fr_FR`
     * and the like (the web reader's `normalizeLang`, plus a few native names); any other two or
     * three letter code as it is; nothing usable is English. The codes are Wikipedia's (`no` is
     * Bokmål); where en.wiktionary files a language otherwise (Bokmål as `nb`, Croatian, Serbian
     * and Bosnian as Serbo-Croatian) [WikimediaParsing.preferred] matches its headers.
     */
    fun language(raw: String?): String {
        val primary = raw?.trim()?.lowercase()?.split(';', ',', '/', '|')?.firstOrNull()?.trim().orEmpty()
        if (primary.isEmpty()) return "en"
        val base = primary.split('-', '_').first().trim()
        LANGUAGE_ALIASES[base]?.let { return it }
        return if (CODE.matches(base)) base else "en"
    }

    private val CODE = Regex("[a-z]{2,3}")

    /** The Wikipedia for [code] ([language]'s result), or English when there's no sizeable one. */
    fun wikipediaLanguage(code: String): String = if (code in WIKIPEDIAS) code else "en"

    private val LANGUAGE_ALIASES: Map<String, String> = buildMap {
        fun add(code: String, vararg names: String) {
            put(code, code)
            names.forEach { put(it, code) }
        }
        add("ar", "ara", "arabic")
        add("ca", "cat", "catalan", "català")
        add("cs", "ces", "cze", "czech", "čeština")
        add("cy", "cym", "wel", "welsh", "cymraeg")
        add("da", "dan", "danish", "dansk")
        add("de", "deu", "ger", "german", "deutsch")
        add("el", "ell", "gre", "greek")
        add("en", "eng", "english")
        add("eo", "epo", "esperanto")
        add("es", "spa", "spanish", "español", "espanol")
        add("et", "est", "estonian", "eesti")
        add("eu", "eus", "baq", "basque", "euskara")
        add("fa", "fas", "per", "persian", "farsi")
        add("fi", "fin", "finnish", "suomi")
        add("fr", "fra", "fre", "french", "français", "francais")
        add("ga", "gle", "irish", "gaeilge")
        add("gd", "gla", "gaelic", "gàidhlig")
        add("gl", "glg", "galician", "galego")
        add("he", "heb", "hebrew")
        add("hi", "hin", "hindi")
        add("hr", "hrv", "croatian", "hrvatski")
        add("hu", "hun", "hungarian", "magyar")
        add("id", "ind", "indonesian")
        add("is", "isl", "ice", "icelandic", "íslenska")
        add("it", "ita", "italian", "italiano")
        add("ja", "jpn", "japanese")
        add("ko", "kor", "korean")
        add("la", "lat", "latin", "latina")
        add("lt", "lit", "lithuanian")
        add("lv", "lav", "latvian")
        add("nl", "nld", "dut", "dutch", "nederlands")
        // Bokmål (the no.wikipedia's) and Nynorsk; Wiktionary's names for them: WikimediaParsing.preferred.
        add("no", "nor", "nob", "nb", "norwegian", "norsk", "bokmål", "bokmal")
        add("nn", "nno", "nynorsk")
        add("pl", "pol", "polish", "polski")
        add("pt", "por", "portuguese", "português", "portugues")
        add("ro", "ron", "rum", "romanian", "română")
        add("ru", "rus", "russian")
        add("sk", "slk", "slo", "slovak")
        add("sl", "slv", "slovenian", "slovene")
        add("sr", "srp", "serbian")
        add("bs", "bos", "bosnian")
        add("sh", "hbs") // Serbo-Croatian, as Wiktionary files Croatian, Serbian and Bosnian
        add("sv", "swe", "swedish", "svenska")
        add("th", "tha", "thai")
        add("tr", "tur", "turkish", "türkçe")
        add("uk", "ukr", "ukrainian")
        add("vi", "vie", "vietnamese")
        add("zh", "zho", "chi", "chinese")
    }

    /** The Wikipedias large enough to be worth asking before English (every alias above has one). */
    private val WIKIPEDIAS: Set<String> = LANGUAGE_ALIASES.values.toSet() + setOf("af", "be", "bg", "bn", "hy", "ka", "kk", "mk", "ms", "sq", "ta", "ur", "uz")
}
