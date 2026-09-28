package io.github.ottershelf.feature.notes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.core.settings.AppSettingsRepository
import io.github.ottershelf.feature.notes.model.Annotation
import io.github.ottershelf.feature.notes.model.BookAnnotationsPage
import io.github.ottershelf.feature.notes.model.BookFacet
import io.github.ottershelf.feature.notes.model.HubOverview
import io.github.ottershelf.feature.notes.model.HubPage
import io.github.ottershelf.feature.notes.model.NoteFilter

/**
 * BookOrbit's annotations (`annotation.controller.ts`, `annotation-hub.controller.ts`). PATCH bodies
 * carry only `note`, `color` or `style` (`UpdateAnnotationDto`); DELETE on the book route moves the
 * highlight to the web's trash.
 */
interface NotesRemote {
    suspend fun bookAnnotations(bookId: Long, page: Int, pageSize: Int, newestFirst: Boolean): BookAnnotationsPage
    suspend fun hub(filter: NoteFilter, page: Int, pageSize: Int): HubPage
    suspend fun overview(): HubOverview
    suspend fun books(limit: Int): List<BookFacet>
    suspend fun update(bookId: Long, id: Long, body: JsonObject): Annotation
    suspend fun delete(bookId: Long, id: Long)

    /** The book's highlights as Markdown (`GET annotations/export?bookId=&format=md`). */
    suspend fun exportMarkdown(bookId: Long): String
}

class ApiNotesRemote(private val api: Api) : NotesRemote {
    private suspend fun <T> get(path: String, de: DeserializationStrategy<T>): T =
        api.send("GET", path, null) { ApiJson.decodeFromString(de, it) }

    override suspend fun bookAnnotations(bookId: Long, page: Int, pageSize: Int, newestFirst: Boolean) =
        get(NotesLogic.bookQuery(bookId, page, pageSize, newestFirst), BookAnnotationsPage.serializer())

    override suspend fun hub(filter: NoteFilter, page: Int, pageSize: Int) =
        get(NotesLogic.hubQuery(filter, page, pageSize), HubPage.serializer())

    override suspend fun overview() = get("annotations/overview", HubOverview.serializer())

    override suspend fun books(limit: Int) = get("annotations/books?limit=$limit", ListSerializer(BookFacet.serializer()))

    override suspend fun update(bookId: Long, id: Long, body: JsonObject): Annotation =
        api.send("PATCH", "books/$bookId/annotations/$id", body) { ApiJson.decodeFromString(Annotation.serializer(), it) }

    override suspend fun delete(bookId: Long, id: Long) {
        api.send("DELETE", "books/$bookId/annotations/$id", null) { }
    }

    override suspend fun exportMarkdown(bookId: Long): String =
        api.send("GET", "annotations/export?bookId=$bookId&format=md", null) { it }
}

/** A PATCH body: the note (blank clears it), a colour or a style. */
internal object NoteEdits {
    fun note(text: String?): JsonObject =
        JsonObject(mapOf("note" to (text?.trim()?.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) } ?: JsonNull)))

    fun color(hex: String): JsonObject = JsonObject(mapOf("color" to JsonPrimitive(hex)))

    fun style(style: String): JsonObject = JsonObject(mapOf("style" to JsonPrimitive(style)))
}

/** A change to highlights made on this phone: in [bookId] (null: any book), by [origin] (the screen that made it). */
data class NoteChange(val bookId: Long?, val origin: Any? = null)

/**
 * Highlights changed on this phone (edited, recoloured, deleted, synced from the reader, a quote
 * added): other screens showing that book reload. A screen passes itself as `origin` and skips its
 * own changes. Bookmarks aren't highlights and don't count.
 */
internal object NoteChanges {
    private val _changes = MutableSharedFlow<NoteChange>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<NoteChange> = _changes.asSharedFlow()

    fun changed(bookId: Long?, origin: Any? = null) {
        LikedNotesCache.invalidate(bookId)
        _changes.tryEmit(NoteChange(bookId, origin))
    }
}

/**
 * The liked highlights as last fetched, per book, for one account at a time: the Liked filter, its
 * colour, notes and search chips, its random note and Memorize share them instead of fetching every
 * liked book again. A book is fetched again after a change to it ([NoteChanges]), when it holds a
 * newly liked highlight, or on a pull to refresh.
 */
internal object LikedNotesCache {
    private class Entry(val asked: Set<Long>, val found: List<Annotation>)

    private var account: String? = null
    private val books = HashMap<Long, Entry>()

    /** [ids]' highlights in [bookId], if that book was fetched for all of them. */
    @Synchronized
    fun get(account: String, bookId: Long, ids: Set<Long>): List<Annotation>? {
        if (account != this.account) return null
        val entry = books[bookId]?.takeIf { it.asked.containsAll(ids) } ?: return null
        return entry.found.filter { it.id in ids }
    }

    @Synchronized
    fun put(account: String, bookId: Long, ids: Set<Long>, found: List<Annotation>) {
        if (account != this.account) {
            books.clear()
            this.account = account
        }
        books[bookId] = Entry(ids, found)
    }

    /** Forgets [bookId] (null: every book). */
    @Synchronized
    fun invalidate(bookId: Long?) {
        if (bookId == null) books.clear() else books.remove(bookId)
    }
}

/**
 * The feature's data: the remote, and the app's own part of it in the settings key (likes and
 * Memorize reviews, `AppSettings.notes`). With [account], liked highlights are kept in [LikedNotesCache].
 */
class NotesRepository(val remote: NotesRemote, private val settings: AppSettingsRepository?, private val account: String? = null) {

    /** Likes or unlikes [note]; false when the likes are full and nothing changed (the user unlikes some first). */
    fun toggleLike(note: Annotation): Boolean {
        val settings = settings ?: return true
        if (NotesLogic.toggleLiked(settings.settings.value.notes, note) == null) return false
        settings.update { it.copy(notes = NotesLogic.toggleLiked(it.notes, note) ?: it.notes) }
        return true
    }

    /** Memorize reviews (id -> how many) counted in one settings change. */
    fun addReviews(counts: Map<Long, Int>) {
        if (counts.isEmpty()) return
        settings?.update { it.copy(notes = NotesLogic.addReviews(it.notes, counts)) }
    }

    /**
     * Every liked highlight that passes [filter], newest first: each liked book's highlights from
     * the hub (so title and author come with them), [PARALLEL_BOOKS] books at a time, kept in
     * [LikedNotesCache] ([fresh]: fetched again). A liked id its book no longer has is looked for in
     * the web's trash, where it keeps its like (it can be restored); one in neither loses its like.
     * Throws when nothing could be fetched.
     */
    suspend fun likedNotes(liked: Map<Long, Long>, filter: NoteFilter, fresh: Boolean = false): List<Annotation> {
        if (liked.isEmpty()) return emptyList()
        val books = liked.entries.groupBy({ it.value }, { it.key })
            .filterKeys { filter.bookId == null || it == filter.bookId }
        val gate = Semaphore(PARALLEL_BOOKS)
        val results = coroutineScope {
            books.map { (bookId, ids) ->
                async {
                    gate.withPermit {
                        try {
                            Result.success(likedIn(bookId, ids.toSet(), fresh))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    }
                }
            }.awaitAll()
        }
        val found = mutableListOf<Annotation>()
        val gone = mutableSetOf<Long>()
        var failure: Throwable? = null
        results.forEach { r ->
            r.onSuccess {
                found += it.found
                gone += it.gone
            }.onFailure { failure = it }
        }
        if (results.none { it.isSuccess }) failure?.let { throw it }
        if (gone.isNotEmpty()) settings?.update { it.copy(notes = NotesLogic.withoutLikes(it.notes, gone)) }
        return NotesLogic.newestFirst(found.filter { NotesLogic.matches(it, filter) })
    }

    private class BookLikes(val found: List<Annotation>, val gone: Set<Long>)

    private suspend fun likedIn(bookId: Long, ids: Set<Long>, fresh: Boolean): BookLikes {
        val account = account
        if (!fresh && account != null) LikedNotesCache.get(account, bookId, ids)?.let { return BookLikes(it, emptySet()) }
        val wanted = ids.toMutableSet()
        val found = find(NoteFilter(bookId = bookId), wanted)
        // Not among the active ones: in the web's trash it can still come back, so its like stays.
        if (wanted.isNotEmpty()) find(NoteFilter(bookId = bookId, trashed = true), wanted)
        account?.let { LikedNotesCache.put(it, bookId, ids, found) }
        return BookLikes(found, gone = wanted)
    }

    /** Pages through [filter] until every id in [wanted] was seen (each is removed); returns those found. */
    private suspend fun find(filter: NoteFilter, wanted: MutableSet<Long>): List<Annotation> {
        val found = mutableListOf<Annotation>()
        var page = 1
        while (wanted.isNotEmpty()) {
            val result = remote.hub(filter, page, PAGE_MAX)
            result.items.forEach { if (wanted.remove(it.id)) found += it }
            if (result.items.isEmpty() || page * PAGE_MAX >= result.total) break
            page++
        }
        return found
    }

    companion object {
        /** The server's largest page. */
        const val PAGE_MAX = 100

        /** Liked books fetched at once. */
        const val PARALLEL_BOOKS = 4
    }
}
