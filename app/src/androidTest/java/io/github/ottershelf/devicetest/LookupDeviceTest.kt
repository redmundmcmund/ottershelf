package io.github.ottershelf.devicetest

import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import io.github.ottershelf.feature.reader.lookup.LookupHttp
import io.github.ottershelf.feature.reader.lookup.LookupPart
import io.github.ottershelf.feature.reader.lookup.LookupRepository
import io.github.ottershelf.feature.reader.lookup.LookupSheet
import io.github.ottershelf.feature.reader.lookup.LookupTab
import io.github.ottershelf.feature.reader.lookup.LookupViewModel
import io.github.ottershelf.feature.reader.lookup.OkHttpWikimediaRemote
import io.github.ottershelf.feature.reader.lookup.TextApps
import io.github.ottershelf.ui.theme.OttershelfTheme
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Look up's own client ([LookupHttp.client]: the reader's Wiktionary and Wikipedia requests) on
 * the phone's network, for a fixed word. The client is the app's, with only a recorder added in
 * front and behind ([Recorder]); in front it also plays a careless caller that adds an
 * `Authorization` and a `Cookie` header, which must never reach the wire. Nothing here talks to the user
 * server: the refused hosts are `.invalid`, which can't resolve anywhere.
 */
@RunWith(AndroidJUnit4::class)
class LookupDeviceTest {

    /** Every request as it went out on the wire (EventListener.requestHeadersEnd), and every call's connection events. */
    private class Recorder : EventListener() {
        val sent = CopyOnWriteArrayList<Request>()
        val connects = CopyOnWriteArrayList<String>()
        val dns = CopyOnWriteArrayList<String>()

        override fun requestHeadersEnd(call: Call, request: Request) {
            sent += request
        }

        override fun dnsStart(call: Call, domainName: String) {
            dns += domainName
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connects += inetSocketAddress.hostString
        }
    }

    /** A careless caller: credentials that must be stripped before anything is sent. */
    private object LeakyCaller : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
            chain.request().newBuilder()
                .header("Authorization", "Bearer device-test-not-a-token")
                .header("Cookie", "device-test=not-a-cookie")
                .header("Proxy-Authorization", "Basic ZGV2aWNlOnRlc3Q=")
                .build(),
        )
    }

    private fun instrumented(recorder: Recorder): OkHttpClient =
        LookupHttp.client.newBuilder()
            .apply { interceptors().add(0, LeakyCaller) }
            .eventListener(recorder)
            .build()

    @Test
    fun theRealClientFetchesADefinitionAndASummaryWithItsUserAgentAndNoCredentials() {
        val recorder = Recorder()
        val repository = LookupRepository(OkHttpWikimediaRemote(instrumented(recorder)))
        val (dictionary, wikipedia) = runBlocking(Dispatchers.IO) {
            repository.dictionary("serendipity", "en") to repository.wikipedia("serendipity", "en")
        }
        Device.log("dictionary: $dictionary")
        Device.log("wikipedia: $wikipedia")
        assertNotNull("no Wiktionary answer", dictionary)
        dictionary!!
        assertEquals("serendipity", dictionary.word)
        val english = dictionary.sections.firstOrNull { it.language == "English" }
        assertNotNull("no English entry: ${dictionary.sections.map { it.language }}", english)
        assertTrue("no noun: ${english!!.blocks.map { it.partOfSpeech }}", english.blocks.any { it.partOfSpeech == "Noun" && it.senses.isNotEmpty() })
        assertTrue(english.blocks.first().senses.first().text.text.isNotBlank())
        assertTrue(dictionary.url.startsWith("https://en.wiktionary.org/"))

        assertNotNull("no Wikipedia answer", wikipedia)
        wikipedia!!
        assertEquals("Serendipity", wikipedia.title)
        assertTrue(wikipedia.extract.text.length > 40)
        assertTrue(wikipedia.url.startsWith("https://en.wikipedia.org/"))

        assertTrue("nothing was sent", recorder.sent.isNotEmpty())
        for (request in recorder.sent) {
            Device.log("sent ${request.method} ${request.url} headers=${request.headers.names()}")
            assertTrue("not https: ${request.url}", request.isHttps)
            assertTrue("not Wikimedia: ${request.url.host}", LookupHttp.isWikimediaHost(request.url.host))
            assertEquals(LookupHttp.userAgent, request.header("User-Agent"))
            assertEquals("Authorization reached the wire", null, request.header("Authorization"))
            assertEquals("Proxy-Authorization reached the wire", null, request.header("Proxy-Authorization"))
            assertEquals("Cookie reached the wire", null, request.header("Cookie"))
        }
        assertTrue(LookupHttp.userAgent, LookupHttp.userAgent.startsWith("Ottershelf/"))
    }

    @Test
    fun otherHostsAndPlainHttpAreRefusedBeforeAnythingIsSent() {
        val recorder = Recorder()
        val client = instrumented(recorder)
        for (url in listOf(
            "https://books.example.invalid/api/v1/books",
            "https://en.wikipedia.org.lookalike.invalid/wiki/Serendipity",
            "https://user@en.wikipedia.org/wiki/Serendipity",
            "http://en.wikipedia.org/wiki/Serendipity",
        )) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().close()
                fail("$url was fetched")
            } catch (e: IOException) {
                Device.log("refused $url: ${e.message}")
            }
        }
        assertTrue("a refused host was looked up: ${recorder.dns}", recorder.dns.isEmpty())
        assertTrue("a refused host was connected to: ${recorder.connects}", recorder.connects.isEmpty())
        assertTrue("a refused request was sent: ${recorder.sent}", recorder.sent.isEmpty())
    }

    @Test
    fun theTextAppsAreFoundAndTheSheetShowsTheAnswers() {
        val context = Device.context
        val apps = TextApps.query(context)
        Device.report("text apps: ${apps.map { "${it.label} (${it.packageName}/${it.className}, ${it.kind})" }}")
        val pm = context.packageManager
        val processText = pm.queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0).map { it.activityInfo.packageName }
        val translate = pm.queryIntentActivities(Intent(Intent.ACTION_TRANSLATE), 0).map { it.activityInfo.packageName }
        Device.report("PROCESS_TEXT handlers: $processText; ACTION_TRANSLATE handlers: $translate")
        assertFalse("the app offered itself", apps.any { it.packageName == context.packageName })

        val recorder = Recorder()
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = LookupViewModel(
                repository = LookupRepository(OkHttpWikimediaRemote(instrumented(recorder))),
                bookLanguage = { "en" },
                installedApps = { TextApps.query(context) },
                online = MutableStateFlow(true),
            ) as T
        }
        val viewModel = Device.onMain { ViewModelProvider.create(store, factory)[LookupViewModel::class] }
        LockScreenHost.launch().use { host ->
            host.setContent {
                OttershelfTheme {
                    Box(Modifier.fillMaxSize().background(OttershelfTheme.colors.background)) {
                        Text(
                            "A serendipity of lanterns, far below the orbit station.",
                            Modifier.padding(24.dp, 96.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = OttershelfTheme.colors.foreground,
                        )
                        val state by viewModel.state.collectAsState()
                        state?.let { LookupSheet(it, viewModel) }
                    }
                }
            }
            Device.onMain { viewModel.open("serendipity,") }
            Device.waitUntil("the dictionary answer", 20_000) { viewModel.state.value?.dictionary !is LookupPart.Loading }
            val s = viewModel.state.value!!
            assertTrue("the dictionary tab shows ${s.dictionary}", s.dictionary is LookupPart.Ready)
            Device.waitUntil("the text apps", 10_000) { viewModel.state.value?.apps?.size == apps.size }
            SystemClock.sleep(1_200) // the sheet's slide in
            Device.screenshot("lookup/dictionary")

            Device.onMain { viewModel.selectTab(LookupTab.Wikipedia) }
            Device.waitUntil("the Wikipedia answer", 20_000) { viewModel.state.value?.wikipedia !is LookupPart.Loading }
            assertTrue("the Wikipedia tab shows ${viewModel.state.value?.wikipedia}", viewModel.state.value?.wikipedia is LookupPart.Ready)
            SystemClock.sleep(2_500) // the thumbnail, through Look up's own image loader
            Device.screenshot("lookup/wikipedia")
            Device.onMain { viewModel.close() }
            SystemClock.sleep(500)
        }
        Device.onMain { store.clear() }
        for (request in recorder.sent) {
            assertTrue("not Wikimedia: ${request.url.host}", LookupHttp.isWikimediaHost(request.url.host))
            assertEquals(null, request.header("Authorization"))
            assertEquals(null, request.header("Cookie"))
        }
    }
}
