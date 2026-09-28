package io.github.ottershelf.feature.bookedit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An author already in the library (`GET authors`, best match first), with how many books the user has. */
data class AuthorSuggestion(val name: String, val bookCount: Int)

/** A series already in the library (`GET series`, best match first), with its size and authors. */
data class SeriesSuggestion(val name: String, val bookCount: Int, val authors: List<String>)

/**
 * The suggestions for what was typed: [items] are the answer for [query] (or, while [loading] the
 * next one, still the last answer's); [failed]: the last search didn't answer.
 */
data class Suggestions<T>(
    val query: String = "",
    val items: List<T> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/**
 * Suggestions as the user types: asked for [debounceMs] after the last keystroke, one request at a time
 * (a newer query cancels the older request, which cancels its HTTP call), and an answer is taken
 * only while it is still for what the box says, so a slow answer to an old query never replaces a
 * newer one. A blank query asks nothing and shows nothing. A failed search shows nothing (typing on
 * asks again); the name can still be typed in full.
 */
class SuggestionSearch<T>(
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val search: suspend (String) -> List<T>,
) {
    private val _state = MutableStateFlow(Suggestions<T>())
    val state: StateFlow<Suggestions<T>> = _state.asStateFlow()

    private var job: Job? = null

    fun query(text: String) {
        val q = text.trim()
        val current = _state.value
        // The same text again (the box recomposed, a separator typed): the answer stands, unless it failed.
        if (q.isNotEmpty() && q == current.query && !current.failed) return
        job?.cancel()
        if (q.isEmpty()) {
            job = null
            _state.value = Suggestions()
            return
        }
        _state.update { it.copy(query = q, loading = true, failed = false) }
        job = scope.launch {
            delay(debounceMs)
            val items = try {
                search(q)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value.query == q) _state.value = Suggestions(query = q, failed = true)
                return@launch
            }
            if (_state.value.query == q) _state.value = Suggestions(query = q, items = items)
        }
    }

    /** A suggestion was taken (or the box emptied): nothing shows until the user types again. */
    fun clear() {
        job?.cancel()
        job = null
        _state.value = Suggestions()
    }

    companion object {
        const val DEBOUNCE_MS = 250L
    }
}

/** A row of the author box's list. */
sealed interface AuthorRow {
    /** A name not in the library (or not among the matches): added as typed. */
    data class Add(val name: String) : AuthorRow

    data class Existing(val suggestion: AuthorSuggestion) : AuthorRow
}

/**
 * The author box's list for [typed]: the library's matches in the server's order, an exact match
 * (any case) moved to the top, those already chosen left out; then "Add <typed>" unless the name is
 * already there or chosen (last, so a quick tap on the first row takes a name the library knows
 * rather than half a word). Nothing for a blank box; only Add while [suggestions] are for other text.
 */
fun authorRows(typed: String, suggestions: Suggestions<AuthorSuggestion>, chosen: List<String>): List<AuthorRow> {
    val name = EditRules.normalizeName(typed)
    if (name.isEmpty()) return emptyList()
    val current = suggestions.query.equals(typed.trim(), ignoreCase = false)
    val matches = if (current || suggestions.loading) suggestions.items else emptyList()
    val available = matches.filter { m -> chosen.none { it.equals(m.name, ignoreCase = true) } }
    val exact = available.filter { it.name.equals(name, ignoreCase = true) }
    val ordered = exact + (available - exact.toSet())
    val add = if (exact.isEmpty() && chosen.none { it.equals(name, ignoreCase = true) }) listOf(AuthorRow.Add(name)) else emptyList()
    return ordered.take(MAX_ROWS).map { AuthorRow.Existing(it) } + add
}

/**
 * The series box's list for [typed]: the library's matches (series names, and series by an author
 * of that name, as the server searches), an exact name (any case) first; nothing once the box holds
 * exactly one of them (the user picked it), for a blank box, or while the answer is for other text.
 */
fun seriesRows(typed: String, suggestions: Suggestions<SeriesSuggestion>): List<SeriesSuggestion> {
    val name = EditRules.normalizeName(typed)
    if (name.isEmpty() || !suggestions.query.equals(typed.trim(), ignoreCase = false) && !suggestions.loading) return emptyList()
    if (suggestions.items.any { it.name == name }) return emptyList()
    val exact = suggestions.items.filter { it.name.equals(name, ignoreCase = true) }
    return (exact + (suggestions.items - exact.toSet())).take(MAX_ROWS)
}

/** Whether [typed] names a series the library doesn't have yet (Save creates it), once the answer is in. */
fun isNewSeries(typed: String, suggestions: Suggestions<SeriesSuggestion>): Boolean {
    val name = EditRules.normalizeName(typed)
    if (name.isEmpty() || suggestions.loading || suggestions.failed || suggestions.query != typed.trim()) return false
    return suggestions.items.none { it.name.equals(name, ignoreCase = true) }
}

/** At most this many suggestions show under a box. */
const val MAX_ROWS = 6
