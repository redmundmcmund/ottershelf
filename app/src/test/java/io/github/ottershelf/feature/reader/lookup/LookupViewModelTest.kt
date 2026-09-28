package io.github.ottershelf.feature.reader.lookup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import io.github.ottershelf.feature.reader.lookup.WikimediaParsingTest.Companion.fixture
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor

/** The sheet's state: which tabs, what is asked (and when nothing is), failures, retries, the apps. */
@OptIn(ExperimentalCoroutinesApi::class)
class LookupViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Remote : WikimediaRemote {
        val asked = mutableListOf<String>()
        /** The dispatcher each request (and so the parsing after it) ran on. */
        val ranOn = mutableListOf<ContinuationInterceptor?>()
        var offline = false
        var gate: CompletableDeferred<Unit>? = null
        val answers = mapOf(
            Urls.definition("serendipity").toString() to fixture("def_serendipity.json"),
            Urls.definition("chat").toString() to fixture("def_chat.json"),
            Urls.summary("en", "Serendipity").toString() to fixture("sum_serendipity.json"),
            Urls.summary("en", "serendipity").toString() to fixture("sum_serendipity.json"),
        )

        override suspend fun get(url: HttpUrl): String? {
            asked += url.toString()
            ranOn += currentCoroutineContext()[ContinuationInterceptor]
            gate?.await()
            if (offline) throw IOException("offline")
            return answers[url.toString()]
        }
    }

    private class Setup(val vm: LookupViewModel, val remote: Remote, val online: MutableStateFlow<Boolean>) {
        var languageReads = 0
    }

    private fun TestScope.setup(
        language: String? = "en",
        apps: List<TextApp> = emptyList(),
        readLanguage: suspend () -> String? = { language },
        work: CoroutineDispatcher = dispatcher,
    ): Setup {
        val remote = Remote()
        val online = MutableStateFlow(true)
        lateinit var setup: Setup
        val vm = LookupViewModel(
            repository = LookupRepository(remote),
            bookLanguage = { setup.languageReads++; readLanguage() },
            installedApps = { apps },
            online = online,
            io = dispatcher,
            work = work,
            now = { scheduler.currentTime },
        )
        setup = Setup(vm, remote, online)
        return setup
    }

    @Test
    fun aWordOpensOnTheDictionaryAndLoadsBothTabs() = runTest(dispatcher) {
        val s = setup()
        s.vm.open("“Serendipity,”")
        val opening = s.vm.state.value!!
        assertEquals("“Serendipity,”", opening.text)
        assertEquals("Serendipity", opening.term)
        assertTrue(opening.showDictionary)
        assertEquals(LookupTab.Dictionary, opening.tab)
        assertEquals(LookupPart.Loading, opening.dictionary)
        advanceUntilIdle()
        val state = s.vm.state.value!!
        assertEquals("serendipity", (state.dictionary as LookupPart.Ready).value.word)
        assertEquals("Serendipity", (state.wikipedia as LookupPart.Ready).value.title)
        s.vm.selectTab(LookupTab.Wikipedia)
        assertEquals(LookupTab.Wikipedia, s.vm.state.value!!.tab)
    }

    @Test
    fun aPhraseOfMoreThanFiveWordsSkipsTheDictionary() = runTest(dispatcher) {
        val s = setup()
        s.vm.open("the journey that matters in the end")
        advanceUntilIdle()
        val state = s.vm.state.value!!
        assertFalse(state.showDictionary)
        assertEquals(LookupTab.Wikipedia, state.tab)
        assertEquals(LookupPart.Skipped, state.dictionary)
        assertEquals(LookupPart.NotFound, state.wikipedia)
        assertTrue(s.remote.asked.none { "wiktionary" in it })
        // No Dictionary tab to switch to.
        s.vm.selectTab(LookupTab.Dictionary)
        assertEquals(LookupTab.Wikipedia, s.vm.state.value!!.tab)
    }

    @Test
    fun aPassageSendsNothingButStillOffersTheApps() = runTest(dispatcher) {
        val app = TextApp("com.example.translate", "com.example.translate.Process", "Translate")
        val s = setup(apps = listOf(app))
        s.vm.open("It is a capital mistake to theorize before you have all the evidence. It biases the judgment.")
        advanceUntilIdle()
        val state = s.vm.state.value!!
        assertEquals(LookupPart.Skipped, state.dictionary)
        assertEquals(LookupPart.Skipped, state.wikipedia)
        assertTrue(s.remote.asked.isEmpty())
        assertEquals(listOf(app), state.apps)
    }

    @Test
    fun offlineFailsWithRetryAndComesBackByItself() = runTest(dispatcher) {
        val s = setup()
        s.remote.offline = true
        s.online.value = false
        s.vm.open("serendipity")
        advanceUntilIdle()
        assertEquals(LookupPart.Failed(offline = true), s.vm.state.value!!.dictionary)
        assertEquals(LookupPart.Failed(offline = true), s.vm.state.value!!.wikipedia)
        // Retry while still offline: the same.
        s.vm.retry()
        advanceUntilIdle()
        assertEquals(LookupPart.Failed(offline = true), s.vm.state.value!!.dictionary)
        // The connection comes back: both are asked again without a tap.
        s.remote.offline = false
        s.online.value = true
        advanceUntilIdle()
        assertTrue(s.vm.state.value!!.dictionary is LookupPart.Ready)
        assertTrue(s.vm.state.value!!.wikipedia is LookupPart.Ready)
    }

    @Test
    fun aServerErrorOnlineIsNotOffline() = runTest(dispatcher) {
        val s = setup()
        s.remote.offline = true
        s.vm.open("serendipity")
        advanceUntilIdle()
        assertEquals(LookupPart.Failed(offline = false), s.vm.state.value!!.dictionary)
        s.remote.offline = false
        s.vm.retry()
        advanceUntilIdle()
        assertTrue(s.vm.state.value!!.dictionary is LookupPart.Ready)
    }

    @Test
    fun theBooksLanguageIsReadOnceAndOrdersTheDictionary() = runTest(dispatcher) {
        val s = setup(language = "fre")
        s.vm.open("chat")
        advanceUntilIdle()
        val sections = (s.vm.state.value!!.dictionary as LookupPart.Ready).value.sections
        assertEquals(listOf("French", "English"), sections.map { it.language })
        assertEquals("fr", s.vm.state.value!!.wikipediaLang)
        s.vm.close()
        s.vm.open("serendipity")
        advanceUntilIdle()
        assertEquals(1, s.languageReads)
    }

    @Test
    fun anAnswerAfterClosingDoesNotReopen() = runTest(dispatcher) {
        val s = setup()
        s.remote.gate = CompletableDeferred()
        s.vm.open("serendipity")
        advanceUntilIdle()
        s.vm.close()
        s.remote.gate!!.complete(Unit)
        advanceUntilIdle()
        assertNull(s.vm.state.value)
    }

    @Test
    fun theSameWordAgainIsAnsweredFromMemory() = runTest(dispatcher) {
        val s = setup()
        s.vm.open("serendipity")
        advanceUntilIdle()
        val asked = s.remote.asked.size
        s.vm.close()
        s.vm.open("serendipity")
        advanceUntilIdle()
        assertEquals(asked, s.remote.asked.size)
        assertTrue(s.vm.state.value!!.dictionary is LookupPart.Ready)
    }

    @Test
    fun lookupsRunOffTheMainThread() = runTest(dispatcher) {
        // Main is `dispatcher`; the requests, and the parsing of their answers after them, run on `work`.
        val work = StandardTestDispatcher(scheduler, "work")
        val s = setup(work = work)
        s.vm.open("serendipity")
        advanceUntilIdle()
        assertTrue(s.vm.state.value!!.dictionary is LookupPart.Ready)
        assertTrue(s.vm.state.value!!.wikipedia is LookupPart.Ready)
        assertTrue(s.remote.ranOn.isNotEmpty())
        assertTrue(s.remote.ranOn.toString(), s.remote.ranOn.all { it === work })
    }

    @Test
    fun aSlowLanguageReadIsWaitedForOnceAndNotAskedAgainAtOnce() = runTest(dispatcher) {
        // Not downloaded, and the user's server doesn't answer: the read gives up after 5 s.
        val s = setup(readLanguage = { delay(5_000); throw IOException("No answer from the server") })
        s.vm.open("serendipity")
        advanceUntilIdle()
        // Both parts waited for the one read: answered 5 s in, not 10.
        assertEquals(1, s.languageReads)
        assertEquals(5_000L, currentTime)
        assertTrue(s.vm.state.value!!.dictionary is LookupPart.Ready)
        assertTrue(s.vm.state.value!!.wikipedia is LookupPart.Ready)
        assertEquals("en", s.vm.state.value!!.wikipediaLang)
        // The next lookup soon after doesn't wait again...
        s.vm.close()
        s.vm.open("chat")
        advanceUntilIdle()
        assertEquals(1, s.languageReads)
        assertEquals(5_000L, currentTime)
        // ...but a later one asks again.
        s.vm.close()
        advanceTimeBy(LookupViewModel.LANGUAGE_RETRY_MS)
        s.vm.open("serendipity")
        advanceUntilIdle()
        assertEquals(2, s.languageReads)
    }

    @Test
    fun aLanguageThatCouldNotBeReadOfflineIsAskedAgainWhenBackOnline() = runTest(dispatcher) {
        var offline = true
        val s = setup(readLanguage = { if (offline) throw IOException("offline") else "fre" })
        s.remote.offline = true
        s.online.value = false
        s.vm.open("chat")
        advanceUntilIdle()
        assertEquals(LookupPart.Failed(offline = true), s.vm.state.value!!.dictionary)
        offline = false
        s.remote.offline = false
        s.online.value = true
        advanceUntilIdle()
        assertEquals(2, s.languageReads)
        val sections = (s.vm.state.value!!.dictionary as LookupPart.Ready).value.sections
        assertEquals(listOf("French", "English"), sections.map { it.language })
        assertEquals("fr", s.vm.state.value!!.wikipediaLang)
    }

    @Test
    fun aSpacelessPassageSendsNothing() = runTest(dispatcher) {
        val s = setup()
        s.vm.open("我们在图书馆里读了一整个下午的书，然后一起去吃晚饭。")
        advanceUntilIdle()
        val state = s.vm.state.value!!
        assertEquals(LookupPart.Skipped, state.dictionary)
        assertEquals(LookupPart.Skipped, state.wikipedia)
        assertTrue(s.remote.asked.isEmpty())
        assertEquals(0, s.languageReads)
        // A word of it is still looked up.
        s.vm.open("图书馆")
        assertTrue(s.vm.state.value!!.showDictionary)
    }

    @Test
    fun nothingToLookUpOpensNothing() = runTest(dispatcher) {
        val s = setup()
        s.vm.open(" …! ")
        assertNull(s.vm.state.value)
    }
}
