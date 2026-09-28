package io.github.ottershelf.core.readerprefs

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.ottershelf.core.tracking.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Reader settings as the web keeps them: sanitised, merged, patched key by key, synced when asked. */
class ReaderSettingsTest {

    // --- spec ----------------------------------------------------------------------------------

    @Test
    fun sanitisingKeepsOnlyWhatTheServerWouldTake() {
        val raw = buildJsonObject {
            put("fitMode", "fit-width")
            put("spreadGap", 12)
            put("bgColor", "purple") // not a web value
            put("direction", 3) // wrong type
            put("mystery", true) // unknown key
            put("autoAdvance", true)
        }
        val clean = ReaderSettingsSpecs.Cbx.sanitize(raw)
        assertEquals(setOf("fitMode", "spreadGap", "autoAdvance"), clean.keys)
        assertEquals(JsonPrimitive(12L), clean["spreadGap"])
        // The PDF reader's old `wrapped` is read as vertical, as the web and the server do.
        val pdf = ReaderSettingsSpecs.Pdf.sanitize(buildJsonObject { put("scrollMode", "wrapped"); put("rotation", 45); put("customScale", 2.5) })
        assertEquals(buildJsonObject { put("scrollMode", "vertical"); put("customScale", 2.5) }, pdf)
    }

    @Test
    fun theBooksOwnValuesWinOverTheDefaultWhichWinsOverBuiltIn() {
        val spec = ReaderSettingsSpecs.Cbx
        assertEquals(CbxReaderSettings(), spec.effective(null, null))
        val effective = spec.effective(
            default = buildJsonObject { put("fitMode", "fit-width"); put("direction", "rtl") },
            book = buildJsonObject { put("direction", "ltr") },
        )
        assertEquals(CbxReaderSettings(fitMode = "fit-width", direction = "ltr"), effective)
        assertEquals(
            buildJsonObject { put("viewMode", "two-page") },
            spec.changes(CbxReaderSettings(), CbxReaderSettings(viewMode = "two-page")),
        )
    }

    // --- store ---------------------------------------------------------------------------------

    private class FakeRemote : ReaderPrefsRemote {
        var defaults: Map<String, JsonObject> = emptyMap()
        var preference = FileReaderPreference()
        var failing = false
        val calls = mutableListOf<String>()

        private fun check() {
            if (failing) throw IOException("offline")
        }

        override suspend fun defaults(): Map<String, JsonObject> = check().let { defaults }
        override suspend fun preference(fileId: Long): FileReaderPreference = check().let { preference }
        override suspend fun patchPreference(fileId: Long, set: JsonObject, unset: List<String>) {
            check(); calls += "patch book $fileId $set"
        }
        override suspend fun deletePreference(fileId: Long) {
            check(); calls += "delete book $fileId"
        }
        override suspend fun patchDefault(group: ReaderFormatGroup, set: JsonObject) {
            check(); calls += "patch default ${group.id} $set"
        }
        override suspend fun deleteDefault(group: ReaderFormatGroup) {
            check(); calls += "delete default ${group.id}"
        }
    }

    private fun TestScope.store(remote: FakeRemote, memory: MemoryStore, sync: Boolean) =
        ReaderSettingsStore(ReaderSettingsSpecs.Cbx, 42L, memory, remote, { "acc" }, { sync }, backgroundScope)

    @Test
    fun inSyncModeTheServerReplacesTheDeviceCopyAndChangesArePatchedKeyByKey() = runTest {
        val remote = FakeRemote().apply {
            defaults = mapOf("cbx" to buildJsonObject { put("bgColor", "white") }, "pdf" to buildJsonObject { put("spread", "odd") })
            preference = FileReaderPreference(buildJsonObject { put("direction", "rtl") }, isCustomized = true)
        }
        val memory = MemoryStore()
        val store = store(remote, memory, sync = true)
        store.load()
        assertEquals(CbxReaderSettings(bgColor = "white", direction = "rtl"), store.effective.value)
        assertTrue(store.customized.value)

        store.updateBook { it.copy(viewMode = "two-page") }
        store.updateDefault { it.copy(fitMode = "fit-height") }
        runCurrent()
        assertEquals(
            listOf("patch book 42 {\"viewMode\":\"two-page\"}", "patch default cbx {\"fitMode\":\"fit-height\"}"),
            remote.calls,
        )
        assertEquals(CbxReaderSettings(bgColor = "white", direction = "rtl", viewMode = "two-page", fitMode = "fit-height"), store.effective.value)

        // A new reader on the same device, offline: the device copy has it all.
        remote.failing = true
        val again = store(remote, memory, sync = true)
        again.load()
        assertEquals(store.effective.value, again.effective.value)

        again.resetBook()
        assertFalse(again.customized.value)
        assertEquals(CbxReaderSettings(bgColor = "white", fitMode = "fit-height"), again.effective.value)
    }

    @Test
    fun withoutSyncNothingIsSent() = runTest {
        val remote = FakeRemote()
        val store = store(remote, MemoryStore(), sync = false)
        store.load()
        store.updateBook { it.copy(bgColor = "gray") }
        store.updateBook { it } // no change, no write
        runCurrent()
        assertTrue(remote.calls.isEmpty())
        assertEquals("gray", store.effective.value.bgColor)
    }
}
