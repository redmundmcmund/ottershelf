package io.github.ottershelf.core.session

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.NativeCredentials
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.network.FakeServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * When the session's tokens are decrypted (each a Keystore round trip on a phone): never on the
 * main thread just to know whether the user is signed in, as at app start.
 */
@RunWith(AndroidJUnit4::class)
class SessionDecryptTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    /** Reversible, or ([readable] false) a key that can't decrypt; counts the decrypts. */
    private class CountingCipher(private val readable: Boolean = true) : TokenCipher {
        val decrypts = AtomicInteger()
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(stored: String): String? {
            decrypts.incrementAndGet()
            return if (readable) stored.removePrefix("enc:") else null
        }
    }

    private fun credentials(name: String, expiresAt: String) = NativeCredentials(
        accessToken = "access-$name",
        accessTokenExpiresAt = expiresAt,
        refreshToken = "refresh-$name",
        refreshTokenExpiresAt = "2099-01-01T00:00:00Z",
        user = AuthUser(id = 1, username = "reader"),
    )

    @Before
    fun storeASignIn() {
        prefs.edit().clear().commit()
        Session(context, CountingCipher()).store(credentials("1", expiresAt = "2099-01-01T00:00:00Z"), server = "https://books.example.net")
    }

    @Test
    fun theAppStartsSignedInWithoutDecryptingOnTheMainThread() {
        assertTrue(Looper.getMainLooper().isCurrentThread) // as in Application.onCreate
        val cipher = CountingCipher()
        val session = Session(context, cipher)

        val auth = AuthState(session)

        assertEquals(AuthState.Status.SignedIn("books.example.net_reader"), auth.status.value)
        assertEquals(0, cipher.decrypts.get())
        assertEquals("refresh-1", session.refreshToken)
        assertTrue(session.isSignedIn)
    }

    @Test
    fun tokensTheKeyCannotDecryptAreSignedOutOnceReadAndStayStored() {
        val session = Session(context, CountingCipher(readable = false))
        assertTrue(session.isSignedIn) // stored, not read yet

        assertNull(session.refreshToken)

        assertFalse(session.isSignedIn)
        // Kept: a Keystore failure may pass (Session.store replaces the key only for a new sign-in).
        assertTrue(prefs.contains("refresh.enc"))
        assertTrue(prefs.contains("access.enc"))
    }

    @Test
    fun eachTokenIsDecryptedOnceWhenFirstRead() {
        val cipher = CountingCipher()
        val session = Session(context, cipher)

        assertEquals("refresh-1", session.refreshToken)
        assertEquals(1, cipher.decrypts.get())
        assertEquals("access-1", session.accessToken)
        assertEquals("access-1", session.accessToken)
        assertEquals("refresh-1", session.refreshToken)
        assertEquals(2, cipher.decrypts.get())
    }

    @Test
    fun anExpiredAccessTokenIsNeverDecrypted() = FakeServer().use { server ->
        Session(context, CountingCipher()).store(credentials("old", expiresAt = "2020-01-01T00:00:00Z"), server = server.url)
        server.answer = { request ->
            if (request.path.endsWith("/auth/refresh")) {
                FakeServer.Answer(200, ApiJson.encodeToString(NativeCredentials.serializer(), credentials("new", expiresAt = "2099-01-01T00:00:00Z")))
            } else {
                FakeServer.Answer(200, """{"id":1,"username":"reader"}""")
            }
        }
        val cipher = CountingCipher()
        val api = Api(Session(context, cipher), "Test device") {}

        assertEquals("reader", runBlocking { api.me() }.username)

        assertEquals("only the refresh token", 1, cipher.decrypts.get())
        assertEquals("Bearer access-new", server.received.last().authorization)
    }

    @Test
    fun offTheMainThreadTheTokensAreReadBeforeAnswering() {
        val cipher = CountingCipher(readable = false)
        val session = Session(context, cipher)

        var signedIn: Boolean? = null
        thread { signedIn = session.isSignedIn }.join() // a worker's check

        assertEquals(false, signedIn)
        assertTrue(cipher.decrypts.get() > 0)
        assertTrue(prefs.contains("refresh.enc"))
    }
}
