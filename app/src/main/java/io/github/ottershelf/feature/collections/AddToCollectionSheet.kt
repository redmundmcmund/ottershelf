package io.github.ottershelf.feature.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ottershelf.R
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.components.belowStatusBar
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme

/**
 * The shell's "add to a collection" sheet: opens by itself when [CollectionPrompt] offers a book
 * (just marked Want to read); dismiss it to skip. [onChanged]: a collection's books changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToCollectionHost(onChanged: () -> Unit = {}) {
    val viewModel = appViewModel { c -> AddToCollectionViewModel(ApiCollectionsRemote(c.api), onChanged) }
    val prompt by CollectionPrompt.book.collectAsStateWithLifecycle()
    LaunchedEffect(prompt) {
        prompt?.let {
            viewModel.open(it)
            CollectionPrompt.taken()
        }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val s = state ?: return
    ModalBottomSheet(
        onDismissRequest = viewModel::close,
        modifier = Modifier.belowStatusBar(),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OttershelfTheme.colors.card,
    ) {
        AddToCollectionContent(s, onToggle = viewModel::toggle, onCreate = viewModel::create, onRetry = viewModel::retry, onDone = viewModel::close)
    }
}

/**
 * The book's title, the user's collections (ticked where the book is; a tap adds or removes it),
 * "New collection" (a name, then Create: made with the book in it), and Done.
 */
@Composable
fun AddToCollectionContent(
    state: AddToCollectionUiState,
    onToggle: (CollectionChoice) -> Unit = {},
    onCreate: (String) -> Unit = {},
    onRetry: () -> Unit = {},
    onDone: () -> Unit = {},
) {
    val colors = OttershelfTheme.colors
    var naming by rememberSaveable(state.book.id) { mutableStateOf(false) }
    var name by rememberSaveable(state.book.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text(
                stringResource(R.string.collections_add_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = colors.foreground,
            )
            state.book.title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    stringResource(R.string.collections_add_subtitle, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedForeground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            state.loading -> Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) { LoadingState() }
            state.failed -> ErrorState(onRetry, message = stringResource(R.string.collections_load_failed), compact = true)
            state.choices.isEmpty() -> Text(stringResource(R.string.collections_none), style = MaterialTheme.typography.bodyMedium, color = colors.mutedForeground)
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(state.choices, key = { it.id }) { choice ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = choice.id !in state.busy, role = Role.Checkbox) { onToggle(choice) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LucideIcon(choice.icon, contentDescription = null, tint = colors.mutedForeground, size = 20.dp, fallback = "FolderOpen")
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(choice.name, style = MaterialTheme.typography.bodyLarge, color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(R.string.collections_count, choice.bookCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.mutedForeground,
                            )
                        }
                        Checkbox(checked = choice.contains, onCheckedChange = null, enabled = choice.id !in state.busy)
                    }
                }
            }
        }
        if (!state.loading && !state.failed) {
            if (naming) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.collections_new_name)) },
                    singleLine = true,
                    shape = RoundedCornerShape(OttershelfTheme.radii.md),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCreate(name); naming = false; name = "" }),
                    enabled = !state.creating,
                )
            }
        }
        state.error?.let { error ->
            Text(
                if (error.isBlank()) stringResource(R.string.collections_change_failed) else stringResource(R.string.collections_change_failed_because, error),
                style = MaterialTheme.typography.bodySmall,
                color = colors.destructive,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (naming) {
                SecondaryButton(stringResource(R.string.collections_cancel), onClick = { naming = false; name = "" }, modifier = Modifier.weight(1f))
                AccentButton(
                    stringResource(R.string.collections_create),
                    onClick = { onCreate(name); naming = false; name = "" },
                    modifier = Modifier.weight(1f),
                    icon = "Plus",
                    enabled = name.isNotBlank() && !state.creating,
                )
            } else {
                SecondaryButton(
                    stringResource(R.string.collections_new),
                    onClick = { naming = true },
                    modifier = Modifier.weight(1f),
                    icon = "FolderPlus",
                    enabled = !state.loading && !state.failed && !state.creating,
                )
                AccentButton(stringResource(R.string.collections_done), onClick = onDone, modifier = Modifier.weight(1f), icon = "Check")
            }
        }
    }
}
