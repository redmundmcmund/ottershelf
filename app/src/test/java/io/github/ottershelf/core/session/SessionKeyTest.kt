package io.github.ottershelf.core.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.NativeCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per-account storage keys: two accounts whose names read the same once made file-name safe never
 * share one (their downloads, queued progress and notes), while an account's existing key, and so
 * its data on the device, stays as it was.
 */
@RunWith(AndroidJUnit4::class)
class SessionKeyTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    private object Plain : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(stored: String): String? = stored
    }

    private fun creds(user: String?) = NativeCredentials(
        accessToken = "a",
        accessTokenExpiresAt = "2099-01-01T00:00:00Z",
        refreshToken = "r",
        refreshTokenExpiresAt = "2099-01-01T00:00:00Z",
        user = user?.let { AuthUser(id = 1, username = it) },
    )

    private fun signIn(session: Session, user: String): String {
        session.store(creds(user), server = SERVER)
        return session.accountKey()
    }

    @Before
    fun clean() {
        prefs.edit().clear().commit()
    }

    @Test
    fun aSafeNameKeepsTheKeyItAlwaysHad() {
        assertEquals("books.example.net_reader", signIn(Session(context, Plain), "reader"))
    }

    @Test
    fun twoNamesThatReadTheSameGetTheirOwnKeys() {
        val session = Session(context, Plain)
        val anna = signIn(session, "anna@x")
        assertEquals("books.example.net_anna_x", anna) // the first one keeps the readable key
        session.clearCredentials()
        session.forgetUser()
        val other = signIn(session, "anna_x")
        assertNotEquals(anna, other)
        assertEquals(true, other.startsWith("books.example.net_anna_x-"))
        // And each keeps its own on the next sign-in.
        session.clearCredentials()
        session.forgetUser()
        assertEquals(anna, signIn(session, "anna@x"))
        session.clearCredentials()
        session.forgetUser()
        assertEquals(other, signIn(session, "anna_x"))
    }

    @Test
    fun anInstallFromBeforeOwnersKeepsItsAccountsKey() {
        // As an older version left it: signed out, its name still stored, no owner record.
        prefs.edit().putString("server", SERVER).putString("user", "anna@x").commit()
        val session = Session(context, Plain) // claims for anna@x
        session.forgetUser()
        val other = signIn(session, "anna_x")
        assertNotEquals("books.example.net_anna_x", other)
    }

    @Test
    fun signingOutForgetsWhoButTheFormStillOffersTheName() {
        val session = Session(context, Plain)
        signIn(session, "reader")
        session.userJson = "{}"
        session.clearCredentials()
        session.forgetUser()
        assertNull(session.username)
        assertNull(session.userJson)
        assertEquals("reader", session.lastLoginName)
    }

    private companion object {
        const val SERVER = "https://books.example.net"
    }
}
