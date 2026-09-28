package io.github.ottershelf.core.settings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Three-way merge of JSON values, for writing the app's users.settings key whole without undoing
 * a change another device (or the web) made since this device last read it.
 *
 * [base] is the value this device last saw on the server, [local] the value it wants, [remote]
 * what the server has now. For each object key: untouched here -> the server's; untouched there ->
 * ours; changed on both sides -> merged key by key when both are objects, else ours (last write
 * wins). A null result means "absent". Pure; tested in JsonMergeTest.
 */
object JsonMerge {

    fun merge(base: JsonElement?, local: JsonElement?, remote: JsonElement?): JsonElement? {
        if (local == base) return remote
        if (remote == base || remote == local) return local
        if (local is JsonObject && remote is JsonObject) {
            val b = base as? JsonObject ?: JsonObject(emptyMap())
            val keys = LinkedHashSet<String>().apply {
                addAll(remote.keys)
                addAll(local.keys)
                addAll(b.keys)
            }
            val merged = LinkedHashMap<String, JsonElement>()
            for (key in keys) {
                merge(b[key], local[key], remote[key])?.let { merged[key] = it }
            }
            return JsonObject(merged)
        }
        return local
    }
}
