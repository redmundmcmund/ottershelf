package io.github.ottershelf.core.theme

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.UserSettings
import io.github.ottershelf.core.network.ApiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** ThemeRepository against a fake server and an in-memory DataStore, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThemeRepositoryTest {

    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private class FakeRemote : ThemeRemote {
        var stored: JsonObject? = null
        val puts = mutableListOf<ThemePreferencesDto>()
        val modes = mutableListOf<Boolean>()
        var gets = 0
        var failPut = false

        override suspend fun themePreferences(): JsonObject? {
            gets++
            return stored
        }

        override suspend fun saveThemePreferences(body: ThemePreferencesBody) {
            if (failPut) throw IOException("offline")
            puts += body.settings
        }

        override suspend fun setThemeStorageMode(body: ThemeStorageModeBody) {
            modes += body.sync
        }
    }

    private class Setup(
        val repo: ThemeRepository,
        val store: MemoryStore,
        val remote: FakeRemote,
        val account: MutableStateFlow<AuthUser?>,
    )

    /** Runs everything due in the next few seconds, the repository's own (background) work included. */
    private fun TestScope.settle() {
        advanceTimeBy(5_000)
        runCurrent()
    }

    private fun user(sync: Boolean?, id: Long = 1) =
        AuthUser(id = id, username = "reader", settings = if (sync == null) null else UserSettings(sync))

    private fun TestScope.setup(
        user: AuthUser? = user(sync = false),
        stored: Preferences = emptyPreferences(),
        server: JsonObject? = null,
    ): Setup {
        val store = MemoryStore(stored)
        val remote = FakeRemote().apply { this.stored = server }
        val account = MutableStateFlow(user)
        val repo = ThemeRepository(store, remote, account, { account.value = it }, backgroundScope)
        repo.start()
        runCurrent()
        return Setup(repo, store, remote, account)
    }

    @Test
    fun defaultsAreTheWebsWhenNothingIsStored() = runTest {
        val s = setup()
        assertTrue(s.repo.loaded.value)
        assertEquals(ThemePrefs(), s.repo.prefs.value)
        assertEquals(ThemeBackground.VINYL, s.repo.prefs.value.background)
        assertEquals(Accent.BLUE, s.repo.prefs.value.accent)
        assertEquals(35, s.repo.prefs.value.brightness)
        assertEquals(92, s.repo.prefs.value.surfaceOpacity)
    }

    @Test
    fun invalidStoredValuesFallBackAsTheWebDoes() = runTest {
        val stored = mutablePreferencesOf(
            stringPreferencesKey("theme.theme") to "sepia",
            stringPreferencesKey("theme.accent") to "chartreuse",
            stringPreferencesKey("theme.radius") to "huge",
            stringPreferencesKey("theme.background") to "stars",
            intPreferencesKey("theme.brightness") to 250,
            intPreferencesKey("theme.surfaceOpacity") to 10,
        )
        val prefs = setup(stored = stored).repo.prefs.value
        assertEquals(ThemeMode.SYSTEM, prefs.theme)
        assertEquals(Accent.CHARTREUSE, prefs.accent)
        assertEquals(Radius.DEFAULT, prefs.radius)
        assertEquals(ThemeBackground.DOTS, prefs.background) // invalid, not missing
        assertEquals(100, prefs.brightness)
        assertEquals(80, prefs.surfaceOpacity)
    }

    @Test
    fun localModeSavesOnTheDeviceOnly() = runTest {
        val s = setup(user = user(sync = null))
        s.repo.update { it.copy(accent = Accent.IRIS, radius = Radius.PILL) }
        settle()
        assertEquals(Accent.IRIS, s.repo.prefs.value.accent)
        assertEquals("iris", s.store.state.value[stringPreferencesKey("theme.accent")])
        assertEquals("pill", s.store.state.value[stringPreferencesKey("theme.radius")])
        assertTrue(s.remote.puts.isEmpty())
        assertEquals(0, s.remote.gets)
    }

    @Test
    fun syncModeSendsTheWholeObjectOnceChangesSettle() = runTest {
        val s = setup(user = user(sync = true))
        s.repo.update { it.copy(accent = Accent.IRIS) }
        advanceTimeBy(1_000)
        s.repo.update { it.copy(brightness = 60) }
        advanceTimeBy(1_400)
        runCurrent()
        assertTrue("debounced", s.remote.puts.isEmpty())
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(ThemePreferencesDto("system", "iris", "default", "vinyl", 60, 92)), s.remote.puts)
    }

    @Test
    fun serverValuesWinFieldByFieldAndAreNotSentBack() = runTest {
        val server = buildJsonObject {
            put("theme", "dark")
            put("accent", "periwinkle")
            put("radius", "enormous")
            put("background", 5)
            put("brightness", 50.5)
            put("surfaceOpacity", 85)
            put("extra", true)
        }
        val s = setup(user = user(sync = true), server = server)
        settle()
        val prefs = s.repo.prefs.value
        assertEquals(ThemeMode.DARK, prefs.theme)
        assertEquals(Accent.PERIWINKLE, prefs.accent)
        assertEquals(Radius.DEFAULT, prefs.radius)
        assertEquals(ThemeBackground.VINYL, prefs.background)
        assertEquals(35, prefs.brightness)
        assertEquals(85, prefs.surfaceOpacity)
        assertEquals("periwinkle", s.store.state.value[stringPreferencesKey("theme.accent")])
        assertTrue(s.remote.puts.isEmpty())
    }

    @Test
    fun anAccountWithoutServerPreferencesKeepsTheDevicesOnes() = runTest {
        val stored = mutablePreferencesOf(stringPreferencesKey("theme.accent") to "jade")
        val s = setup(user = user(sync = true), stored = stored, server = null)
        settle()
        assertEquals(1, s.remote.gets)
        assertEquals(Accent.JADE, s.repo.prefs.value.accent)
        assertTrue(s.remote.puts.isEmpty())
    }

    @Test
    fun hydratesOncePerAccountAndAgainAfterSyncIsTurnedOnElsewhere() = runTest {
        val s = setup(user = user(sync = true), server = buildJsonObject { put("accent", "teal") })
        settle()
        assertEquals(1, s.remote.gets)
        s.account.value = user(sync = true).copy(name = "Refreshed") // refreshUser, same flag
        settle()
        assertEquals(1, s.remote.gets)
        s.account.value = user(sync = false)
        settle()
        s.account.value = user(sync = true)
        settle()
        assertEquals(2, s.remote.gets)
    }

    @Test
    fun leavingTheScreenSendsAWaitingSaveAtOnce() = runTest {
        val s = setup(user = user(sync = true))
        s.repo.update { it.copy(theme = ThemeMode.LIGHT) }
        runCurrent()
        s.repo.flushPendingSave()
        runCurrent()
        assertEquals(1, s.remote.puts.size)
        settle()
        assertEquals("the debounced save was replaced, not repeated", 1, s.remote.puts.size)
    }

    @Test
    fun signingOutDropsAWaitingSave() = runTest {
        val s = setup(user = user(sync = true))
        s.repo.update { it.copy(theme = ThemeMode.LIGHT) }
        runCurrent()
        s.account.value = null
        settle()
        assertTrue(s.remote.puts.isEmpty())
        assertEquals(ThemeMode.LIGHT, s.repo.prefs.value.theme) // still on the device
    }

    @Test
    fun aFailedSaveIsReported() = runTest {
        val s = setup(user = user(sync = true))
        s.remote.failPut = true
        val events = mutableListOf<ThemeSyncEvent>()
        backgroundScope.launch { s.repo.events.collect { events += it } }
        runCurrent()
        s.repo.update { it.copy(theme = ThemeMode.DARK) }
        settle()
        assertEquals(listOf<ThemeSyncEvent>(ThemeSyncEvent.SaveFailed), events)
    }

    @Test
    fun switchingToSyncSeedsAnEmptyAccountWithTheDevicesPreferences() = runTest {
        val s = setup(user = user(sync = false))
        s.repo.update { it.copy(accent = Accent.CORAL) }
        settle()
        s.repo.setStorageMode(true)
        settle()
        assertEquals(listOf(true), s.remote.modes)
        assertTrue(s.account.value!!.syncsTheme)
        assertTrue(s.repo.syncEnabled.value)
        assertEquals("its own check, no second hydrate", 1, s.remote.gets)
        assertEquals(listOf(ThemePreferencesDto("system", "coral", "default", "vinyl", 35, 92)), s.remote.puts)
    }

    @Test
    fun switchingToSyncTakesTheAccountsPreferencesWhenItHasSome() = runTest {
        val s = setup(user = user(sync = false), server = buildJsonObject { put("accent", "lavender") })
        s.repo.update { it.copy(accent = Accent.CORAL) }
        settle()
        s.repo.setStorageMode(true)
        settle()
        assertEquals(Accent.LAVENDER, s.repo.prefs.value.accent)
        assertTrue(s.remote.puts.isEmpty())
    }

    @Test
    fun switchingToLocalKeepsTheCurrentValues() = runTest {
        val s = setup(user = user(sync = true), server = buildJsonObject { put("accent", "mint") })
        settle()
        s.repo.setStorageMode(false)
        settle()
        assertEquals(listOf(false), s.remote.modes)
        assertFalse(s.repo.syncEnabled.value)
        assertEquals(Accent.MINT, s.repo.prefs.value.accent)
        s.repo.update { it.copy(accent = Accent.SAGE) }
        settle()
        assertTrue(s.remote.puts.isEmpty())
    }

    @Test
    fun bodiesMatchTheServersStrictSchemas() {
        val body = ThemePrefs(brightness = 140, surfaceOpacity = 50).toBody()
        assertEquals(
            """{"settings":{"theme":"system","accent":"blue","radius":"default","background":"vinyl","brightness":100,"surfaceOpacity":80}}""",
            ApiJson.encodeToString(ThemePreferencesBody.serializer(), body),
        )
        assertEquals("""{"sync":true}""", ApiJson.encodeToString(ThemeStorageModeBody.serializer(), ThemeStorageModeBody(true)))
    }

    @Test
    fun everyIdMatchesTheSharedTypes() {
        assertEquals(64, Accent.entries.size)
        assertEquals(64, Accent.entries.map { it.id }.toSet().size)
        assertEquals(20, ThemeBackground.entries.size)
        assertEquals(listOf("sharp", "default", "rounded", "pill"), Radius.entries.map { it.id })
        assertEquals(listOf("light", "dark", "system"), ThemeMode.entries.map { it.id })
        assertEquals(32, Accent.pairs.size)
        assertTrue(Accent.pairs.all { (vivid, pastel) -> vivid.hue == pastel.hue })
        assertEquals(4, Accent.rows.size)
        assertTrue(Accent.rows.all { it.size == 16 })
    }
}
