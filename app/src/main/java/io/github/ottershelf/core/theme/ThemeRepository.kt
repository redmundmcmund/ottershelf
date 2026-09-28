package io.github.ottershelf.core.theme

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.UserSettings

/**
 * The appearance settings ([ThemePrefs]): kept on the device in the settings DataStore and, when
 * the account is in sync mode, on the server, following the web client exactly
 * (client/src/composables/useThemeSync.ts, features/settings/AppearancePreferenceStorage.vue):
 *
 * - **Local mode** (`AuthUser.settings.syncThemePreferences` missing or false): the device copy is
 *   the only copy; nothing is sent.
 * - **Sync mode**, once per signed-in account and process (the web: on page load and on login):
 *   `GET user-preferences/theme`. A null answer changes nothing; otherwise every valid field
 *   replaces the device value (invalid ones are ignored field by field). The server wins, and
 *   applying its values never sends them back. Only screens use the appearance, so AppContainer
 *   calls [start] when the first activity is created: a process started for a worker, an alarm or
 *   boot never reads it or asks the server.
 * - **Changes** ([update]) are saved on the device at once and, in sync mode, sent as the whole
 *   object 1.5 s after the last change (`PUT user-preferences/theme`). A failed save is reported on
 *   [events] and not retried, as on the web. A save still waiting when the app leaves the screen is
 *   sent straight away ([flushPendingSave], the web's `pagehide`); signing out cancels it.
 * - **Switching modes** ([setStorageMode]): `PATCH users/me/theme-storage-mode`, then, when
 *   switching to sync, the server's preferences win if it has any; otherwise the device's are
 *   uploaded (with surfaceOpacity, which the web's seed leaves out by mistake). Switching to local
 *   keeps the current values on the device.
 *
 * Main-thread confined: call everything from the main thread ([scope] runs on it).
 */
class ThemeRepository(
    private val store: DataStore<Preferences>,
    private val remote: ThemeRemote,
    /** The signed-in user, or null while signed out. */
    private val account: StateFlow<AuthUser?>,
    /** Stores a changed user (its settings) as the app's current user. */
    private val updateUser: (AuthUser) -> Unit,
    private val scope: CoroutineScope,
    private val saveDelayMs: Long = SAVE_DELAY_MS,
) {
    private val _prefs = MutableStateFlow(ThemePrefs())

    /** The current preferences: the web defaults until the device copy is read ([loaded]). */
    val prefs: StateFlow<ThemePrefs> = _prefs.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /** Whether the device copy has been read, so the first frame can wait for the real theme. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Whether the signed-in account keeps its appearance on the server. */
    val syncEnabled: StateFlow<Boolean> = account
        .map { it?.syncsTheme == true }
        .stateIn(scope, SharingStarted.Eagerly, account.value?.syncsTheme == true)

    private val _events = MutableSharedFlow<ThemeSyncEvent>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Failures worth telling the user about (the web toasts them). */
    val events: SharedFlow<ThemeSyncEvent> = _events.asSharedFlow()

    private var pendingSave: Job? = null
    private var changedBeforeLoad = false
    /** The account whose server preferences were already applied in this process. */
    private var hydratedFor: Long? = null

    /** Reads the device copy, then follows the account to hydrate from the server in sync mode. */
    fun start() {
        scope.launch {
            val stored = try {
                store.data.first()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (stored != null && !changedBeforeLoad) _prefs.value = stored.toThemePrefs()
            _loaded.value = true
        }
        scope.launch {
            account
                .map { user -> user?.let { it.id to it.syncsTheme } }
                .distinctUntilChanged()
                .collect { key ->
                    when {
                        key == null -> {
                            cancelPendingSave()
                            hydratedFor = null
                        }
                        !key.second -> hydratedFor = null
                        hydratedFor != key.first -> {
                            hydratedFor = key.first
                            _loaded.first { it }
                            loadFromServer()
                        }
                    }
                }
        }
    }

    /** Changes the preferences (clamped), saves them on the device and, in sync mode, soon on the server. */
    fun update(transform: (ThemePrefs) -> ThemePrefs) {
        val old = _prefs.value
        val next = transform(old).clamped()
        if (next == old) return
        changedBeforeLoad = changedBeforeLoad || !_loaded.value
        _prefs.value = next
        persist()
        if (syncEnabled.value) scheduleSave()
    }

    /**
     * Switches between keeping the preferences on this device and on the account. Throws the Api's
     * exceptions if the server refuses (demo accounts can't switch) or can't be reached.
     */
    suspend fun setStorageMode(sync: Boolean) {
        val user = account.value ?: return
        if (user.syncsTheme == sync) return
        remote.setThemeStorageMode(ThemeStorageModeBody(sync))
        if (sync) hydratedFor = user.id // handled here, not by the account watcher
        updateUser(user.copy(settings = (user.settings ?: UserSettings()).copy(syncThemePreferences = sync)))
        if (!sync) return
        val server = remote.themePreferences()
        if (server == null) {
            try {
                remote.saveThemePreferences(_prefs.value.toBody())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // As the web's seed: silent.
            }
        } else {
            applyFromServer(server)
        }
    }

    /** `loadFromServer`: the server's valid fields replace the device's; silent if it can't be reached. */
    suspend fun loadFromServer() {
        val server = try {
            remote.themePreferences()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        } ?: return
        applyFromServer(server)
    }

    /** Sends a save that is still waiting for its delay now (the app is leaving the screen). */
    fun flushPendingSave() {
        if (pendingSave == null || !syncEnabled.value) return
        pendingSave?.cancel()
        pendingSave = null
        scope.launch { saveToServer() }
    }

    /** Drops a save that is still waiting (signing out). */
    fun cancelPendingSave() {
        pendingSave?.cancel()
        pendingSave = null
    }

    private fun applyFromServer(server: JsonObject) {
        val next = _prefs.value.withServerFields(server)
        if (next == _prefs.value) return
        _prefs.value = next
        persist()
    }

    private fun scheduleSave() {
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(saveDelayMs)
            pendingSave = null
            // Not part of this job: a later change must not cancel a save already on its way.
            scope.launch { saveToServer() }
        }
    }

    private suspend fun saveToServer() {
        if (!syncEnabled.value) return
        try {
            remote.saveThemePreferences(_prefs.value.toBody())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _events.tryEmit(ThemeSyncEvent.SaveFailed)
        }
    }

    /** Always writes the newest value, so the order the writes land in doesn't matter. */
    private fun persist() {
        scope.launch {
            try {
                store.edit { it.write(_prefs.value) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The in-memory value still applies; the next change writes again.
            }
        }
    }

    companion object {
        /** The web's debounce before a change is sent. */
        const val SAVE_DELAY_MS = 1_500L

        // Declared here (the theme owns them), prefixed like every settings key.
        private val THEME = stringPreferencesKey("theme.theme")
        private val ACCENT = stringPreferencesKey("theme.accent")
        private val RADIUS = stringPreferencesKey("theme.radius")
        private val BACKGROUND = stringPreferencesKey("theme.background")
        private val BRIGHTNESS = intPreferencesKey("theme.brightness")
        private val SURFACE_OPACITY = intPreferencesKey("theme.surfaceOpacity")

        /** As the web store reads localStorage, but clamping brightness too (the web forgets to). */
        internal fun Preferences.toThemePrefs() = ThemePrefs(
            theme = ThemeMode.fromId(this[THEME]) ?: ThemeMode.SYSTEM,
            accent = Accent.fromId(this[ACCENT]) ?: Accent.DEFAULT,
            radius = Radius.fromId(this[RADIUS]) ?: Radius.DEFAULT,
            background = this[BACKGROUND]?.let { ThemeBackground.fromId(it) ?: ThemeBackground.INVALID_FALLBACK }
                ?: ThemeBackground.DEFAULT,
            brightness = this[BRIGHTNESS] ?: ThemePrefs.DEFAULT_BRIGHTNESS,
            surfaceOpacity = this[SURFACE_OPACITY] ?: ThemePrefs.DEFAULT_SURFACE_OPACITY,
        ).clamped()

        private fun MutablePreferences.write(prefs: ThemePrefs) {
            this[THEME] = prefs.theme.id
            this[ACCENT] = prefs.accent.id
            this[RADIUS] = prefs.radius.id
            this[BACKGROUND] = prefs.background.id
            this[BRIGHTNESS] = prefs.brightness
            this[SURFACE_OPACITY] = prefs.surfaceOpacity
        }
    }
}

sealed interface ThemeSyncEvent {
    /** A change couldn't be saved to the account ("Failed to save theme preferences" on the web). */
    data object SaveFailed : ThemeSyncEvent
}
