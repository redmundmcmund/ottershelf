package io.github.ottershelf.feature.reader.lookup

import kotlinx.coroutines.test.runTest
import io.github.ottershelf.BuildConfig
import io.github.ottershelf.feature.reader.lookup.WikimediaParsingTest.Companion.fixture
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * The Wikimedia client can never carry the account's credentials: it is a bare client (not the
 * app's), strips credential headers, names itself, and refuses every host that isn't Wikimedia's
 * before connecting (checked without a network: a refused request never reaches the socket).
 */
class LookupHttpTest {

    @Test
    fun wikimediaHostsAreComparedOnTheParsedHost() {
        listOf("en.wiktionary.org", "de.wikipedia.org", "upload.wikimedia.org", "thumb.wikimedia.org", "wikipedia.org", "EN.Wikipedia.ORG.").forEach {
            assertTrue(it, LookupHttp.isWikimediaHost(it))
        }
        listOf("books.example.net", "wikipedia.org.evil.example", "evilwikipedia.org", "en.wikipedia.org.example.net", "wikimedia.org-evil.example", "").forEach {
            assertFalse(it, LookupHttp.isWikimediaHost(it))
        }
    }

    @Test
    fun linksAndImagesMustBeHttpsOnWikimedia() {
        assertTrue(LookupHttp.isWikimediaUrl("https://en.wikipedia.org/wiki/Serendipity"))
        assertFalse(LookupHttp.isWikimediaUrl("http://en.wikipedia.org/wiki/Serendipity"))
        // User info before the host: the host is what counts, and user info isn't taken at all.
        assertFalse(LookupHttp.isWikimediaUrl("https://en.wikipedia.org@evil.example/wiki/X"))
        assertFalse(LookupHttp.isWikimediaUrl("https://books.example.net@en.wikipedia.org/wiki/X"))
        assertFalse(LookupHttp.isWikimediaUrl("javascript:alert(1)"))
        assertFalse(LookupHttp.isWikimediaUrl("intent://x#Intent;end"))
        assertTrue(LookupHttp.isWikimediaImage("https://upload.wikimedia.org/wikipedia/commons/a/a0/Horace_Walpole.jpg"))
        assertFalse(LookupHttp.isWikimediaImage("https://en.wikipedia.org/wiki/File:X.jpg"))
    }

    @Test
    fun theClientIsBareNotTheAccountsClient() {
        val client = LookupHttp.newClient()
        assertTrue(client.interceptors.single() is WikimediaGuard)
        assertTrue(client.networkInterceptors.single() is WikimediaGuard)
        assertSame(Authenticator.NONE, client.authenticator)
        assertSame(Authenticator.NONE, client.proxyAuthenticator)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertNull(client.cache)
        assertFalse(client.followSslRedirects)
        assertTrue(client.callTimeoutMillis in 1..15_000)
    }

    @Test
    fun requestsCarryTheUserAgentAndNoCredentials() {
        var seen: Request? = null
        val client = capture(LookupHttp.newClient()) { request ->
            seen = request
            response(request, 200, "{}")
        }
        client.newCall(
            Request.Builder()
                .url("https://en.wiktionary.org/api/rest_v1/page/definition/serendipity")
                .header("Authorization", "Bearer not-a-real-token")
                .header("Proxy-Authorization", "Basic x")
                .header("Cookie", "refresh_token=x")
                .build(),
        ).execute().close()
        val sent = seen!!
        assertEquals(LookupHttp.userAgent, sent.header("User-Agent"))
        assertNull(sent.header("Authorization"))
        assertNull(sent.header("Proxy-Authorization"))
        assertNull(sent.header("Cookie"))
        assertEquals("Ottershelf/${BuildConfig.VERSION_NAME} (https://github.com/redmundmcmund/ottershelf) okhttp/${OkHttp.VERSION}", LookupHttp.userAgent)
    }

    @Test
    fun otherHostsAreRefusedBeforeAnythingIsSent() {
        var reached = 0
        val client = capture(LookupHttp.newClient()) { request ->
            reached++
            response(request, 200, "{}")
        }
        listOf(
            "https://books.example.net/api/v1/auth/me",
            "https://en.wikipedia.org.evil.example/wiki/X",
            "https://user:pass@en.wikipedia.org/wiki/X",
            "http://en.wikipedia.org/wiki/X",
        ).forEach { url ->
            try {
                client.newCall(Request.Builder().url(url).build()).execute().close()
                fail("$url went through")
            } catch (e: IOException) {
                assertTrue(e.message.orEmpty(), e.message.orEmpty().startsWith("Look up only"))
            }
        }
        assertEquals(0, reached)
    }

    @Test
    fun remoteReadsBodiesAndTakesA404AsNothing() = runTest {
        val client = capture(LookupHttp.newClient()) { request ->
            when (request.url.pathSegments.last()) {
                "serendipity" -> response(request, 200, """{"en":[]}""")
                "missing" -> response(request, 404, """{"status":404}""")
                "gone" -> response(request, 410, "")
                "bad" -> response(request, 400, "")
                "long" -> response(request, 414, "")
                "forbidden" -> response(request, 403, """{"status":403}""")
                "unauthorized" -> response(request, 401, "")
                "timeout" -> response(request, 408, "")
                "busy" -> response(request, 429, "")
                else -> response(request, 503, "")
            }
        }
        val remote = OkHttpWikimediaRemote(client)
        assertEquals("""{"en":[]}""", remote.get("https://en.wiktionary.org/x/serendipity".toHttpUrl()))
        for (nothing in listOf("missing", "gone", "bad", "long")) {
            assertNull(nothing, remote.get("https://en.wiktionary.org/x/$nothing".toHttpUrl()))
        }
        // Not "no entry": a failure (Retry), so it isn't remembered as the answer.
        for (failing in listOf("forbidden", "unauthorized", "timeout", "busy", "down")) {
            try {
                remote.get("https://en.wiktionary.org/x/$failing".toHttpUrl())
                fail("$failing should fail")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun aRefusalOffersRetryAndIsNotRememberedAsNoEntry() = runTest {
        // Wikimedia answering 403 for a while (its User-Agent policy, a block): "No dictionary
        // entry" kept for the session would hide a word that has one.
        var refuse = true
        val client = capture(LookupHttp.newClient()) { request ->
            when {
                refuse -> response(request, 403, """{"status":403}""")
                request.url.pathSegments.last() == "serendipity" -> response(request, 200, fixture("def_serendipity.json"))
                request.url.pathSegments.last() == "Serendipity" && request.url.host == "en.wikipedia.org" -> response(request, 200, fixture("sum_serendipity.json"))
                else -> response(request, 404, "")
            }
        }
        val repository = LookupRepository(OkHttpWikimediaRemote(client))
        for (lookup in listOf<suspend () -> Any?>({ repository.dictionary("serendipity", "en") }, { repository.wikipedia("Serendipity", "en") })) {
            try {
                lookup()
                fail("a 403 read as nothing found")
            } catch (_: IOException) {
            }
        }
        refuse = false
        assertEquals("serendipity", repository.dictionary("serendipity", "en")?.word)
        assertEquals("Serendipity", repository.wikipedia("Serendipity", "en")?.title)
    }

    /** [client] answering every request itself, after its own interceptors ran (so nothing touches the network). */
    private fun capture(client: OkHttpClient, answer: (Request) -> Response): OkHttpClient =
        client.newBuilder().addInterceptor(Interceptor { chain -> answer(chain.request()) }).build()

    private fun response(request: Request, code: Int, body: String) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()
}
