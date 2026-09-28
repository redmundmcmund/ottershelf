package io.github.ottershelf.feature.reader

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import io.github.ottershelf.core.network.ApiJson

/**
 * The user's measured pace in each book (percent per hour, the book page's `trustedPace`), kept on this
 * device (settings DataStore `reader.pace.<account>`), so the footer's time left is worked out from
 * the user's speed when the reader opens offline too. Refreshed from the server on every opening.
 */
internal class ReaderPaceStore(private val store: DataStore<Preferences>, account: String) {

    private val key = stringPreferencesKey("reader.pace.$account")

    suspend fun get(bookId: Long): Double? = decode(store.data.first()[key])[bookId.toString()]

    /** Keeps [percentPerHour] for [bookId] (null: no trusted pace yet, forgotten). */
    suspend fun put(bookId: Long, percentPerHour: Double?) {
        store.edit { it[key] = encode(PaceCache.put(decode(it[key]), bookId, percentPerHour)) }
    }

    private fun decode(raw: String?): Map<String, Double> =
        raw?.let { runCatching { ApiJson.decodeFromString(SERIALIZER, it) }.getOrNull() } ?: emptyMap()

    private fun encode(map: Map<String, Double>): String = ApiJson.encodeToString(SERIALIZER, map)

    private companion object {
        val SERIALIZER = MapSerializer(String.serializer(), Double.serializer())
    }
}

/** The pace map's rules. Pure; `ReadingTimeTest`. */
internal object PaceCache {
    /** Books kept; the one updated longest ago goes first. */
    const val MAX = 100

    fun put(map: Map<String, Double>, bookId: Long, percentPerHour: Double?): Map<String, Double> {
        val id = bookId.toString()
        val next = LinkedHashMap(map)
        next.remove(id)
        if (percentPerHour != null && percentPerHour.isFinite() && percentPerHour > 0) next[id] = percentPerHour
        while (next.size > MAX) next.remove(next.keys.first())
        return next
    }
}
