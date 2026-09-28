package io.github.ottershelf.core.tracking

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import io.github.ottershelf.core.network.ApiJson

/** A timed session waiting for `POST books/files/:fileId/sessions` (safe to resend). */
@Serializable
data class PendingTimedSession(val bookId: Long, val fileId: Long, val body: TimedSessionBody)

/**
 * A manual session waiting for `POST books/:bookId/sessions`. [unconfirmed]: a send was started
 * but its answer never arrived, so the server may already have it; the book's sessions are checked
 * before it is sent again (a resend would make a duplicate).
 *
 * [startReadingOn] (`YYYY-MM-DD`): once the session is on the server, a book that still isn't
 * started (unread or want to read) is set to reading from that day; [posted] means the session
 * itself is in and only that step is left.
 */
@Serializable
data class PendingManualSession(
    val localId: String,
    val bookId: Long,
    val body: ManualSessionBody,
    val unconfirmed: Boolean = false,
    val startReadingOn: String? = null,
    val posted: Boolean = false,
)

@Serializable
data class TrackingQueueState(
    val timed: List<PendingTimedSession> = emptyList(),
    val manual: List<PendingManualSession> = emptyList(),
) {
    val size: Int get() = timed.size + manual.size
    fun isEmpty(): Boolean = size == 0
}

/**
 * Sessions waiting to be sent, per account, in the settings DataStore (`tracking.queue.<account>`),
 * so they survive the app being killed. Every change is written before the send it prepares.
 */
class TrackingQueue(private val store: DataStore<Preferences>) {

    private val mutex = Mutex()

    suspend fun read(account: String): TrackingQueueState = mutex.withLock { load(account) }

    /** Applies [transform] and stores the result; returns it. */
    suspend fun update(account: String, transform: (TrackingQueueState) -> TrackingQueueState): TrackingQueueState =
        mutex.withLock {
            val next = transform(load(account))
            store.edit { prefs ->
                if (next.isEmpty()) prefs.remove(key(account))
                else prefs[key(account)] = ApiJson.encodeToString(TrackingQueueState.serializer(), next)
            }
            next
        }

    private suspend fun load(account: String): TrackingQueueState = try {
        store.data.first()[key(account)]?.let { ApiJson.decodeFromString(TrackingQueueState.serializer(), it) }
            ?: TrackingQueueState()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        TrackingQueueState()
    }

    private fun key(account: String) = stringPreferencesKey("tracking.queue.$account")
}
