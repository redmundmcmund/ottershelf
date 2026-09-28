package io.github.ottershelf.feature.history

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Readings saved on this device ([SavedReading], from the dates sheet), laid over what a screen
 * loaded: each shows at once and stays until a load that started after it was saved has come
 * back (that one has it, since the save and its re-read were done before it was handed over).
 * A book whose status left History's ([HistoryMath.STATUSES]) is dropped.
 */
internal class SavedReadings {
    private var generation = 0
    private val saved = LinkedHashMap<Long, Pair<Int, CachedBook>>()

    /** Read before a load starts; pass it to [loaded] when it's back. */
    @Synchronized
    fun generation(): Int = generation

    @Synchronized
    fun add(reading: SavedReading) {
        generation++
        saved[reading.card.id] = generation to CachedBook(reading.card, reading.card.fingerprint, reading.attempts)
    }

    /** A load that started at [since] came back: it has every reading saved up to then. */
    @Synchronized
    fun loaded(since: Int) {
        saved.entries.removeAll { it.value.first <= since }
    }

    /** [base] with the saved readings on top. */
    @Synchronized
    fun over(base: Map<Long, CachedBook>): Map<Long, CachedBook> {
        if (saved.isEmpty()) return base
        val out = LinkedHashMap(base)
        for ((id, entry) in saved) {
            val book = entry.second
            if (book.card.readStatus?.status in HistoryMath.STATUSES) out[id] = book else out.remove(id)
        }
        return out
    }
}

/**
 * Every reading of every book, as History has them, for the Calendar and a Day (their marks: a
 * book started, finished or given up on a day). The same device copy as History's
 * ([historyCacheFile]) and the same loads ([HistoryLoader]: only books whose status row changed
 * since are fetched again), with the readings saved on this device on top ([SavedReadings]).
 */
internal class ReadingLog(private val loader: HistoryLoader, private val cache: HistoryCache) {

    private val mutex = Mutex()
    private var books: Map<Long, CachedBook>? = null
    private val saved = SavedReadings()

    /** What is known now: the device copy until a load has come back, then that load's. */
    suspend fun current(): Map<Long, CachedBook> = saved.over(base())

    /**
     * Loads the books and the readings that changed, keeps them on the device and returns them.
     * Throws when the books can't be listed (offline, a server error).
     */
    suspend fun refresh(): Map<Long, CachedBook> {
        val since = saved.generation()
        val result = loader.load(saved.over(base()), force = false)
        val next = result.books.associateBy { it.card.id }
        mutex.withLock { books = next }
        saved.loaded(since)
        cache.write(HistoryCacheFile(result.books))
        return saved.over(next)
    }

    /** The device copy read again (another screen's load may have written it), without a load of its own. */
    suspend fun reread(): Map<Long, CachedBook> {
        mutex.withLock { books = null }
        return current()
    }

    /** [reading] was saved here: it counts at once. */
    suspend fun add(reading: SavedReading): Map<Long, CachedBook> {
        saved.add(reading)
        return current()
    }

    private suspend fun base(): Map<Long, CachedBook> {
        mutex.withLock { books }?.let { return it }
        val read = cache.read()?.books.orEmpty().associateBy { it.card.id }
        return mutex.withLock { books ?: read.also { books = it } }
    }
}
