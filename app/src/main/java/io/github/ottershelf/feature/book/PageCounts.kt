package io.github.ottershelf.feature.book

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.network.Api
import io.github.ottershelf.core.network.ApiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/** What a metadata provider says about a book (the fields of MetadataCandidate used here). */
@Serializable
internal data class PageCandidate(
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val pageCount: Int? = null,
    val isbn10: String? = null,
    val isbn13: String? = null,
)

/**
 * A page count for a book whose metadata has none, from BookOrbit's metadata providers (Google Books,
 * Open Library and the rest, as the web's "Fetch metadata" asks them): `GET metadata-fetch/stream`,
 * Server-Sent Events, one candidate per `data:` line (provider status lines have their own event
 * name and are skipped). [pick] chooses: a candidate with the book's ISBN, else the median of the
 * candidates with the same title and author. Each book is looked up once per account ([firstTry]):
 * a book the providers don't know isn't asked about again on every visit.
 */
internal class PageCounts(
    private val api: Api,
    private val store: DataStore<Preferences>,
    private val account: String,
) {
    private val triedKey = stringSetPreferencesKey("book.pageCountLookedUp.$account")

    /** True the first time it's asked for [bookId] (and records it); false after that. */
    suspend fun firstTry(bookId: Long): Boolean {
        val id = bookId.toString()
        if (id in (store.data.first()[triedKey] ?: emptySet())) return false
        store.edit { prefs ->
            val tried = prefs[triedKey].orEmpty()
            // Kept small: the oldest ids go first (a set keeps no order, so any go; asked again is harmless).
            prefs[triedKey] = (if (tried.size >= MAX_TRIED) tried.drop(tried.size - MAX_TRIED + 1).toSet() else tried) + id
        }
        return true
    }

    /** The candidates the providers send for [book], then [pick]; null when none fits or the search fails. */
    suspend fun find(book: BookDetail): Int? = withContext(Dispatchers.IO) {
        val url = api.serverUrl("/api/v1/metadata-fetch/stream").toHttpUrl().newBuilder().apply {
            addQueryParameter("bookId", book.id.toString())
            book.title?.takeIf { it.isNotBlank() }?.let { addQueryParameter("title", it) }
            book.authors.firstOrNull()?.name?.takeIf { it.isNotBlank() }?.let { addQueryParameter("author", it) }
            (book.isbn13 ?: book.isbn10)?.takeIf { it.isNotBlank() }?.let { addQueryParameter("isbn", it) }
            addQueryParameter("isAudiobook", "false")
        }.build()
        val candidates = ArrayList<PageCandidate>()
        // The whole search at most: a stream that keeps trickling is cut off with the call.
        val client = api.client.newBuilder().callTimeout(SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS).readTimeout(SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
        val call = client.newCall(Request.Builder().url(url).header("Accept", "text/event-stream").get().build())
        withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            runCatching {
                call.execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val source = response.body.source()
                    var event: String? = null
                    while (true) {
                        ensureActive()
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> event = null
                            line.startsWith("event:") -> event = line.substringAfter(':').trim()
                            line.startsWith("data:") && event == null -> {
                                runCatching { ApiJson.decodeFromString(PageCandidate.serializer(), line.substringAfter(':').trim()) }
                                    .getOrNull()?.let { candidates += it }
                                // A candidate with the book's ISBN settles it: no need to wait for the rest.
                                if (pickByIsbn(candidates, book) != null) break
                            }
                        }
                    }
                }
            }
        }
        call.cancel()
        pick(candidates, book)
    }

    /** Writes [count] to the book's metadata (`library_edit_metadata`); throws when refused. */
    suspend fun save(bookId: Long, count: Int) {
        api.send("PATCH", "books/$bookId/metadata", buildJsonObject { put("pageCount", count) }) { }
    }

    companion object {
        private const val MAX_TRIED = 2000
        private const val SEARCH_TIMEOUT_MS = 25_000L

        /** Fewer, or more, than this many pages is no printed book's count. */
        private val PLAUSIBLE = 10..5000

        /** The page count for [book] among [candidates]: by ISBN, else the median of same title and author; null if none. */
        fun pick(candidates: List<PageCandidate>, book: BookDetail): Int? {
            pickByIsbn(candidates, book)?.let { return it }
            val title = normalTitle(book.title) ?: return null
            val surnames = book.authors.mapNotNull { surname(it.name) }.toSet()
            val counts = candidates
                .filter { it.pageCount != null && it.pageCount in PLAUSIBLE }
                .filter { normalTitle(it.title) == title }
                .filter { c -> surnames.isEmpty() || c.authors.isEmpty() || c.authors.any { surname(it) in surnames } }
                .mapNotNull { it.pageCount }
                .sorted()
            return if (counts.isEmpty()) null else counts[(counts.size - 1) / 2]
        }

        private fun pickByIsbn(candidates: List<PageCandidate>, book: BookDetail): Int? {
            val isbns = listOfNotNull(book.isbn13, book.isbn10).map(::digits).filter { it.length >= 10 }.toSet()
            if (isbns.isEmpty()) return null
            return candidates.firstOrNull { c ->
                c.pageCount != null && c.pageCount in PLAUSIBLE && listOfNotNull(c.isbn13, c.isbn10).any { digits(it) in isbns }
            }?.pageCount
        }

        private fun digits(isbn: String) = isbn.filter { it.isDigit() || it == 'X' || it == 'x' }.uppercase()

        /** Lower case, no accents, punctuation or subtitle (after ':'), single spaces. */
        internal fun normalTitle(title: String?): String? {
            val main = title?.substringBefore(':')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val plain = Normalizer.normalize(main, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            return plain.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().takeIf { it.isNotEmpty() }
        }

        private fun surname(name: String?): String? = normalTitle(name)?.substringAfterLast(' ')
    }
}
