package io.github.ottershelf.feature.collections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.ottershelf.R
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A collection the user asked to delete. */
data class CollectionToDelete(val id: Long, val name: String)

/**
 * Deleting a whole collection (the web's EditCollectionDialog "Delete collection"). The shell tells
 * it which collections are the user's own ([noteOwned], from the drawer's `GET collections`, whose
 * rows carry `isOwner`); a collection's book list offers Delete only for those ([CollectionMenu]),
 * and the shell's [DeleteCollectionHost] confirms and deletes.
 */
object CollectionDeletion {
    private val _owned = MutableStateFlow<Set<Long>>(emptySet())
    val owned: StateFlow<Set<Long>> = _owned.asStateFlow()

    private val _request = MutableStateFlow<CollectionToDelete?>(null)
    val request: StateFlow<CollectionToDelete?> = _request.asStateFlow()

    fun noteOwned(ids: Set<Long>) {
        _owned.value = ids
    }

    fun ask(id: Long, name: String) {
        if (id in _owned.value) _request.value = CollectionToDelete(id, name)
    }

    internal fun taken() {
        _request.value = null
    }

    internal fun deleted(id: Long) {
        _owned.update { it - id }
    }
}

data class DeleteCollectionUiState(
    val target: CollectionToDelete,
    val deleting: Boolean = false,
    /** Why the delete failed (the server's words, when it gave some; "" when it gave none). */
    val error: String? = null,
)

/** The confirmation: Delete sends `DELETE collections/:id`; [deleted] reports each one that went through. */
class DeleteCollectionViewModel(private val remote: CollectionsRemote) : ViewModel() {

    private val _state = MutableStateFlow<DeleteCollectionUiState?>(null)
    val state: StateFlow<DeleteCollectionUiState?> = _state.asStateFlow()

    private val _deleted = Channel<CollectionToDelete>(Channel.BUFFERED)
    val deleted: Flow<CollectionToDelete> = _deleted.receiveAsFlow()

    fun open(target: CollectionToDelete) {
        if (_state.value?.deleting == true) return
        _state.value = DeleteCollectionUiState(target)
    }

    fun dismiss() {
        if (_state.value?.deleting == true) return
        _state.value = null
    }

    fun confirm() {
        val s = _state.value ?: return
        if (s.deleting) return
        _state.value = s.copy(deleting = true, error = null)
        viewModelScope.launch {
            try {
                remote.delete(s.target.id)
                CollectionDeletion.deleted(s.target.id)
                _state.value = null
                _deleted.send(s.target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = s.copy(deleting = false, error = e.message.orEmpty())
            }
        }
    }
}

/**
 * The shell's "Delete this collection?" dialog, opened by [CollectionDeletion.ask]. [onDeleted]: it
 * is gone (the shell reloads the drawer and leaves its list).
 */
@Composable
fun DeleteCollectionHost(onDeleted: (CollectionToDelete) -> Unit) {
    val viewModel = appViewModel { c -> DeleteCollectionViewModel(ApiCollectionsRemote(c.api)) }
    val request by CollectionDeletion.request.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        request?.let {
            viewModel.open(it)
            CollectionDeletion.taken()
        }
    }
    val done by rememberUpdatedState(onDeleted)
    LaunchedEffect(viewModel) { viewModel.deleted.collect { done(it) } }
    val state by viewModel.state.collectAsStateWithLifecycle()
    state?.let { DeleteCollectionDialog(it, onConfirm = viewModel::confirm, onDismiss = viewModel::dismiss) }
}

@Composable
fun DeleteCollectionDialog(state: DeleteCollectionUiState, onConfirm: () -> Unit = {}, onDismiss: () -> Unit = {}) {
    val colors = OttershelfTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text(stringResource(R.string.collections_delete_title, state.target.name)) },
        text = {
            Column {
                Text(stringResource(R.string.collections_delete_message), color = colors.foreground)
                state.error?.let { error ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (error.isBlank()) stringResource(R.string.collections_delete_failed) else stringResource(R.string.collections_delete_failed_because, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.destructive,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !state.deleting) {
                Text(
                    stringResource(if (state.deleting) R.string.collections_deleting else R.string.collections_delete),
                    color = if (state.deleting) colors.mutedForeground else colors.destructive,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.deleting) { Text(stringResource(R.string.collections_cancel)) }
        },
    )
}

/**
 * The toolbar's ⋮ on a collection's book list: "Delete collection", shown only when the collection is
 * the user's own.
 */
@Composable
fun CollectionMenu(id: Long, name: String) {
    val owned by CollectionDeletion.owned.collectAsStateWithLifecycle()
    if (id !in owned) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            LucideIcon("EllipsisVertical", contentDescription = stringResource(R.string.collections_more), size = 22.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = OttershelfTheme.colors.card) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.collections_delete_collection), color = OttershelfTheme.colors.destructive) },
                leadingIcon = { LucideIcon("Trash", contentDescription = null, tint = OttershelfTheme.colors.destructive, size = 20.dp) },
                onClick = {
                    open = false
                    CollectionDeletion.ask(id, name)
                },
            )
        }
    }
}
