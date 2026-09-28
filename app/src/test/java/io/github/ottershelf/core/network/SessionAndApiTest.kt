package io.github.ottershelf.core.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.core.model.NativeCredentials
import io.github.ottershelf.core.session.Session
import io.github.ottershelf.core.session.TokenCipher
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** Session storage (with a stand-in for the Keystore cipher, which Robolectric lacks) and Api guards. */
@RunWith(AndroidJUnit4::class)
class SessionAndApiTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    /** Reversible, and visibly not the plain text. */
    private val cipher = object : TokenCipher {
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(stored: String) = if (stored.startsWith("enc:")) stored.removePrefix("enc:").reversed() else null
    }

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun tokensAreStoredEncryptedAndReadBack() {
        val session = Session(context, cipher)
        session.store(credentials(), server = "https://books.example.net/")

        assertTrue(session.isSignedIn)
        assertEquals("https://books.example.net", session.serverUrl)
        assertEquals("access-1", session.accessToken)
        assertEquals("reader", session.username)
        assertEquals(1_790_000_000_000L, session.accessExpiresAtMs)
        val onDisk = prefs.all.values.joinToString()
        assertFalse("access-1" in onDisk)
        assertFalse("refresh-1" in onDisk)

        val reopened = Session(context, cipher)
        assertEquals("refresh-1", reopened.refreshToken)
        assertEquals("books.example.net_reader", reopened.accountKey())
    }

    @Test
    fun undecryptableTokensMeanSignedOut() {
        Session(context, cipher).store(credentials(), server = "https://books.example.net")
        var replaced = 0
        val keyLost = object : TokenCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(stored: String): String? = null
            override fun replaceKey() { replaced++ }
        }
        val session = Session(context, keyLost)
        assertNull(session.refreshToken) // read off the main thread at app start (AppContainer.start)
        assertFalse(session.isSignedIn)
        // A failed decrypt may pass (the Keystore busy at boot): the key and the tokens stay.
        assertEquals(0, replaced)
        assertTrue(prefs.contains("refresh.enc"))
    }

    @Test
    fun aKeyThatCanNoLongerEncryptIsReplacedAtTheNextSignIn() {
        var replaced = 0
        val brokenUntilReplaced = object : TokenCipher {
            override fun encrypt(plain: String) =
                if (replaced == 0) throw IllegalStateException("key blob corrupted") else cipher.encrypt(plain)
            override fun decrypt(stored: String) = cipher.decrypt(stored)
            override fun replaceKey() { replaced++ }
        }
        val session = Session(context, brokenUntilReplaced)

        assertTrue(session.store(credentials(), server = "https://books.example.net"))

        assertEquals(1, replaced)
        assertEquals("access-1", session.accessToken)
        assertEquals("refresh-1", Session(context, cipher).refreshToken)
    }

    @Test
    fun clearingCredentialsSignsOut() {
        val session = Session(context, cipher)
        session.store(credentials(), server = "https://books.example.net")
        session.clearCredentials()
        assertFalse(session.isSignedIn)
        assertNull(Session(context, cipher).refreshToken)
    }

    @Test
    fun aRefreshThatStartedBeforeSignOutDoesNotStoreItsTokens() {
        val session = Session(context, cipher)
        session.store(credentials(), server = "https://books.example.net")
        val beforeSignOut = session.credentialsGeneration
        session.clearCredentials()

        assertFalse(session.store(credentials(), ifGeneration = beforeSignOut))
        assertFalse(session.isSignedIn)
        assertNull(Session(context, cipher).refreshToken)

        assertTrue(session.store(credentials(), ifGeneration = session.credentialsGeneration))
        assertTrue(session.isSignedIn)
    }

    @Test
    fun aKeystoreFailureIsAnIOException() {
        var replaced = 0
        val broken = object : TokenCipher {
            override fun encrypt(plain: String): String = throw IllegalStateException("keystore")
            override fun decrypt(stored: String): String? = null
            override fun replaceKey() { replaced++ }
        }
        val session = Session(context, broken)
        try {
            session.store(credentials(), server = "https://books.example.net")
            fail("store should have thrown")
        } catch (_: IOException) {
        }
        assertFalse(session.isSignedIn)
        assertEquals("a new key is tried once, not forever", 1, replaced)
    }

    @Test
    fun theReaderProxyOnlyForwardsEpubRoutes() {
        val session = Session(context, cipher)
        session.store(credentials(), server = "https://books.example.invalid")
        val api = Api(session, "Test device") {}
        for (path in listOf("/api/v1/books/1", "/api/v1/auth/me", "/api/v1/epub/../books/1", "/api/v1/epub/%2e%2e/auth/me")) {
            try {
                api.proxy(path).close()
                fail("$path should have been refused")
            } catch (e: IOException) {
                assertTrue(e.message!!, e.message!!.startsWith("Not a reader route"))
            }
        }
    }

    @Test
    fun theTokenOnlyGoesToTheServerItself() {
        val session = Session(context, cipher)
        val api = Api(session, "Test device") {}
        session.store(credentials(), server = "https://books.example.net")
        for (url in listOf(
            "https://books.example.net/api/v1/books/1/thumbnail?t=1",
            "https://BOOKS.example.net:443/api/v1/auth/me",
            "https://books.example.net",
        )) assertTrue(url, api.isOwnServer(url.toHttpUrl()))
        for (url in listOf(
            "https://books.example.net@evil.example/c.jpg",
            "https://books.example.net.evil.example/c.jpg",
            "https://books.example.net:8443/api/v1/books/1",
            "http://books.example.net/api/v1/books/1",
            "https://user:pw@books.example.net/api/v1/books/1",
        )) assertFalse(url, api.isOwnServer(url.toHttpUrl()))

        // A server under a path: only that path counts.
        session.store(credentials(), server = "https://example.net/books/")
        assertTrue(api.isOwnServer("https://example.net/books/api/v1/auth/me".toHttpUrl()))
        assertFalse(api.isOwnServer("https://example.net/bookshelf/c.jpg".toHttpUrl()))
        assertFalse(api.isOwnServer("https://example.net/c.jpg".toHttpUrl()))

        assertEquals("https://example.net/books/a/1.jpg", api.serverUrl("/a/1.jpg"))
        try {
            api.serverUrl("@evil.example/a.jpg")
            fail("a relative URL that isn't a path should have been refused")
        } catch (_: IOException) {
        }
    }

    private fun credentials() = NativeCredentials(
        accessToken = "access-1",
        accessTokenExpiresAt = "2026-09-21T14:13:20Z",
        refreshToken = "refresh-1",
        refreshTokenExpiresAt = "2026-10-21T14:13:20Z",
        user = io.github.ottershelf.core.model.AuthUser(id = 1, username = "reader"),
    )
}
