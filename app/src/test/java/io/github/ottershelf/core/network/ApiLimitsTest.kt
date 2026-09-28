package io.github.ottershelf.core.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.NativeCredentials
import io.github.ottershelf.core.network.FakeServer.Answer
import io.github.ottershelf.core.session.Session
import io.github.ottershelf.core.session.TokenCipher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** What the Api reads into memory from the server is bounded: a broken server can't exhaust it. */
@RunWith(AndroidJUnit4::class)
class ApiLimitsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = FakeServer()

    private val cipher = object : TokenCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(stored: String) = stored.removePrefix("enc:")
    }

    private lateinit var api: Api

    @Before
    fun setUp() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        val session = Session(context, cipher)
        session.store(
            NativeCredentials(
                accessToken = "access",
                accessTokenExpiresAt = "2099-01-01T00:00:00Z",
                refreshToken = "refresh",
                refreshTokenExpiresAt = "2099-01-01T00:00:00Z",
                user = AuthUser(id = 1, username = "reader"),
            ),
            server = server.url,
        )
        api = Api(session, "Test device") {}
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun aMetadataEventTooLongToKeepIsSkippedAndTheRestStillCome() {
        val huge = "x".repeat(1_100_000)
        server.answer = {
            Answer(
                200,
                chunked = true,
                body = "data: {\"provider\":\"a\",\"title\":\"$huge\"}\n\n" +
                    "data: {\"provider\":\"b\",\"title\":\"Kept\"}\n\n" +
                    // Many data lines that only add up past the limit.
                    (1..20).joinToString("") { "data: ${"y".repeat(60_000)}\n" } + "\n" +
                    "event: message\ndata: {\"provider\":\"c\",\"title\":\"Also kept\"}\n\n",
            )
        }

        val found = runBlocking { api.searchMetadata("Dracula", "", "book").toList() }

        assertEquals(listOf("b", "c"), found.map { it.provider })
    }

    @Test
    fun aJsonAnswerTooLargeToKeepIsRefused() {
        // Sent in chunks, so no Content-Length warns of it.
        server.answer = { Answer(200, chunked = true, body = "{\"id\":1,\"username\":\"" + "x".repeat(17 shl 20) + "\"}") }

        val e = assertFails { api.me() }

        assertTrue(e.message!!, "too large" in e.message!!)
        assertFalse(e is ApiException)
    }

    @Test
    fun anEpubInfoTooLargeToKeepIsRefused() {
        server.answer = { Answer(200, body = "x".repeat((16 shl 20) + 1)) }

        assertFails { api.epubInfo(1, 2) }

        server.answer = { Answer(200, body = "{\"spine\":[]}") }
        assertEquals("{\"spine\":[]}", api.epubInfo(1, 2).decodeToString())
    }

    @Test
    fun aHugeErrorBodyStillGivesItsMessage() {
        server.answer = { Answer(500, chunked = true, body = "{\"message\":\"Broken\"}" + " ".repeat(8 shl 20)) }

        val e = assertFails { api.me() } as ApiException

        assertEquals(500, e.code)
        assertEquals("Broken", e.message)
    }

    @Test
    fun onlyTheStartOfAnErrorBodyIsRead() {
        // Valid JSON, but its message begins past the first 64 KB, which is all that is read.
        server.answer = { Answer(500, chunked = true, body = " ".repeat(64 shl 10) + "{\"message\":\"Too far in\"}") }

        val e = assertFails { api.me() } as ApiException

        assertEquals(500, e.code)
        assertEquals("HTTP 500", e.message)
    }

    private fun assertFails(block: suspend () -> Unit): IOException {
        try {
            runBlocking { block() }
        } catch (e: IOException) {
            return e
        }
        fail("the call should have failed")
        throw AssertionError()
    }
}
