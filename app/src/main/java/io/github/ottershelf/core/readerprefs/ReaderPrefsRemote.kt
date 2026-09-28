package io.github.ottershelf.core.readerprefs

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson

/** `GET reader/preferences/:bookFileId`: the book's own settings (a partial object) or null. */
@Serializable
data class FileReaderPreference(val settings: JsonObject? = null, val isCustomized: Boolean = false)

/** `PATCH reader/defaults/:formatGroup` (PatchDefaultDto): only the keys in [set] are written. */
@Serializable
internal data class PatchDefaultBody(val set: JsonObject)

/**
 * `PATCH reader/preferences/:bookFileId` (PatchPreferenceDto): [set] pins keys on the book, [unset]
 * drops them back to the account default. At least one must be non-empty and a key may not be in
 * both; a row left empty is deleted by the server.
 */
@Serializable
internal data class PatchPreferenceBody(val set: JsonObject? = null, val unset: List<String>? = null)

/**
 * The server's reader-preferences routes (reader-preferences.controller.ts), used only in the
 * account's sync mode (`AuthUser.settings.syncReaderPreferences`). Bodies are exactly the DTOs.
 */
interface ReaderPrefsRemote {
    /** `GET reader/defaults`: format group id -> that group's saved default settings. */
    suspend fun defaults(): Map<String, JsonObject>

    suspend fun preference(fileId: Long): FileReaderPreference

    suspend fun patchPreference(fileId: Long, set: JsonObject, unset: List<String> = emptyList())

    /** `DELETE reader/preferences/:bookFileId`: the book follows the account default again. */
    suspend fun deletePreference(fileId: Long)

    suspend fun patchDefault(group: ReaderFormatGroup, set: JsonObject)

    /** `DELETE reader/defaults/:formatGroup`: back to the built-in defaults. */
    suspend fun deleteDefault(group: ReaderFormatGroup)
}

class ApiReaderPrefsRemote(private val api: Api) : ReaderPrefsRemote {

    override suspend fun defaults(): Map<String, JsonObject> =
        api.send("GET", "reader/defaults", null) { text ->
            val root = ApiJson.parseToJsonElement(text.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
            root.mapNotNull { (group, value) -> (value as? JsonObject)?.let { group to it } }.toMap()
        }

    override suspend fun preference(fileId: Long): FileReaderPreference =
        api.send("GET", "reader/preferences/$fileId", null) { ApiJson.decodeFromString(FileReaderPreference.serializer(), it) }

    override suspend fun patchPreference(fileId: Long, set: JsonObject, unset: List<String>) {
        val body = PatchPreferenceBody(set = set.takeIf { it.isNotEmpty() }, unset = unset.takeIf { it.isNotEmpty() })
        if (body.set == null && body.unset == null) return // the server refuses an empty patch
        api.send("PATCH", "reader/preferences/$fileId", ApiJson.encodeToJsonElement(PatchPreferenceBody.serializer(), body)) { }
    }

    override suspend fun deletePreference(fileId: Long) {
        api.send("DELETE", "reader/preferences/$fileId", null) { }
    }

    override suspend fun patchDefault(group: ReaderFormatGroup, set: JsonObject) {
        if (set.isEmpty()) return
        api.send("PATCH", "reader/defaults/${group.id}", ApiJson.encodeToJsonElement(PatchDefaultBody.serializer(), PatchDefaultBody(set))) { }
    }

    override suspend fun deleteDefault(group: ReaderFormatGroup) {
        api.send("DELETE", "reader/defaults/${group.id}", null) { }
    }
}
