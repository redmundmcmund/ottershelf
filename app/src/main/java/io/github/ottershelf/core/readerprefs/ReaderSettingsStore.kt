package io.github.ottershelf.core.readerprefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import io.github.ottershelf.core.network.ApiJson

/**
 * One reader's settings for one file, kept as the web keeps them (useReaderSettings.ts): the
 * built-in defaults, then the account's default for the format group, then the book's own changes
 * (only the keys changed for it, never a full snapshot). Get one from
 * `AppContainer.readerSettings(ReaderSettingsSpecs.Cbx, fileId)` (or `.Pdf`).
 *
 * - **Device copy** (the web's localStorage): the settings DataStore, `readerprefs.default.<account>.<group>`
 *   and `readerprefs.book.<account>.<fileId>`, JSON. Always written; read first by [load].
 * - **Sync mode** (`AuthUser.settings.syncReaderPreferences`): [load] then asks
 *   `reader/preferences/:fileId` and `reader/defaults`; what the server has replaces the device copy
 *   (a book the server has nothing for keeps the device's). Changes are sent as PATCHes of only the
 *   changed keys (so another client's fields are never overwritten); resets are DELETEs. Like the web,
 *   a send that fails is not retried: the device copy keeps the change.
 * - Everything read is sanitised with the web's rules ([ReaderSettingsSpec.sanitize]).
 *
 * Main thread (the writes run in [scope], the app scope, so closing the reader doesn't cut them off).
 */
class ReaderSettingsStore<T>(
    private val spec: ReaderSettingsSpec<T>,
    private val fileId: Long,
    private val store: DataStore<Preferences>,
    private val remote: ReaderPrefsRemote,
    private val accountKey: () -> String,
    private val syncEnabled: () -> Boolean,
    private val scope: CoroutineScope,
) {
    private var default: JsonObject? = null
    private var book: JsonObject? = null

    private val _effective = MutableStateFlow(spec.defaults)
    /** What the reader applies. */
    val effective: StateFlow<T> = _effective.asStateFlow()

    private val _customized = MutableStateFlow(false)
    /** The book has settings of its own (the web's "Customised for this book", with Reset). */
    val customized: StateFlow<Boolean> = _customized.asStateFlow()

    private val defaultKey get() = stringPreferencesKey("readerprefs.default.${accountKey()}.${spec.group.id}")
    private val bookKey get() = stringPreferencesKey("readerprefs.book.${accountKey()}.$fileId")

    /** The device copy, then (sync mode) the server's. Never throws for the network. */
    suspend fun load() {
        val prefs = runCatching { store.data.first() }.getOrNull()
        default = prefs?.get(defaultKey)?.let(::parse)?.let(spec::sanitize)
        book = prefs?.get(bookKey)?.let(::parse)?.let(spec::sanitize)?.takeIf { it.isNotEmpty() }
        publish()
        if (!syncEnabled()) return
        coroutineScope {
            val pref = async { fetch { remote.preference(fileId) } }
            val defaults = async { fetch { remote.defaults() } }
            pref.await()?.settings?.let { raw ->
                book = spec.sanitize(raw).takeIf { it.isNotEmpty() }
                persist(bookKey, book)
            }
            defaults.await()?.get(spec.group.id)?.let { raw ->
                default = spec.sanitize(raw)
                persist(defaultKey, default)
            }
        }
        publish()
    }

    /** Changes this book's settings: `updateBook { it.copy(fitMode = "fit-width") }`. */
    fun updateBook(change: (T) -> T) {
        val patch = spec.changes(_effective.value, change(_effective.value))
        if (patch.isEmpty()) return
        book = JsonObject((book ?: JsonObject(emptyMap())) + patch)
        publish()
        persist(bookKey, book)
        if (syncEnabled()) send { remote.patchPreference(fileId, patch) }
    }

    /** The book follows the account default again. */
    fun resetBook() {
        book = null
        publish()
        persist(bookKey, null)
        if (syncEnabled()) send { remote.deletePreference(fileId) }
    }

    /** Changes the account's default for this format group (every book without its own value follows). */
    fun updateDefault(change: (T) -> T) {
        val before = spec.effective(default, null)
        val after = change(before)
        val patch = spec.changes(before, after)
        if (patch.isEmpty()) return
        default = spec.encode(after)
        publish()
        persist(defaultKey, default)
        if (syncEnabled()) send { remote.patchDefault(spec.group, patch) }
    }

    fun resetDefault() {
        default = null
        publish()
        persist(defaultKey, null)
        if (syncEnabled()) send { remote.deleteDefault(spec.group) }
    }

    private fun publish() {
        _effective.value = spec.effective(default, book)
        _customized.value = book?.isNotEmpty() == true
    }

    private fun persist(key: Preferences.Key<String>, value: JsonObject?) {
        scope.launch {
            runCatching {
                store.edit { if (value == null) it.remove(key) else it[key] = value.toString() }
            }
        }
    }

    private fun send(block: suspend () -> Unit) {
        scope.launch { fetch { block() } }
    }

    private suspend fun <R> fetch(block: suspend () -> R): R? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun parse(text: String): JsonObject? = runCatching { ApiJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
}
