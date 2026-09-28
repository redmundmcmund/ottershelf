package io.github.ottershelf.feature.reader.lookup

import kotlinx.coroutines.test.runTest
import io.github.ottershelf.feature.reader.lookup.WikimediaParsingTest.Companion.fixture
import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** The lookup rules against Wikimedia's real answers (fixtures); anything not listed is a 404. */
class LookupRepositoryTest {

    private class FakeRemote(val answers: Map<String, String>) : WikimediaRemote {
        val asked = mutableListOf<String>()
        var offline = false
        /** Requests that time out. */
        val failing = mutableSetOf<String>()

        override suspend fun get(url: HttpUrl): String? {
            synchronized(asked) { asked += url.toString() }
            if (offline || url.toString() in failing) throw IOException("timeout")
            return answers[url.toString()]
        }
    }

    private fun def(word: String) = Urls.definition(word).toString()

    private val remote = FakeRemote(
        mapOf(
            def("serendipity") to fixture("def_serendipity.json"),
            def("walked") to fixture("def_walked.json"),
            def("walk") to fixture("def_walk.json"),
            def("berries") to fixture("def_berries.json"),
            def("berry") to fixture("def_berry.json"),
            def("maison") to fixture("def_maison.json"),
            def("chat") to fixture("def_chat.json"),
            def("Haus") to fixture("def_haus_capital.json"),
            def("Running") to fixture("def_running_capital.json"),
            def("running") to fixture("def_running.json"),
            Urls.summary("en", "Serendipity").toString() to fixture("sum_serendipity.json"),
            Urls.summary("en", "Mercury").toString() to fixture("sum_mercury.json"),
            Urls.summary("de", "Haus").toString() to fixture("sum_de_haus.json"),
            Urls.searchTitle("en", "a study in scarlet").toString() to fixture("search_study.json"),
            Urls.summary("en", "A_Study_in_Scarlet").toString() to fixture("sum_study.json"),
            Urls.searchTitle("en", "Lestrade").toString() to fixture("search_lestrade.json"),
        ),
    )
    private val repository = LookupRepository(remote)

    @Test
    fun aCapitalisedWordIsRetriedInLowerCase() = runTest {
        val r = repository.dictionary("Serendipity", "en")!!
        assertEquals("serendipity", r.word)
        assertEquals(listOf("English"), r.sections.map { it.language })
        assertEquals("Noun", r.sections.single().blocks.single().partOfSpeech)
        assertEquals("https://en.wiktionary.org/wiki/serendipity#English", r.url)
    }

    @Test
    fun aCapitalisedProperNounAlsoShowsTheLowerCaseWord() = runTest {
        // "Running" at the start of a sentence: Wiktionary's Running is only a surname.
        val r = repository.dictionary("Running", "en")!!
        assertEquals("running", r.word)
        val english = r.sections.single()
        assertEquals("running", english.blocks.first().headword)
        assertEquals("Proper noun", english.blocks.last().partOfSpeech)
        assertEquals("Running", english.blocks.last().headword)
        // running's verb is "present participle of run": run was asked for too (here a 404, left out).
        assertTrue(def("run") in remote.asked)
    }

    @Test
    fun aFormOfDefinitionBringsItsWord() = runTest {
        val r = repository.dictionary("walked", "en")!!
        val english = r.sections.single()
        assertEquals(listOf("walked", "walk", "walk"), english.blocks.map { it.headword })
        assertEquals(listOf("Verb", "Verb", "Noun"), english.blocks.map { it.partOfSpeech })
        assertTrue(english.blocks[1].senses.first().text.text.startsWith("To move on the feet"))
    }

    @Test
    fun anUnknownWordTriesItsInflections() = runTest {
        val r = repository.dictionary("berrys", "en")!!
        assertEquals("berry", r.word)
        assertEquals(listOf(def("berrys"), def("berry")), remote.asked)
    }

    @Test
    fun theBooksLanguageComesFirstThenEnglish() = runTest {
        val r = repository.dictionary("chat", "fr")!!
        assertEquals(listOf("French", "English"), r.sections.map { it.language })
        assertEquals("cat (feline)", r.sections.first().blocks.first().senses.first().text.text)
        assertEquals("https://en.wiktionary.org/wiki/chat#French", r.url)
        val de = repository.dictionary("Haus", "de")!!
        assertEquals(listOf("German", "English"), de.sections.map { it.language })
        assertEquals("Haus", de.word)
    }

    @Test
    fun otherLanguagesAreTheLastResort() = runTest {
        // A French word in an English book: no English entry, so the page's first languages.
        val r = repository.dictionary("maison", "en")!!
        assertEquals(listOf("French", "Middle French"), r.sections.map { it.language })
    }

    @Test
    fun nothingFoundIsNull() = runTest {
        assertNull(repository.dictionary("Lestrade", "en"))
        assertTrue(def("Lestrade") in remote.asked)
        assertTrue(def("lestrade") in remote.asked)
    }

    @Test
    fun offlineFailsAtOnceAndIsNotRemembered() = runTest {
        remote.offline = true
        try {
            repository.dictionary("berrys", "en")
            fail("expected a failure")
        } catch (e: IOException) {
            // The inflections weren't tried: they'd have failed too.
            assertEquals(listOf(def("berrys")), remote.asked)
        }
        remote.offline = false
        assertEquals("berry", repository.dictionary("berrys", "en")!!.word)
    }

    @Test
    fun aWordThatFailedIsNotAnsweredByTheProperNounFoundInstead() = runTest {
        // "Running" at the start of a sentence; "running" times out, "Running" is only a surname.
        remote.failing += def("running")
        try {
            repository.dictionary("Running", "en")
            fail("expected a failure (Retry), not the surname")
        } catch (_: IOException) {
        }
        // Nothing was remembered: asked again, the whole answer.
        remote.failing.clear()
        val r = repository.dictionary("Running", "en")!!
        assertEquals("running", r.word)
        assertEquals("Proper noun", r.sections.single().blocks.last().partOfSpeech)
    }

    @Test
    fun anAnswerMissingAWordItNamesIsShownButAskedAgain() = runTest {
        // walked is "simple past of walk"; walk times out: walked's own entry shows, without walk's senses.
        remote.failing += def("walk")
        val partial = repository.dictionary("walked", "en")!!
        assertEquals(listOf("walked"), partial.sections.single().blocks.map { it.headword })
        // Not remembered: the next lookup asks for walk again and gets the whole answer.
        remote.failing.clear()
        val whole = repository.dictionary("walked", "en")!!
        assertEquals(listOf("walked", "walk", "walk"), whole.sections.single().blocks.map { it.headword })
        assertEquals(2, remote.asked.count { it == def("walk") })
        // Now it is remembered.
        val asked = remote.asked.size
        repository.dictionary("walked", "en")
        assertEquals(asked, remote.asked.size)
    }

    @Test
    fun aStrongAnswerAfterAFailedWordIsShownButAskedAgain() = runTest {
        // "Running": the surname's page times out, running's own is there. It answers, not remembered.
        remote.failing += def("Running")
        val r = repository.dictionary("Running", "en")!!
        assertEquals("running", r.word)
        assertTrue(r.sections.single().blocks.none { it.partOfSpeech == "Proper noun" })
        remote.failing.clear()
        assertEquals("Proper noun", repository.dictionary("Running", "en")!!.sections.single().blocks.last().partOfSpeech)
    }

    @Test
    fun answersAreRememberedForTheSession() = runTest {
        repository.dictionary("walked", "en")
        repository.wikipedia("Serendipity", "en")
        val asked = remote.asked.size
        repository.dictionary("walked", "en")
        repository.wikipedia("Serendipity", "en")
        assertNull(repository.dictionary("Lestrade", "en"))
        val afterMiss = remote.asked.size
        assertNull(repository.dictionary("Lestrade", "en"))
        assertEquals(afterMiss, remote.asked.size)
        assertTrue(asked < afterMiss)
    }

    // --- Wikipedia -------------------------------------------------------------------------------

    @Test
    fun wikipediaInTheBooksLanguageFirst() = runTest {
        val s = repository.wikipedia("Haus", "de")!!
        assertEquals("de", s.lang)
        assertEquals(listOf(Urls.summary("de", "Haus").toString()), remote.asked)
    }

    @Test
    fun wikipediaFallsBackToEnglish() = runTest {
        val s = repository.wikipedia("Serendipity", "de")!!
        assertEquals("en", s.lang)
        assertEquals(
            listOf(Urls.summary("de", "Serendipity"), Urls.searchTitle("de", "Serendipity"), Urls.summary("en", "Serendipity")).map { it.toString() },
            remote.asked,
        )
    }

    @Test
    fun aTitleInTheWrongCaseIsFoundBySearch() = runTest {
        val s = repository.wikipedia("a study in scarlet", "en")!!
        assertEquals("A Study in Scarlet", s.title)
    }

    @Test
    fun aNearMissIsNoArticle() = runTest {
        assertNull(repository.wikipedia("Lestrade", "en"))
        // The near miss's summary was never asked for.
        assertTrue(remote.asked.none { "Inspector" in it })
    }

    @Test
    fun wikipediaOfflineThrows() = runTest {
        remote.offline = true
        try {
            repository.wikipedia("Mercury", "en")
            fail("expected a failure")
        } catch (_: IOException) {
        }
        remote.offline = false
        assertTrue(repository.wikipedia("Mercury", "en")!!.disambiguation)
    }
}
