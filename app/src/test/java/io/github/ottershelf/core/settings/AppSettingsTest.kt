package io.github.ottershelf.core.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.model.UserSettings
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.tracking.FakeTrackingRemote
import io.github.ottershelf.core.tracking.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonMergeTest {

    private fun obj(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(
        pairs.associate { (k, v) ->
            k to when (v) {
                is JsonObject -> v
                is Int -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        },
    )

    @Test
    fun untouchedHereTakesTheServersValue() {
        val base = obj("a" to 1, "b" to 1)
        val remote = obj("a" to 2, "b" to 1)
        assertEquals(remote, JsonMerge.merge(base, base, remote))
    }

    @Test
    fun changesOnBothSidesMergeKeyByKey() {
        val base = obj("pages" to obj("1" to 100), "goal" to 20)
        val local = obj("pages" to obj("1" to 100, "2" to 250), "goal" to 20)
        val remote = obj("pages" to obj("1" to 120), "goal" to 30)
        val merged = JsonMerge.merge(base, local, remote) as JsonObject
        assertEquals(obj("1" to 120, "2" to 250), merged["pages"])
        assertEquals(JsonPrimitive(30), merged["goal"])
    }

    @Test
    fun aScalarChangedOnBothSidesGoesToTheLastWriter() {
        val merged = JsonMerge.merge(obj("goal" to 20), obj("goal" to 25), obj("goal" to 30)) as JsonObject
        assertEquals(JsonPrimitive(25), merged["goal"])
    }

    @Test
    fun aKeyRemovedHereStaysRemovedUnlessTheServerChangedIt() {
        val base = obj("pages" to obj("1" to 100, "2" to 200))
        val local = obj("pages" to obj("2" to 200))
        val merged = JsonMerge.merge(base, local, obj("pages" to obj("1" to 100, "2" to 200, "3" to 300))) as JsonObject
        assertEquals(obj("2" to 200, "3" to 300), merged["pages"])
    }
}

class AppSettingsModelTest {

    @Test
    fun roundTripsWithBookIdKeys() {
        val settings = AppSettings(
            pageTotals = mapOf(12L to 320),
            pageOverrides = mapOf(12L to PageOverride(45, 1_000)),
            progressUnits = mapOf(7L to ProgressUnit.PERCENT),
            dailyGoalMinutes = 30,
            timer = TimerPrefs(mode = TimerMode.COUNTDOWN, countdownMinutes = 20),
        )
        val json = AppSettings.encodeOver(null, settings)
        assertEquals(320, json["pageTotals"]!!.jsonObject["12"]!!.jsonPrimitive.int)
        assertEquals("countdown", json["timer"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals(settings, AppSettings.decode(json))
    }

    @Test
    fun keepsANewerAppsFieldsAndVersionAndDropsClearedOnes() {
        val newer = buildJsonObject {
            put("v", 3)
            put("dailyGoalMinutes", 40)
            put("wishlist", "kept")
        }
        val written = AppSettings.encodeOver(newer, AppSettings.decode(newer).copy(dailyGoalMinutes = null))
        assertEquals("kept", written["wishlist"]!!.jsonPrimitive.content)
        assertEquals(3, written["v"]!!.jsonPrimitive.int)
        assertNull(written["dailyGoalMinutes"])
    }

    @Test
    fun brokenOrMissingValuesAreDefaults() {
        assertEquals(AppSettings(), AppSettings.decode(null))
        assertEquals(AppSettings(), AppSettings.decode(buildJsonObject { put("pageTotals", "nonsense") }))
        // A broken field is its default on its own; it no longer resets the fields beside it.
        assertEquals(
            AppSettings(dailyGoalMinutes = 20),
            AppSettings.decode(buildJsonObject { put("pageTotals", "nonsense"); put("dailyGoalMinutes", 20) }),
        )
        assertEquals(250, AppSettings(pageTotals = mapOf(1L to 250)).pageTotal(1, 300))
        assertEquals(300, AppSettings().pageTotal(1, 300))
        assertNull(AppSettings().pageTotal(1, 0))
    }

    /** A key partly unreadable (a newer app's values, a bad server value) keeps everything else. */
    @Test
    fun aPartItCantReadFallsBackOnItsOwn() {
        val json = ApiJson.parseToJsonElement(
            """
            {"v": 1,
             "pageTotals": {"1": 250, "x": 3, "2": "many"},
             "pageOverrides": {"3": {"page": 4}, "4": {"page": 5, "at": 9}},
             "progressUnits": {"5": "chapter", "7": "percent"},
             "dailyGoalMinutes": "lots",
             "timer": {"mode": "countdown", "countdownMinutes": "long"},
             "notes": {"liked": {"1": 2, "b": 3}, "reviews": {"1": 2}},
             "quotePhotos": {"8": "8.jpg"},
             "dashboardOrder": ["w:goal", "s:4"],
             "nextInSeries": false}
            """,
        ).jsonObject
        assertEquals(
            AppSettings(
                pageTotals = mapOf(1L to 250),
                pageOverrides = mapOf(4L to PageOverride(5, 9)),
                progressUnits = mapOf(7L to ProgressUnit.PERCENT),
                dailyGoalMinutes = null,
                timer = TimerPrefs(mode = TimerMode.COUNTDOWN),
                notes = NotesPrefs(liked = mapOf(1L to 2L), reviews = mapOf(1L to 2)),
                quotePhotos = mapOf(8L to "8.jpg"),
                dashboardOrder = listOf("w:goal", "s:4"),
                nextInSeries = false,
            ),
            AppSettings.decode(json),
        )
    }

    /** A value nested far deeper than the model is dropped whole, not taken apart level by level. */
    @Test
    fun aDeeplyNestedBadValueIsDroppedWithoutFollowingItDown() {
        var deep: JsonElement = JsonPrimitive(1)
        repeat(20_000) { deep = JsonObject(mapOf("a" to deep)) }
        val json = JsonObject(
            mapOf(
                "pageTotals" to JsonObject(mapOf("1" to deep, "2" to JsonPrimitive(250))),
                "dailyGoalMinutes" to JsonPrimitive(20),
            ),
        )
        assertEquals(AppSettings(pageTotals = mapOf(2L to 250), dailyGoalMinutes = 20), AppSettings.decode(json))
    }

    @Test
    fun aChangeWritesOnlyWhatChanged() {
        val stored = ApiJson.parseToJsonElement(
            """{"v": 1, "pageTotals": {"1": 250}, "progressUnits": {"5": "chapter", "7": "percent"}, "wishlist": "kept"}""",
        ).jsonObject
        val old = AppSettings.decode(stored)
        assertEquals(mapOf(7L to ProgressUnit.PERCENT), old.progressUnits)

        val goal = AppSettings.encodeChange(stored, old, old.copy(dailyGoalMinutes = 30))
        assertEquals(JsonObject(stored + ("dailyGoalMinutes" to JsonPrimitive(30))), goal)

        // A change inside a map keeps the entries this app couldn't read.
        val unit = AppSettings.encodeChange(stored, old, old.copy(progressUnits = old.progressUnits + (9L to ProgressUnit.PAGE)))
        assertEquals(
            ApiJson.parseToJsonElement("""{"5": "chapter", "7": "percent", "9": "page"}"""),
            unit["progressUnits"],
        )
        val removed = AppSettings.encodeChange(stored, old, old.copy(pageTotals = emptyMap(), dailyGoalMinutes = null))
        assertEquals(JsonObject(emptyMap()), removed["pageTotals"])
        assertEquals("kept", removed["wishlist"]!!.jsonPrimitive.content)

        // Nothing stored yet: the whole model, with its version.
        assertEquals(AppSettings.encodeOver(null, AppSettings(dailyGoalMinutes = 5)), AppSettings.encodeChange(null, AppSettings(), AppSettings(dailyGoalMinutes = 5)))
        // A newer app's version stays.
        val newer = buildJsonObject { put("v", 3) }
        assertEquals(3, AppSettings.encodeChange(newer, AppSettings.decode(newer), AppSettings(v = 3, dailyGoalMinutes = 5))["v"]!!.jsonPrimitive.int)
    }

    /** Next in series is on for a key written before the switch existed, and off only when the user says so. */
    @Test
    fun nextInSeriesIsOnUnlessSwitchedOff() {
        assertTrue(AppSettings.decode(buildJsonObject { put("v", 1) }).nextInSeries)
        assertFalse(AppSettings.decode(buildJsonObject { put("nextInSeries", false) }).nextInSeries)
        val written = AppSettings.encodeOver(null, AppSettings(nextInSeries = false))
        assertFalse(AppSettings.decode(written).nextInSeries)
    }
}

/** AppSettingsRepository against a fake server and an in-memory DataStore, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsRepositoryTest {

    private class Setup(
        val repo: AppSettingsRepository,
        val remote: FakeTrackingRemote,
        val store: MemoryStore,
        val account: MutableStateFlow<AuthUser?>,
        val queued: IntArray,
    )

    private fun key(vararg pairs: Pair<String, Int>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    private fun TestScope.setup(serverKey: JsonObject? = null, store: MemoryStore = MemoryStore()): Setup {
        val remote = FakeTrackingRemote()
        remote.user = AuthUser(
            id = 1,
            username = "reader",
            settings = UserSettings(bookorbitAndroid = serverKey, dashboardConfig = buildJsonObject { put("readingGoal", 12) }),
        )
        val account = MutableStateFlow<AuthUser?>(remote.user)
        val queued = intArrayOf(0)
        val repo = AppSettingsRepository(
            store = store,
            remote = remote,
            account = account,
            accountKey = { "books.example_reader" },
            updateUser = { account.value = it },
            scope = backgroundScope,
            onQueued = { queued[0]++ },
        )
        repo.start()
        runCurrent()
        return Setup(repo, remote, store, account, queued)
    }

    @Test
    fun readsTheKeyFromTheSignedInUser() = runTest {
        val s = setup(serverKey = key("dailyGoalMinutes" to 25))
        assertTrue(s.repo.loaded.value)
        assertEquals(25, s.repo.settings.value.dailyGoalMinutes)
    }

    @Test
    fun sendsOnlyItsOwnKeyOnceChangesSettle() = runTest {
        val s = setup()
        s.repo.update { it.copy(dailyGoalMinutes = 20) }
        advanceTimeBy(1_000)
        s.repo.update { it.copy(pageTotals = mapOf(5L to 410)) }
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue("debounced", s.remote.settingsPatches.isEmpty())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, s.remote.settingsPatches.size)
        val patch = s.remote.settingsPatches.single()
        assertEquals(setOf(AppSettings.KEY), patch.keys) // never another top-level key
        val sent = AppSettings.decode(patch[AppSettings.KEY] as JsonObject)
        assertEquals(20, sent.dailyGoalMinutes)
        assertEquals(mapOf(5L to 410), sent.pageTotals)
        assertFalse(s.repo.hasUnsent())
        // The cached user now carries it; the dashboard config is untouched.
        assertEquals(12, s.account.value!!.settings!!.dashboardConfig!!["readingGoal"]!!.jsonPrimitive.int)
    }

    @Test
    fun keepsAChangeMadeElsewhereMeanwhile() = runTest {
        val s = setup(serverKey = AppSettings.encodeOver(null, AppSettings(pageTotals = mapOf(1L to 100))))
        // The web (or another phone) sets a daily goal after this device read the key.
        s.remote.user = s.remote.user.copy(
            settings = s.remote.user.settings!!.copy(
                bookorbitAndroid = AppSettings.encodeOver(null, AppSettings(pageTotals = mapOf(1L to 100), dailyGoalMinutes = 45)),
            ),
        )
        s.repo.update { it.copy(pageTotals = it.pageTotals + (2L to 200)) }
        advanceTimeBy(2_000)
        runCurrent()
        val sent = AppSettings.decode(s.remote.settingsPatches.single()[AppSettings.KEY] as JsonObject)
        assertEquals(mapOf(1L to 100, 2L to 200), sent.pageTotals)
        assertEquals(45, sent.dailyGoalMinutes)
        assertEquals(45, s.repo.settings.value.dailyGoalMinutes)
    }

    @Test
    fun aFieldTheLastSeenKeyLackedTakesTheOtherDevicesChange() = runTest {
        // This device last saw a key written before nextInSeries existed; another phone has since
        // switched it off. An unrelated change here must not switch it back on.
        val s = setup(serverKey = key("v" to 1, "dailyGoalMinutes" to 30))
        val other = AppSettings.encodeOver(null, AppSettings(dailyGoalMinutes = 30, nextInSeries = false))
        s.remote.user = s.remote.user.copy(settings = s.remote.user.settings!!.copy(bookorbitAndroid = other))
        s.repo.update { it.copy(pageTotals = mapOf(3L to 300)) }
        advanceTimeBy(2_000)
        runCurrent()
        val sent = AppSettings.decode(s.remote.settingsPatches.single()[AppSettings.KEY] as JsonObject)
        assertFalse(sent.nextInSeries)
        assertEquals(mapOf(3L to 300), sent.pageTotals)
        assertFalse(s.repo.settings.value.nextInSeries)
    }

    @Test
    fun aDeviceThatNeverSawTheKeyChangesOnlyWhatItChanged() = runTest {
        // Its cached user has no key (an older build, or an offline start); another phone wrote one since.
        val s = setup(serverKey = null)
        val other = AppSettings.encodeOver(null, AppSettings(timer = TimerPrefs(mode = TimerMode.COUNTDOWN, countdownMinutes = 20)))
        s.remote.user = s.remote.user.copy(
            settings = s.remote.user.settings!!.copy(bookorbitAndroid = JsonObject(other + ("v" to JsonPrimitive(2)))),
        )
        s.repo.update { it.copy(dailyGoalMinutes = 15) }
        advanceTimeBy(2_000)
        runCurrent()
        val sent = s.remote.settingsPatches.single()[AppSettings.KEY] as JsonObject
        assertEquals(TimerPrefs(mode = TimerMode.COUNTDOWN, countdownMinutes = 20), AppSettings.decode(sent).timer)
        assertEquals(15, AppSettings.decode(sent).dailyGoalMinutes)
        assertEquals("a newer app's version isn't lowered", 2, sent["v"]!!.jsonPrimitive.int)
    }

    @Test
    fun aChangeLeavesWhatThisAppCantReadOnTheServer() = runTest {
        // A newer app on the same account wrote a progress unit this one doesn't know.
        val serverKey = ApiJson.parseToJsonElement(
            """{"v": 1, "pageTotals": {"1": 250}, "quotePhotos": {"8": "8.jpg"}, "progressUnits": {"5": "chapter", "7": "percent"}}""",
        ).jsonObject
        val s = setup(serverKey = serverKey)
        assertEquals(mapOf(1L to 250), s.repo.settings.value.pageTotals)
        s.repo.update { it.copy(dailyGoalMinutes = 30) }
        advanceTimeBy(2_000)
        runCurrent()
        val sent = s.remote.settingsPatches.single()[AppSettings.KEY] as JsonObject
        assertEquals(JsonObject(serverKey + ("dailyGoalMinutes" to JsonPrimitive(30))), sent)

        s.repo.update { it.copy(progressUnits = it.progressUnits + (9L to ProgressUnit.PAGE)) }
        advanceTimeBy(2_000)
        runCurrent()
        val units = (s.remote.settingsPatches.last()[AppSettings.KEY] as JsonObject)["progressUnits"]
        assertEquals(ApiJson.parseToJsonElement("""{"5": "chapter", "7": "percent", "9": "page"}"""), units)
    }

    @Test
    fun offlineChangesWaitOnTheDeviceAndGoLater() = runTest {
        val store = MemoryStore()
        val s = setup(store = store)
        s.remote.offline = true
        s.repo.update { it.copy(dailyGoalMinutes = 15) }
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(s.repo.hasUnsent())
        assertEquals(1, s.queued[0])
        assertTrue(store.state.value.asMap().keys.any { it.name == "tracking.settings.books.example_reader" })

        // The app is killed; a new process reads the waiting change and sends it when back online.
        val again = setup(store = store)
        again.remote.offline = false
        assertEquals(15, again.repo.settings.value.dailyGoalMinutes)
        assertTrue(again.repo.flush())
        assertEquals(15, AppSettings.decode(again.remote.settingsPatches.last()[AppSettings.KEY] as JsonObject).dailyGoalMinutes)
    }

    @Test
    fun aFresherUserReplacesTheCopyWhenNothingWaits() = runTest {
        val s = setup(serverKey = key("dailyGoalMinutes" to 10))
        s.account.value = s.account.value!!.copy(settings = UserSettings(bookorbitAndroid = key("dailyGoalMinutes" to 50)))
        runCurrent()
        assertEquals(50, s.repo.settings.value.dailyGoalMinutes)
    }

    @Test
    fun storedCopyIsPlainJson() = runTest {
        val s = setup()
        s.repo.update { it.copy(dailyGoalMinutes = 5) }
        runCurrent()
        val raw = s.store.state.value.asMap().entries.first { it.key.name.startsWith("tracking.settings.") }.value as String
        assertTrue(ApiJson.parseToJsonElement(raw).jsonObject["dirty"]!!.jsonPrimitive.content == "true")
    }
}
