package io.github.ottershelf.feature.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A book just marked Want to read, for the "add to a collection" sheet. */
data class PromptBook(val id: Long, val title: String?)

/**
 * Asks the shell to open the "add to a collection" sheet for a book the user just marked Want to
 * read (the book page, the quick view, the Dashboard). The shell's [AddToCollectionHost] takes it.
 */
object CollectionPrompt {
    private val _book = MutableStateFlow<PromptBook?>(null)
    val book: StateFlow<PromptBook?> = _book.asStateFlow()

    fun offer(id: Long, title: String?) {
        _book.value = PromptBook(id, title)
    }

    internal fun taken() {
        _book.value = null
    }
}

data class AddToCollectionUiState(
    val book: PromptBook,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val choices: List<CollectionChoice> = emptyList(),
    /** Collections with an add or remove on its way. */
    val busy: Set<Long> = emptySet(),
    val creating: Boolean = false,
    /** Why the last change failed (the server's words, when it gave some). */
    val error: String? = null,
)

/**
 * The "add to a collection" sheet: the user's collections with the book's membership ticked; a tap
 * adds the book to one or takes it out (shown at once, put back if the server refuses), and a new
 * collection can be made with the book in it. [onChanged] runs after each change that went through
 * (the drawer's collection counts).
 */
class AddToCollectionViewModel(
    private val remote: CollectionsRemote,
    private val onChanged: () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow<AddToCollectionUiState?>(null)
    val state: StateFlow<AddToCollectionUiState?> = _state.asStateFlow()

    private var loadJob: Job? = null

    fun open(book: PromptBook) {
        if (_state.value?.book == book) return
        _state.value = AddToCollectionUiState(book)
        load()
    }

    fun close() {
        loadJob?.cancel()
        _state.value = null
    }

    fun retry() = load()

    private fun load() {
        val book = _state.value?.book ?: return
        loadJob?.cancel()
        update(book) { it.copy(loading = true, failed = false) }
        loadJob = viewModelScope.launch {
            try {
                val choices = remote.choices(book.id)
                update(book) { it.copy(loading = false, choices = choices) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                update(book) { it.copy(loading = false, failed = true) }
            }
        }
    }

    fun toggle(choice: CollectionChoice) {
        val s = _state.value ?: return
        if (choice.id in s.busy) return
        val book = s.book
        val adding = !choice.contains
        fun mark(member: Boolean) = update(book) { st ->
            st.copy(choices = st.choices.map { if (it.id == choice.id) it.copy(memberCount = if (member) 1 else 0) else it })
        }
        mark(adding)
        update(book) { it.copy(busy = it.busy + choice.id, error = null) }
        viewModelScope.launch {
            try {
                if (adding) remote.add(choice.id, book.id) else remote.remove(choice.id, book.id)
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mark(!adding)
                update(book) { it.copy(error = e.message.orEmpty()) }
            } finally {
                update(book) { it.copy(busy = it.busy - choice.id) }
            }
        }
    }

    /** Makes a collection called [name] with the book in it. */
    fun create(name: String) {
        val s = _state.value ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || s.creating) return
        val book = s.book
        update(book) { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            try {
                val id = remote.create(trimmed)
                remote.add(id, book.id)
                onChanged()
                val choices = remote.choices(book.id)
                update(book) { it.copy(choices = choices) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(book) { it.copy(error = e.message.orEmpty()) }
            } finally {
                update(book) { it.copy(creating = false) }
            }
        }
    }

    private fun update(book: PromptBook, change: (AddToCollectionUiState) -> AddToCollectionUiState) {
        _state.update { s -> if (s?.book == book) change(s) else s }
    }
}
