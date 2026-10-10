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

/**
 * The books the "add to a collection" sheet is for: one ([title] shown), or several picked in a
 * library grid. [wantToRead]: just marked Want to read (the sheet says so).
 */
data class CollectionTarget(val bookIds: List<Long>, val title: String? = null, val wantToRead: Boolean = false)

/** How many of the target's books a collection holds. */
enum class Membership { None, Some, All }

/**
 * Asks the shell to open the "add to a collection" sheet: for a book the user just marked Want to
 * read (the book page, the quick view, the Dashboard: [offer]), or for the books the user chose to
 * add ([add]: the book page's and the quick view's button, a library grid's selection). The shell's
 * [AddToCollectionHost] takes it.
 */
object CollectionPrompt {
    private val _target = MutableStateFlow<CollectionTarget?>(null)
    val target: StateFlow<CollectionTarget?> = _target.asStateFlow()

    /** [id] was just marked Want to read. */
    fun offer(id: Long, title: String?) {
        _target.value = CollectionTarget(listOf(id), title, wantToRead = true)
    }

    /** The user wants to add [ids] to collections ([title]: the book's, for one). */
    fun add(ids: Collection<Long>, title: String? = null) {
        if (ids.isEmpty()) return
        _target.value = CollectionTarget(ids.distinct(), title?.takeIf { ids.size == 1 })
    }

    internal fun taken() {
        _target.value = null
    }
}

data class AddToCollectionUiState(
    val target: CollectionTarget,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val choices: List<CollectionChoice> = emptyList(),
    /** Collections with an add or remove on its way. */
    val busy: Set<Long> = emptySet(),
    val creating: Boolean = false,
    /** Why the last change failed (the server's words, when it gave some). */
    val error: String? = null,
) {
    fun membership(choice: CollectionChoice): Membership = when {
        choice.memberCount <= 0 -> Membership.None
        choice.memberCount >= target.bookIds.size -> Membership.All
        else -> Membership.Some
    }
}

/**
 * The "add to a collection" sheet: the user's collections, ticked where they hold the book (or all
 * of the books; a dash where they hold some). A tap on one that holds them all takes them out;
 * otherwise it adds them all (the server skips those already in). Shown at once, put back if the
 * server refuses. A new collection can be made with the books in it. [onChanged] runs after each
 * change that went through (the drawer's collection counts).
 */
class AddToCollectionViewModel(
    private val remote: CollectionsRemote,
    private val onChanged: () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow<AddToCollectionUiState?>(null)
    val state: StateFlow<AddToCollectionUiState?> = _state.asStateFlow()

    private var loadJob: Job? = null

    fun open(target: CollectionTarget) {
        if (_state.value?.target == target) return
        _state.value = AddToCollectionUiState(target)
        load()
    }

    fun close() {
        loadJob?.cancel()
        _state.value = null
    }

    fun retry() = load()

    private fun load() {
        val target = _state.value?.target ?: return
        loadJob?.cancel()
        update(target) { it.copy(loading = true, failed = false) }
        loadJob = viewModelScope.launch {
            try {
                val choices = remote.choices(target.bookIds)
                update(target) { it.copy(loading = false, choices = choices) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                update(target) { it.copy(loading = false, failed = true) }
            }
        }
    }

    fun toggle(choice: CollectionChoice) {
        val s = _state.value ?: return
        if (choice.id in s.busy) return
        val target = s.target
        val removing = s.membership(choice) == Membership.All
        val before = choice.memberCount
        fun mark(count: Int) = update(target) { st ->
            st.copy(choices = st.choices.map { if (it.id == choice.id) it.copy(memberCount = count) else it })
        }
        mark(if (removing) 0 else target.bookIds.size)
        update(target) { it.copy(busy = it.busy + choice.id, error = null) }
        viewModelScope.launch {
            try {
                if (removing) remote.remove(choice.id, target.bookIds) else remote.add(choice.id, target.bookIds)
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mark(before)
                update(target) { it.copy(error = e.message.orEmpty()) }
            } finally {
                update(target) { it.copy(busy = it.busy - choice.id) }
            }
        }
    }

    /** Makes a collection called [name] with the books in it. */
    fun create(name: String) {
        val s = _state.value ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || s.creating) return
        val target = s.target
        update(target) { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            try {
                val id = remote.create(trimmed)
                remote.add(id, target.bookIds)
                onChanged()
                val choices = remote.choices(target.bookIds)
                update(target) { it.copy(choices = choices) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(target) { it.copy(error = e.message.orEmpty()) }
            } finally {
                update(target) { it.copy(creating = false) }
            }
        }
    }

    private fun update(target: CollectionTarget, change: (AddToCollectionUiState) -> AddToCollectionUiState) {
        _state.update { s -> if (s?.target == target) change(s) else s }
    }
}
