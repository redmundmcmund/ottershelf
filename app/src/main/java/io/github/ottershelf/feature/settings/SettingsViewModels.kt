package io.github.ottershelf.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.ottershelf.AppContainer
import io.github.ottershelf.BuildConfig
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.network.ApiException
import io.github.ottershelf.core.settings.AppSettings
import io.github.ottershelf.core.theme.Accent
import io.github.ottershelf.core.theme.Radius
import io.github.ottershelf.core.theme.ThemeBackground
import io.github.ottershelf.core.theme.ThemeMode
import io.github.ottershelf.core.theme.ThemePrefs

data class SettingsUiState(
    val username: String = "",
    /** The account's display name, when it has one other than the username. */
    val name: String? = null,
    /** The server without its scheme ("books.example.net"). */
    val server: String = "",
    val prefs: ThemePrefs = ThemePrefs(),
    val version: String = BuildConfig.VERSION_NAME,
    val debugBuild: Boolean = BuildConfig.DEBUG,
    val signingOut: Boolean = false,
    /** Offer the next book of a series when the user finishes one (AppSettings.nextInSeries, on the account). */
    val nextInSeries: Boolean = true,
)

/**
 * Settings: who is signed in and where, the way into Appearance, the next-in-series switch,
 * sign-out and the app version.
 * Sign-out is the Nexus one (cancel downloads, `auth/logout`, forget the session) plus this app's
 * own clean-up, all in [AppContainer.signOut]; the shell then shows Login.
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val signingOut = MutableStateFlow(false)

    val state: StateFlow<SettingsUiState> = combine(
        container.auth.user,
        container.themePrefs,
        signingOut,
        container.appSettings.settings,
    ) { user, prefs, busy, app ->
        settingsState(user, prefs, busy, app)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        settingsState(container.auth.user.value, container.themePrefs.value, false, container.appSettings.settings.value),
    )

    private fun settingsState(user: AuthUser?, prefs: ThemePrefs, busy: Boolean, app: AppSettings) = SettingsUiState(
        username = user?.username ?: container.session.username.orEmpty(),
        name = user?.name?.takeIf { it.isNotBlank() && it != user.username },
        server = container.session.serverUrl.orEmpty().substringAfter("://").trimEnd('/'),
        prefs = prefs,
        signingOut = busy,
        nextInSeries = app.nextInSeries,
    )

    /** Saved to the account's app settings (sent a moment later, or when back online). */
    fun setNextInSeries(on: Boolean) = container.appSettings.update { it.copy(nextInSeries = on) }

    fun signOut() {
        if (signingOut.value) return
        signingOut.value = true
        // In the app scope: the sign-out must finish even though this screen goes away with it.
        container.appScope.launch { container.signOut() }
    }
}

data class AppearanceUiState(
    val prefs: ThemePrefs = ThemePrefs(),
    /** Preferences follow the account (the web's `syncEnabled`: never for a demo account). */
    val sync: Boolean = false,
    /** A demo-restricted account can't switch where preferences are kept. */
    val demoRestricted: Boolean = false,
    /** A storage-mode switch is on its way to the server. */
    val switching: Boolean = false,
)

/** What the Appearance screen reports in a snackbar (the web's toasts). */
enum class AppearanceMessage { Synced, Local, DemoRestricted, UpdateFailed, Error }

/**
 * The web's Display > Theme settings (AppearanceThemeSettings.vue, AppearancePreferenceStorage.vue
 * and the header's SurfacePicker). Every change goes straight to [ThemeRepository], which applies
 * it at once, keeps it on the device and, in sync mode, sends it to the account 1.5 s later.
 */
class AppearanceViewModel(private val container: AppContainer) : ViewModel() {

    private val theme = container.theme
    private val switching = MutableStateFlow(false)
    private val _messages = Channel<AppearanceMessage>(Channel.BUFFERED)

    val messages: Flow<AppearanceMessage> = _messages.receiveAsFlow()

    val state: StateFlow<AppearanceUiState> =
        combine(theme.prefs, theme.syncEnabled, container.auth.user, switching) { prefs, sync, user, busy ->
            appearanceState(prefs, sync, user, busy)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            appearanceState(theme.prefs.value, theme.syncEnabled.value, container.auth.user.value, false),
        )

    private fun appearanceState(prefs: ThemePrefs, sync: Boolean, user: AuthUser?, busy: Boolean): AppearanceUiState {
        val demo = user?.permissions?.contains(DEMO_RESTRICTED) == true
        return AppearanceUiState(prefs = prefs, sync = sync && !demo, demoRestricted = demo, switching = busy)
    }

    fun setTheme(mode: ThemeMode) = theme.update { it.copy(theme = mode) }
    fun setAccent(accent: Accent) = theme.update { it.copy(accent = accent) }
    fun setRadius(radius: Radius) = theme.update { it.copy(radius = radius) }
    fun setBackground(background: ThemeBackground) = theme.update { it.copy(background = background) }
    fun setBrightness(value: Int) = theme.update { it.copy(brightness = value) }
    fun setSurfaceOpacity(value: Int) = theme.update { it.copy(surfaceOpacity = value) }

    /** The web's Reset: brightness 0 (not the 35 a fresh install starts with). */
    fun resetBrightness() = setBrightness(0)

    /** `handleSetStorageMode`: PATCH the mode, then load the account's preferences or seed them. */
    fun setStorageMode(sync: Boolean) {
        val current = state.value
        if (current.switching || current.sync == sync) return
        if (current.demoRestricted) {
            _messages.trySend(AppearanceMessage.DemoRestricted)
            return
        }
        switching.value = true
        // In the app scope, so leaving the screen can't stop it between the PATCH and the user update.
        container.appScope.launch {
            val message = try {
                theme.setStorageMode(sync)
                if (sync) AppearanceMessage.Synced else AppearanceMessage.Local
            } catch (e: CancellationException) {
                throw e
            } catch (_: ApiException) {
                AppearanceMessage.UpdateFailed
            } catch (_: Exception) {
                AppearanceMessage.Error
            } finally {
                switching.value = false
            }
            _messages.trySend(message)
        }
    }

    private companion object {
        /** Permission.DemoRestricted, checked explicitly (a superuser doesn't hold it implicitly). */
        const val DEMO_RESTRICTED = "demo_restricted"
    }
}
