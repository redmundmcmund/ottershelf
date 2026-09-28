package io.github.ottershelf.feature.reader.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wikimedia's answers as captured with curl (app/src/test/resources/lookup, September 2026). */
class WikimediaParsingTest {

    @Test
    fun definitionsAreReadPerLanguageAndPartOfSpeech() {
        val entries = WikimediaParsing.definitions(fixture("def_serendipity.json"))
        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals("en", e.code)
        assertEquals("English", e.language)
        assertEquals("Noun", e.partOfSpeech)
        assertEquals(3, e.senses.size)
        assertEquals(
            "The phenomenon of making an unplanned, fortunate discovery through a combination of unexpected circumstances and insightful recognition.",
            e.senses[0].text.text,
        )
        assertNull(e.senses[0].formOf)
    }

    @Test
    fun examplesAndTheirTranslationsAreKept() {
        val entries = WikimediaParsing.definitions(fixture("def_maison.json"))
        val adjective = entries.first { it.code == "fr" && it.partOfSpeech == "Adjective" }
        val homemade = adjective.senses.first()
        assertEquals("homemade", homemade.text.text)
        assertEquals("une grande tarte maison", homemade.example?.text)
        assertEquals("a big home-made pie", homemade.translation?.text)
        // The headword is bold in the example.
        val bold = homemade.example!!.spanStyles.single()
        assertEquals("maison", homemade.example.text.substring(bold.start, bold.end))
        // An empty usage label before the gloss leaves no stray space.
        assertEquals("in-house", adjective.senses[1].text.text)
    }

    @Test
    fun otherLanguagesKeepTheirNamesUnderOther() {
        val entries = WikimediaParsing.definitions(fixture("def_maison.json"))
        assertEquals(listOf("French", "Middle French", "Spanish"), entries.map { it.language }.distinct())
        assertEquals("other", entries.first { it.language == "Middle French" }.code)
    }

    @Test
    fun formOfDefinitionsNameTheirWord() {
        val walked = WikimediaParsing.definitions(fixture("def_walked.json")).single().senses.single()
        assertEquals("simple past and past participle of walk", walked.text.text)
        assertEquals("walk", walked.formOf)
        val berries = WikimediaParsing.definitions(fixture("def_berries.json"))
        assertEquals(listOf("berry", "berry"), berries.map { it.senses.first().formOf })
        assertEquals("plural of berry", berries.first().senses.first().text.text)
    }

    @Test
    fun formOfIgnoresGlossaryLinks() {
        assertNull(WikimediaParsing.formOf("<a rel=\"mw:WikiLink\" href=\"/wiki/Appendix:Glossary#plural\" title=\"Appendix:Glossary\">plural</a> of berry"))
        assertNull(WikimediaParsing.formOf("<span class=\"form-of-definition-link\"><a title=\"Appendix:Glossary\">x</a></span>"))
        assertEquals("candelabrum", WikimediaParsing.formOf("<span class=\"form-of-definition-link\"><i><a rel=\"mw:WikiLink\" href=\"/wiki/candelabrum#English\" title=\"candelabrum\">candelabrum</a></i></span>"))
    }

    @Test
    fun sectionsMergeAPartOfSpeechAndCapTheSenses() {
        val sections = WikimediaParsing.sections(WikimediaParsing.definitions(fixture("def_berry.json")), "berry")
        val english = sections.single()
        assertEquals("English", english.language)
        // Wiktionary lists berry's nouns and verbs by etymology: each is shown once.
        assertEquals(listOf("Noun", "Verb"), english.blocks.map { it.partOfSpeech })
        val noun = english.blocks.first()
        assertEquals(WikimediaParsing.MAX_SENSES, noun.senses.size)
        assertEquals(8 + 1 + 2 - WikimediaParsing.MAX_SENSES, noun.more)
        assertTrue(english.blocks.all { it.headword == "berry" })
    }

    @Test
    fun preferredIsTheBookLanguageThenEnglishWithoutTranslingual() {
        val sections = WikimediaParsing.sections(WikimediaParsing.definitions(fixture("def_chat.json")), "chat")
        assertEquals(listOf("French", "English"), WikimediaParsing.preferred(sections, "fr").map { it.language })
        assertEquals(listOf("English"), WikimediaParsing.preferred(sections, "en").map { it.language })
        assertEquals(listOf("Irish", "English"), WikimediaParsing.preferred(sections, "ga").map { it.language })
        assertEquals(listOf("English"), WikimediaParsing.preferred(sections, "ja").map { it.language })
        val maison = WikimediaParsing.sections(WikimediaParsing.definitions(fixture("def_maison.json")), "maison")
        assertTrue(WikimediaParsing.preferred(maison, "en").isEmpty())
    }

    @Test
    fun preferredKnowsWiktionarysOwnNamesForTheBooksLanguage() {
        // chat's page files Nynorsk under `other`, by name.
        val chat = WikimediaParsing.sections(WikimediaParsing.definitions(fixture("def_chat.json")), "chat")
        assertEquals(listOf("Norwegian Nynorsk", "English"), WikimediaParsing.preferred(chat, LookupText.language("nno")).map { it.language })
        // Croatian, Serbian and Bosnian are Serbo-Croatian there; Norwegian (no.wikipedia's) is Bokmål.
        fun section(code: String, language: String) = LanguageSection(code, language, emptyList())
        val most = listOf(section("en", "English"), section("sh", "Serbo-Croatian"), section("other", "Norwegian Bokmål"), section("da", "Danish"))
        for (lang in listOf("Croatian", "srp", "bs", "hbs")) {
            assertEquals(lang, listOf("Serbo-Croatian", "English"), WikimediaParsing.preferred(most, LookupText.language(lang)).map { it.language })
        }
        assertEquals(listOf("Norwegian Bokmål", "English"), WikimediaParsing.preferred(most, LookupText.language("nb")).map { it.language })
        assertEquals(listOf("English"), WikimediaParsing.preferred(most, LookupText.language("nn")).map { it.language })
        assertEquals(listOf("Danish", "English"), WikimediaParsing.preferred(most, LookupText.language("dan")).map { it.language })
        val keyed = listOf(section("other", "Serbo-Croatian"), section("nb", "Norwegian Bokmål"))
        assertEquals(listOf("Serbo-Croatian"), WikimediaParsing.preferred(keyed, "hr").map { it.language })
        assertEquals(listOf("Norwegian Bokmål"), WikimediaParsing.preferred(keyed, "no").map { it.language })
    }

    @Test
    fun badBodiesAreEmptyNotErrors() {
        assertTrue(WikimediaParsing.definitions(fixture("not_found.json")).isEmpty())
        assertTrue(WikimediaParsing.definitions("<html>").isEmpty())
        assertTrue(WikimediaParsing.definitions("""{"en":"nope","fr":[{"definitions":[{"definition":""}]}]}""").isEmpty())
        assertNull(WikimediaParsing.summary("[]", "en"))
        assertNull(WikimediaParsing.summary(fixture("not_found.json"), "en"))
        assertNull(WikimediaParsing.exactSearchHit("garbage", "x"))
    }

    // --- Wikipedia -------------------------------------------------------------------------------

    @Test
    fun summaryHasTitleDescriptionExtractAndThumbnail() {
        val s = WikimediaParsing.summary(fixture("sum_serendipity.json"), "en")!!
        assertEquals("Serendipity", s.title)
        assertEquals("Unplanned, fortunate discovery", s.description)
        assertTrue(s.extract.text.startsWith("Serendipity is an unplanned fortunate discovery. The term was coined by Horace Walpole in 1754"))
        // extract_html's bold title and italic book title are kept.
        assertEquals("Serendipity", s.extract.text.substring(s.extract.spanStyles.first().start, s.extract.spanStyles.first().end))
        assertTrue(s.thumbnail!!.startsWith("https://thumb.wikimedia.org/"))
        assertEquals(330, s.thumbnailWidth)
        assertEquals(417, s.thumbnailHeight)
        assertEquals("https://en.wikipedia.org/wiki/Serendipity", s.url)
        assertFalse(s.disambiguation)
    }

    @Test
    fun summaryMarksDisambiguationPages() {
        val s = WikimediaParsing.summary(fixture("sum_mercury.json"), "en")!!
        assertEquals("Mercury", s.title)
        assertTrue(s.disambiguation)
        assertNull(s.thumbnail)
    }

    @Test
    fun summaryFromAnotherWikipedia() {
        val s = WikimediaParsing.summary(fixture("sum_de_haus.json"), "de")!!
        assertEquals("Haus", s.title)
        assertEquals("de", s.lang)
        assertEquals("Wohn- oder Geschäftsgebäude", s.description)
        assertTrue(s.url.startsWith("https://de.wikipedia.org/"))
    }

    @Test
    fun summaryRefusesLinksAndImagesOffWikimedia() {
        val body = """{"type":"standard","title":"X","extract":"An x.","thumbnail":{"source":"https://evil.example/x.jpg","width":10,"height":10},
            |"content_urls":{"mobile":{"page":"https://en.wikipedia.org.evil.example/wiki/X"}}}""".trimMargin()
        val s = WikimediaParsing.summary(body, "en")!!
        assertNull(s.thumbnail)
        assertEquals(0, s.thumbnailWidth)
        assertEquals("https://en.wikipedia.org/wiki/X", s.url)
        assertEquals("An x.", s.extract.text)
    }

    @Test
    fun searchHitOnlyWhenTheTitleMatches() {
        assertEquals("A_Study_in_Scarlet", WikimediaParsing.exactSearchHit(fixture("search_study.json"), "a study in scarlet"))
        // The search offers Inspector Lestrade for Lestrade: a near miss is no answer.
        assertNull(WikimediaParsing.exactSearchHit(fixture("search_lestrade.json"), "Lestrade"))
    }

    @Test
    fun urlsEscapeTheTitle() {
        assertEquals("https://en.wiktionary.org/api/rest_v1/page/definition/kick_the_bucket", Urls.definition("kick the bucket").toString())
        assertEquals("https://en.wiktionary.org/api/rest_v1/page/definition/and%2For", Urls.definition("and/or").toString())
        assertEquals("https://en.wiktionary.org/api/rest_v1/page/definition/what%3F", Urls.definition("what?").toString())
        assertEquals("https://en.wiktionary.org/wiki/maison#Middle_French", Urls.wiktionaryPage("maison", "Middle French").toString())
        assertEquals("https://de.wikipedia.org/api/rest_v1/page/summary/Haus", Urls.summary("de", "Haus").toString())
        // A language without a sizeable Wikipedia asks the English one.
        assertEquals("https://en.wikipedia.org/api/rest_v1/page/summary/coi", Urls.summary("jbo", "coi").toString())
        assertEquals("https://en.wikipedia.org/w/rest.php/v1/search/title?q=lost%20world&limit=5", Urls.searchTitle("en", "lost world").toString())
    }

    companion object {
        fun fixture(name: String): String =
            requireNotNull(WikimediaParsingTest::class.java.getResourceAsStream("/lookup/$name")) { "no fixture $name" }
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
