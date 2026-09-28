package io.github.ottershelf.core.session

import android.content.Context
import android.os.Looper
import io.github.ottershelf.core.model.NativeCredentials
import io.github.ottershelf.core.util.IsoTime
import java.io.IOException

/**
 * Server URL and native-session credentials, persisted in private prefs. The access and refresh
 * tokens are encrypted with [cipher] (an Android Keystore key) before they are written; everything
 * else is stored as is. Each token is decrypted the first time it is read, off the main thread (see
 * [isSignedIn]), and then kept in memory, since the auth interceptor reads the access token on
 * every request. An access token that has expired is never read (Api.currentAccessToken), so it
 * isn't decrypted either.
 */
class Session(context: Context, private val cipher: TokenCipher) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    private val lock = Any()

    /**
     * One stored token: decrypted (a Keystore round trip) under [lock] the first time it is read,
     * then kept. [store] and [clearCredentials] [set] it, under [lock] too.
     */
    private inner class Token(private val key: String) {
        @Volatile var read = false
            private set
        @Volatile private var value: String? = null

        fun get(): String? {
            if (!read) synchronized(lock) {
                if (!read) {
                    value = prefs.getString(key, null)?.let(cipher::decrypt)
                    read = true
                }
            }
            return value
        }

        fun set(token: String?) {
            value = token
            read = true
        }
    }

    private val access = Token(ACCESS)
    private val refresh = Token(REFRESH)

    /**
     * The server the stored tokens belong to. No setter: only [store] writes it, together with the
     * tokens of a sign-in made there, so no other server can be paired with them.
     */
    val serverUrl: String? get() = prefs.getString(SERVER, null)

    val accessToken: String? get() = access.get()
    val accessExpiresAtMs: Long get() = prefs.getLong("accessExp", 0L)
    val refreshToken: String? get() = refresh.get()
    val username: String? get() = prefs.getString("user", null)

    /**
     * Whether a sign-in is stored whose refresh token can be decrypted (one that can't counts as
     * signed out). Decrypting is a Keystore round trip, so on the main thread, until the refresh
     * token has been read, this only checks that one is stored: AppContainer.start reads it on an IO
     * thread at app start and signs out if it can't be decrypted. Any other thread (a worker, an
     * OkHttp call) decrypts first, so nothing runs in the background for a sign-in that can't be
     * read.
     */
    val isSignedIn: Boolean get() = serverUrl != null &&
        (if (refresh.read || !Looper.getMainLooper().isCurrentThread) refreshToken != null else prefs.contains(REFRESH))

    @Volatile private var generation = 0

    /**
     * Changes whenever the credentials are cleared. A refresh reads it before it starts and passes
     * it to [store], so tokens refreshed while the user signed out aren't stored again, and to
     * [clearCredentials], so a rejection of those old tokens can't sign out a newer session.
     */
    val credentialsGeneration: Int get() = generation

    /**
     * Stores [creds], unless [ifGeneration] is given and the credentials were cleared since it was
     * read; returns whether they were stored. A Keystore failure is thrown as an IOException, like
     * any other failure the network code expects.
     *
     * [server]: the server a sign-in was made at, written in the same commit as its tokens. It is
     * kept only once a sign-in there has worked, so a failed attempt at another server can't pair
     * that server with the tokens already stored.
     */
    fun store(creds: NativeCredentials, ifGeneration: Int? = null, server: String? = null): Boolean {
        synchronized(lock) {
            if (ifGeneration != null && ifGeneration != generation) return false
            val (encryptedAccess, encryptedRefresh) = try {
                encrypt(creds)
            } catch (_: Exception) {
                // A key that can't encrypt any more (its Keystore copy broken by a system update,
                // say) would fail every sign-in until the app's data was cleared. Both tokens are
                // being replaced, so nothing it encrypted is still needed: a new key, once. (Not
                // when a token can't be decrypted: that may pass, and the tokens with it.)
                try {
                    cipher.replaceKey()
                    encrypt(creds)
                } catch (e: Exception) {
                    throw IOException("Can't store the sign-in on this device", e)
                }
            }
            val editor = prefs.edit()
                .putString(ACCESS, encryptedAccess)
                .putLong("accessExp", IsoTime.parse(creds.accessTokenExpiresAt) ?: (System.currentTimeMillis() + 10 * 60_000))
                .putString(REFRESH, encryptedRefresh)
            creds.user?.let { editor.putString("user", it.username) }
            server?.let { editor.putString(SERVER, it.trimEnd('/')) }
            // commit, not apply: a rotated refresh token must never be lost if the process dies.
            editor.commit()
            access.set(creds.accessToken)
            refresh.set(creds.refreshToken)
            return true
        }
    }

    private fun encrypt(creds: NativeCredentials) = cipher.encrypt(creds.accessToken) to cipher.encrypt(creds.refreshToken)

    /**
     * Forgets the tokens, unless [ifGeneration] is given and they were cleared since it was read (a
     * refresh of the previous session, rejected after the user signed out and in again); returns whether
     * they were cleared.
     */
    fun clearCredentials(ifGeneration: Int? = null): Boolean {
        synchronized(lock) {
            if (ifGeneration != null && ifGeneration != generation) return false
            generation++
            prefs.edit().remove(ACCESS).remove("accessExp").remove(REFRESH).commit()
            access.set(null)
            refresh.set(null)
            return true
        }
    }

    /**
     * Library this user's book requests are sent to, or null for the server's instance default.
     * Kept per server and user: BookOrbit has no per-user request destination of its own.
     */
    var requestLibraryId: Long?
        get() = prefs.getLong(requestLibraryKey(), -1L).takeIf { it > 0 }
        set(value) = prefs.edit().apply {
            if (value == null) remove(requestLibraryKey()) else putLong(requestLibraryKey(), value)
        }.apply()

    private fun requestLibraryKey() = "requestLibrary:${serverUrl.orEmpty()}:${username.orEmpty()}"

    /** File-name-safe id for the signed-in account (server + user), for per-account storage. */
    fun accountKey(): String =
        "${serverUrl.orEmpty().substringAfter("://")}_${username.orEmpty()}".replace(UNSAFE, "_")

    /** The signed-in user (`AuthUser` JSON) as last fetched. */
    var userJson: String?
        get() = prefs.getString("me", null)
        set(value) = prefs.edit().putString("me", value).apply()

    companion object {
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")
        private const val SERVER = "server"
        private const val ACCESS = "access.enc"
        private const val REFRESH = "refresh.enc"
    }
}
