package io.github.ottershelf.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.UserSettings
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.tracking.TrackingRemote

/**
 * The account's [AppSettings] (users.settings `bookorbitAndroid`), readable at once and safe to
 * change offline.
 *
 * - **Reading:** [settings] starts from this device's copy (DataStore, per account), else from the
 *   signed-in user as last fetched (`GET auth/me`, cached by AuthState). Whenever a fresher user
 *   arrives (the shell and the book page fetch `auth/me`) and nothing is waiting to be sent, its
 *   value replaces the copy.
 * - **Writing:** [update] changes the copy at once (and on the device), then sends the whole key
 *   [saveDelayMs] after the last change: `GET auth/me`, a three-way merge ([JsonMerge]) of what
 *   this device last saw, what it wants and what the server has now, then
 *   `PATCH users/me/settings {settings: {bookorbitAndroid: ...}}`. The server replaces only that
 *   top-level key (a shallow jsonb merge), so no other setting is touched. A change made on both
 *   sides goes to the last writer. A change rewrites only what it changed in the stored value
 *   ([AppSettings.encodeChange]), so a part this app can't read (a newer app's value) survives it.
 * - **Offline:** a failed send stays waiting (on the device, so it survives the app being killed)
 *   and [onQueued] asks WorkManager to retry when connected ([flush] from TrackingWorker). A save
 *   still waiting when the app leaves the screen is started straight away ([flushSoon]).
 * - **Size:** past [MAX_BYTES] the oldest hand-set pages are dropped before sending.
 */
class AppSettingsRepository(
    private val store: DataStore<Preferences>,
    private val remote: TrackingRemote,
    /** The signed-in user, or null while signed out. */
    private val account: StateFlow<AuthUser?>,
    private val accountKey: () -> String,
    /** Stores a changed user (its settings) as the app's current user. */
    private val updateUser: (AuthUser) -> Unit,
    private val scope: CoroutineScope,
    /** Schedules a background retry (WorkManager, when connected). */
    private val onQueued: () -> Unit,
    private val saveDelayMs: Long = SAVE_DELAY_MS,
) {
    /** What the device keeps per account: the server value last seen, the wanted value, and whether it still has to go. */
    @Serializable
    internal data class Stored(val base: JsonObject? = null, val local: JsonObject? = null, val dirty: Boolean = false)

    private val lock = Any()
    private var stored = Stored()
    private var storedFor: String? = null

    private val _settings = MutableStateFlow(AppSettings())

    /** The current settings (defaults until loaded). */
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /** Whether this account's copy has been read. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val flushMutex = Mutex()
    private val persistMutex = Mutex()
    private var pendingSave: Job? = null

    /** Follows the signed-in account: loads its copy, then adopts fresher server values. */
    fun start() {
        scope.launch {
            account.collect { user ->
                if (user == null) {
                    pendingSave?.cancel()
                    synchronized(lock) {
                        storedFor = null
                        stored = Stored()
                    }
                    _settings.value = AppSettings()
                    _loaded.value = false
                    return@collect
                }
                val key = accountKey()
                if (storedFor != key) load(key, user)
                adopt(user)
            }
        }
    }

    private suspend fun load(key: String, user: AuthUser) {
        val saved = try {
            store.data.first()[prefKey(key)]?.let { ApiJson.decodeFromString(Stored.serializer(), it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val server = user.settings?.bookorbitAndroid
        synchronized(lock) {
            storedFor = key
            stored = saved ?: Stored(base = server, local = server)
        }
        publish()
        _loaded.value = true
        if (saved?.dirty == true) flushSoon()
    }

    /** A fresher user from the server: its value wins unless this device has changes waiting. */
    private fun adopt(user: AuthUser) {
        val server = user.settings?.bookorbitAndroid
        val changed = synchronized(lock) {
            if (stored.dirty || flushMutex.isLocked || server == stored.base) {
                false
            } else {
                stored = Stored(base = server, local = server)
                true
            }
        }
        if (changed) {
            publish()
            persist()
        }
    }

    /** Changes the settings here at once and on the account soon (see the class comment). */
    fun update(transform: (AppSettings) -> AppSettings) {
        val changed = synchronized(lock) {
            if (storedFor == null) return
            val old = AppSettings.decode(stored.local)
            val next = transform(old)
            if (next == old) {
                false
            } else {
                stored = stored.copy(local = AppSettings.encodeChange(stored.local, old, next), dirty = true)
                true
            }
        }
        if (!changed) return
        publish()
        persist()
        scheduleSave()
    }

    /** Whether a change is still waiting to reach the server. */
    fun hasUnsent(): Boolean = synchronized(lock) { stored.dirty }

    /** Sends a waiting change now (the app left the screen, or a screen wants it on the server). */
    fun flushSoon() {
        if (!hasUnsent()) return
        pendingSave?.cancel()
        pendingSave = scope.launch { sendOrQueue() }
    }

    private fun scheduleSave() {
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(saveDelayMs)
            sendOrQueue()
        }
    }

    private suspend fun sendOrQueue() {
        try {
            if (!flush()) onQueued()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            onQueued()
        }
    }

    /**
     * Sends the waiting change, if any: read the server's value, merge, write the key whole.
     * Returns true when nothing is left to send. Throws the remote's exceptions (offline, HTTP).
     */
    suspend fun flush(): Boolean = withContext(NonCancellable) {
        // A worker may run before this process has read the account's copy.
        if (account.value != null) withTimeoutOrNull(LOAD_WAIT_MS) { _loaded.first { it } }
        flushMutex.withLock {
            val (snapshot, key) = synchronized(lock) { stored to storedFor }
            if (!snapshot.dirty || key == null) return@withLock true
            val user = remote.me()
            if (accountKey() != key) return@withLock true // signed out or switched meanwhile
            val remoteValue = user.settings?.bookorbitAndroid
            // Never having seen the key (a cached user without it, an offline start), this device
            // started from the defaults: only what it changed from them is its own change. Every
            // other field (another device's timer settings, a newer app's `v`) stays the server's.
            // Likewise a field the writer of the base or of the wanted value didn't know yet (an
            // older app version) counts as its default, not as absent, on both sides: a change
            // writes only the fields it changed, so an untouched field absent from one and at its
            // default in the other would otherwise look changed and undo another device's change.
            val defaults = AppSettings.encodeOver(null, AppSettings())
            fun withDefaults(seen: JsonObject?) = seen?.let { JsonObject(defaults.filterKeys { it !in seen } + seen) } ?: defaults
            val merged = JsonMerge.merge(withDefaults(snapshot.base), withDefaults(snapshot.local), remoteValue) as? JsonObject
                ?: JsonObject(emptyMap())
            val toSend = pruned(merged)
            val saved = remote.patchSettings(buildJsonObject { put(AppSettings.KEY, toSend) })
            val serverValue = saved?.get(AppSettings.KEY) as? JsonObject ?: toSend
            updateUser(user.copy(settings = (user.settings ?: UserSettings()).copy(bookorbitAndroid = serverValue)))
            val done = synchronized(lock) {
                if (storedFor != key) return@withLock true
                val now = stored
                stored = if (now.local == snapshot.local) {
                    Stored(base = serverValue, local = serverValue, dirty = false)
                } else {
                    // Changed again while this was on its way: keep those changes on top.
                    val local = JsonMerge.merge(snapshot.local, now.local, serverValue) as? JsonObject
                    Stored(base = serverValue, local = local, dirty = true)
                }
                !stored.dirty
            }
            publish()
            persist()
            if (!done) scheduleSave()
            done
        }
    }

    private fun publish() {
        _settings.value = AppSettings.decode(synchronized(lock) { stored.local })
    }

    private fun persist() {
        scope.launch {
            persistMutex.withLock {
                // Always the latest snapshot, so the order of these writes doesn't matter.
                val (snapshot, key) = synchronized(lock) { stored to storedFor }
                if (key == null) return@withLock
                try {
                    store.edit { it[prefKey(key)] = ApiJson.encodeToString(Stored.serializer(), snapshot) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A full disk: the in-memory copy still goes to the server.
                }
            }
        }
    }

    /** Past [MAX_BYTES], drops the oldest hand-set pages (they are the only unbounded part that expires). */
    private fun pruned(value: JsonObject): JsonObject {
        if (value.toString().length <= MAX_BYTES) return value
        val read = AppSettings.decode(value)
        var settings = read
        var result = value
        val oldestFirst = settings.pageOverrides.entries.sortedBy { it.value.at }.map { it.key }
        for (bookId in oldestFirst) {
            settings = settings.copy(pageOverrides = settings.pageOverrides - bookId)
            result = AppSettings.encodeChange(value, read, settings)
            if (result.toString().length <= MAX_BYTES) break
        }
        return result
    }

    private fun prefKey(account: String) = stringPreferencesKey("tracking.settings.$account")

    companion object {
        const val SAVE_DELAY_MS = 1_500L
        const val MAX_BYTES = 32_000
        private const val LOAD_WAIT_MS = 5_000L
    }
}
