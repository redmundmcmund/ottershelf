package io.github.ottershelf.core.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Signing in and renewing the sign-in, against a [FakeServer] on this machine. */
@RunWith(AndroidJUnit4::class)
class ApiAuthTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = FakeServer()
    private val signedOut = AtomicInteger()

    private val cipher = object : TokenCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(stored: String) = stored.removePrefix("enc:")
    }

    private lateinit var session: Session
    private lateinit var api: Api

    @Before
    fun setUp() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        session = Session(context, cipher)
        // Expired, so the next request renews it first.
        session.store(credentials("old", expiresAt = "2020-01-01T00:00:00Z"), server = server.url)
        api = Api(session, "Test device") { signedOut.incrementAndGet() }
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun aRefreshRejectedByTheServerSignsOut() {
        server.answer = { Answer(401, """{"message":"Invalid refresh token"}""") }

        assertFails { api.me() }

        assertFalse(session.isSignedIn)
        assertEquals(1, signedOut.get())
    }

    @Test
    fun aForbiddenRefreshIsPassingAndKeepsTheSignIn() {
        // BookOrbit never answers auth/refresh with 403: that's a proxy or firewall in front of it.
        server.answer = { Answer(403, "<html>blocked</html>") }

        assertFails { api.me() }

        assertTrue(session.isSignedIn)
        assertEquals("refresh-old", session.refreshToken)
        assertEquals(0, signedOut.get())
    }

    @Test
    fun aRejectionOfTheOldSessionDoesNotSignOutTheNewOne() {
        server.answer = { request ->
            if (request.path.endsWith("/auth/refresh")) {
                // The user signs out and in again while the old token's refresh is on its way.
                session.clearCredentials()
                session.store(credentials("new", expiresAt = "2099-01-01T00:00:00Z"))
            }
            Answer(401)
        }

        assertFails { api.me() }

        assertTrue(session.isSignedIn)
        assertEquals("refresh-new", session.refreshToken)
        assertEquals(0, signedOut.get())
    }

    @Test
    fun aRefreshTheServerCouldNotDoPausesTheNextOnes() {
        server.answer = { Answer(503) }

        val first = assertFails { api.me() }
        val second = assertFails { api.me() }

        // Plain IOExceptions: a caller mustn't take the refresh's status for its own request's.
        assertFalse(first is ApiException)
        assertFalse(second is ApiException)
        // Neither request went without a token, and the second didn't try the refresh again.
        assertEquals(listOf("/api/v1/auth/refresh"), server.paths())
        assertTrue(session.isSignedIn)
    }

    @Test
    fun aRefreshThatFailedAtOnceIsTriedAgainNextTime() {
        // No answer at all, straight away: as when the network is only just coming back.
        server.answer = { Answer(drop = true) }

        assertFails { api.me() }
        val tried = server.received.size
        assertFails { api.me() }

        assertTrue(server.received.size > tried)
        assertTrue(server.paths().all { it.endsWith("/auth/refresh") })
        assertTrue(session.isSignedIn)
    }

    @Test
    fun aTokenWithSecondsLeftStillServesWhileTheRefreshFails() {
        session.store(credentials("old", expiresAt = Instant.now().plusSeconds(20).toString()))
        server.answer = { request ->
            when {
                request.path.endsWith("/auth/refresh") -> Answer(503)
                request.authorization == "Bearer access-old" -> Answer(200, """{"id":1,"username":"reader"}""")
                else -> Answer(401)
            }
        }

        assertEquals("reader", runBlocking { api.me() }.username)
        assertEquals("reader", runBlocking { api.me() }.username)
        assertEquals(1, server.paths().count { it.endsWith("/auth/refresh") })
    }

    @Test
    fun signingInDoesNotFollowARedirect() = FakeServer().use { elsewhere ->
        // Followed, a 307 would post the password again to wherever it points.
        server.answer = { Answer(307, headers = mapOf("Location" to "${elsewhere.url}/api/v1/auth/login")) }

        val e = assertFails { api.login(server.url, "reader", "secret") }

        assertEquals(307, (e as ApiException).code)
        assertTrue(elsewhere.received.isEmpty())
    }

    @Test
    fun renewingAndEndingTheSessionDoNotFollowARedirect() = FakeServer().use { elsewhere ->
        server.answer = { request ->
            if (request.path.endsWith("/auth/refresh") || request.path.endsWith("/auth/logout")) {
                Answer(308, headers = mapOf("Location" to elsewhere.url + request.path))
            } else {
                Answer(401)
            }
        }

        assertFails { api.me() }
        assertFails { api.revoke("refresh-old") }

        assertTrue(elsewhere.received.isEmpty())
        assertTrue("/api/v1/auth/logout" in server.paths())
        assertEquals("refresh-old", session.refreshToken)
    }

    @Test
    fun aFailedSignInElsewhereKeepsTheServerTheStoredTokensBelongTo() = FakeServer().use { other ->
        other.answer = { Answer(401, """{"message":"Invalid credentials"}""") }

        assertFails { api.login(other.url, "reader", "wrong") }

        // Otherwise those tokens would go to the other server once they can be read again.
        val reopened = Session(context, cipher)
        assertEquals(server.url, reopened.serverUrl)
        assertEquals("refresh-old", reopened.refreshToken)
        assertEquals(listOf("/api/v1/auth/login"), other.paths())
    }

    @Test
    fun aSignInStoresItsServerWithItsTokens() = FakeServer().use { other ->
        val creds = credentials("new", expiresAt = "2099-01-01T00:00:00Z")
        other.answer = { Answer(200, ApiJson.encodeToString(NativeCredentials.serializer(), creds)) }

        runBlocking { api.login("${other.url}/", "reader", "secret") }

        val reopened = Session(context, cipher)
        assertEquals(other.url, reopened.serverUrl)
        assertEquals("refresh-new", reopened.refreshToken)
    }

    @Test
    fun aSignInThatCannotBeKeptIsEndedOnTheServer() = FakeServer().use { other ->
        val creds = credentials("new", expiresAt = "2099-01-01T00:00:00Z")
        other.answer = { request ->
            if (request.path.endsWith("/auth/login")) Answer(200, ApiJson.encodeToString(NativeCredentials.serializer(), creds))
            else Answer(204)
        }
        val keystoreBroken = object : TokenCipher {
            override fun encrypt(plain: String): String = throw IllegalStateException("keystore")
            override fun decrypt(stored: String): String? = null
        }
        val api = Api(Session(context, keystoreBroken), "Test device") {}

        assertFails { api.login(other.url, "reader", "secret") }

        // Revoked at the server it was made at, not the one stored before.
        assertEquals(listOf("/api/v1/auth/login", "/api/v1/auth/logout"), other.paths())
        assertTrue("refresh-new" in other.received.last().body)
        assertTrue(server.received.isEmpty())
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

    private fun credentials(name: String, expiresAt: String) = NativeCredentials(
        accessToken = "access-$name",
        accessTokenExpiresAt = expiresAt,
        refreshToken = "refresh-$name",
        refreshTokenExpiresAt = "2099-01-01T00:00:00Z",
        user = AuthUser(id = 1, username = "reader"),
    )
}
