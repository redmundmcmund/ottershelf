package io.github.ottershelf.feature.quotes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import io.github.ottershelf.R
import io.github.ottershelf.feature.notes.highlightColor
import io.github.ottershelf.feature.notes.model.HighlightColors
import io.github.ottershelf.feature.notes.quoteStyle
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.CardTitle
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.DetailTopBar
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme
import io.github.ottershelf.ui.components.belowStatusBar

/** The form's callbacks, grouped so previews and screenshot tests pass none. */
internal class AddQuoteActions(
    val onBack: () -> Unit = {},
    val onPickBook: () -> Unit = {},
    val onText: (String) -> Unit = {},
    val onPageFrom: (String) -> Unit = {},
    val onPageTo: (String) -> Unit = {},
    val onNote: (String) -> Unit = {},
    val onColor: (String) -> Unit = {},
    val onKeepPhoto: (Boolean) -> Unit = {},
    val onScan: () -> Unit = {},
    val onChoosePhoto: () -> Unit = {},
    val onSave: () -> Unit = {},
)

/** Add quote (pushed from the book page or the Notes feed). */
@Composable
fun AddQuoteScreen(route: Route.AddQuote, navigator: AppNavigator) {
    val context = LocalContext.current
    val viewModel = appViewModel { AddQuoteViewModel(it, context, route.bookId, route.title, route.bookFixed, createSavedStateHandle()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) navigator.back() }

    var rationale by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.openCamera() else denied = true
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let(viewModel::photoPicked)
    }
    val choosePhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val scan = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) viewModel.openCamera()
        else rationale = true
    }
    var scanAsked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (route.scan && !scanAsked) {
            scanAsked = true
            scan()
        }
    }

    val step = state.scan
    if (step != null) {
        BackHandler { viewModel.closeScan() }
        ScanContent(
            step = step,
            onClose = viewModel::closeScan,
            onTaken = viewModel::photoTaken,
            onCaptureFailed = viewModel::captureFailed,
            onChoosePhoto = choosePhoto,
            onRetake = viewModel::openCamera,
            onToggle = viewModel::toggleLine,
            onLines = viewModel::setLines,
            onSelectAll = viewModel::selectAll,
            onUse = viewModel::useLines,
        )
    } else {
        // Not while saving: closing would cancel the save and delete the photo the user chose to keep,
        // although the quote may already be on the server.
        BackHandler(enabled = state.saving) {}
        AddQuoteContent(
            state = state,
            actions = AddQuoteActions(
                onBack = { if (!viewModel.state.value.saving) navigator.back() },
                onPickBook = viewModel::openPicker,
                onText = viewModel::setText,
                onPageFrom = viewModel::setPageFrom,
                onPageTo = viewModel::setPageTo,
                onNote = viewModel::setNote,
                onColor = viewModel::setColor,
                onKeepPhoto = viewModel::setKeepPhoto,
                onScan = scan,
                onChoosePhoto = choosePhoto,
                onSave = viewModel::save,
            ),
        )
    }

    state.picker?.let { picking ->
        BookPickerSheet(picking, onQuery = viewModel::search, onPick = viewModel::pickBook, onDismiss = viewModel::closePicker)
    }

    if (rationale) {
        AlertDialog(
            onDismissRequest = { rationale = false },
            icon = { LucideIcon("ScanLine", contentDescription = null, tint = OttershelfTheme.colors.primary, size = 24.dp) },
            title = { Text(stringResource(R.string.quotes_camera_title)) },
            text = { Text(stringResource(R.string.quotes_camera_rationale)) },
            confirmButton = {
                TextButton(onClick = { rationale = false; permission.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.quotes_camera_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { rationale = false; choosePhoto() }) { Text(stringResource(R.string.quotes_choose_photo)) }
            },
            containerColor = OttershelfTheme.colors.popover,
        )
    }
    if (denied) {
        AlertDialog(
            onDismissRequest = { denied = false },
            title = { Text(stringResource(R.string.quotes_camera_denied_title)) },
            text = { Text(stringResource(R.string.quotes_camera_denied)) },
            confirmButton = {
                TextButton(onClick = { denied = false; choosePhoto() }) { Text(stringResource(R.string.quotes_choose_photo)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { denied = false }) { Text(stringResource(R.string.quotes_cancel)) }
                    TextButton(onClick = {
                        denied = false
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) { Text(stringResource(R.string.quotes_open_settings)) }
                }
            },
            containerColor = OttershelfTheme.colors.popover,
        )
    }
}

/**
 * The form, in the Nexus look: the book, the quote (typed, or filled from a scanned page), the page
 * or pages, the user's thought, the colour, and Save.
 */
@Composable
internal fun AddQuoteContent(state: AddQuoteUiState, actions: AddQuoteActions) {
    val colors = OttershelfTheme.colors
    Scaffold(
        topBar = {
            DetailTopBar(
                title = stringResource(R.string.quotes_title),
                subtitle = state.book?.title,
                onBack = actions.onBack,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BookCard(state, actions.onPickBook)

            DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                CardTitle(stringResource(R.string.quotes_quote), icon = "Quote")
                OutlinedTextField(
                    value = state.text,
                    onValueChange = actions.onText,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 140.dp),
                    placeholder = { Text(stringResource(R.string.quotes_quote_hint), style = quoteStyle(16)) },
                    textStyle = quoteStyle(16).copy(color = colors.foreground),
                    minLines = 5,
                    shape = RoundedCornerShape(OttershelfTheme.radii.md),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.quotes_scan), onClick = actions.onScan, icon = "ScanLine", modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.quotes_choose_photo), onClick = actions.onChoosePhoto, icon = "Image", modifier = Modifier.weight(1f))
                }
                if (state.photo != null) {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.quotes_keep_photo), style = MaterialTheme.typography.bodyMedium, color = colors.foreground)
                            Text(stringResource(R.string.quotes_keep_photo_detail), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = state.keepPhoto, onCheckedChange = actions.onKeepPhoto)
                    }
                }
            }

            DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                CardTitle(stringResource(R.string.quotes_page), icon = "BookOpen")
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PageField(state.pageFrom, actions.onPageFrom, stringResource(R.string.quotes_page_from), Modifier.weight(1f))
                    Text("–", style = MaterialTheme.typography.titleMedium, color = colors.mutedForeground, modifier = Modifier.padding(horizontal = 10.dp))
                    PageField(state.pageTo, actions.onPageTo, stringResource(R.string.quotes_page_to), Modifier.weight(1f))
                }
                state.pageLabel?.let {
                    Text(
                        stringResource(R.string.quotes_page_saved, it),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.mutedForeground,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                CardTitle(stringResource(R.string.quotes_thought), icon = "NotebookPen")
                OutlinedTextField(
                    value = state.note,
                    onValueChange = actions.onNote,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    placeholder = { Text(stringResource(R.string.quotes_thought_hint)) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    minLines = 3,
                    shape = RoundedCornerShape(OttershelfTheme.radii.md),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }

            DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                CardTitle(stringResource(R.string.quotes_colour), icon = "Palette")
                ColourRow(state.color, actions.onColor, Modifier.padding(top = 12.dp))
            }

            state.saveError?.let {
                Text(stringResource(R.string.quotes_save_failed, it), style = MaterialTheme.typography.bodyMedium, color = colors.destructive)
            }
            AccentButton(
                text = stringResource(if (state.saving) R.string.quotes_saving else R.string.quotes_save),
                onClick = actions.onSave,
                icon = "Check",
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.quotes_where), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
        }
    }
}

@Composable
private fun BookCard(state: AddQuoteUiState, onPick: () -> Unit) {
    val colors = OttershelfTheme.colors
    val book = state.book
    DashCard(
        Modifier.fillMaxWidth(),
        onClick = if (state.bookFixed) null else onPick,
        contentPadding = PaddingValues(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (book != null) {
                BookCover(book.cover, book.title, Modifier.width(44.dp), authors = book.authors, seed = book.id.toString())
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        book.title ?: stringResource(R.string.notes_untitled),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.foreground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sub = if (state.bookError != null && book.authors == null) stringResource(R.string.quotes_book_failed) else book.authors
                    sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (!state.bookFixed) {
                    Text(stringResource(R.string.quotes_change_book), style = MaterialTheme.typography.labelLarge, color = colors.primary, modifier = Modifier.padding(start = 8.dp))
                }
            } else {
                Box(Modifier.size(44.dp).background(colors.accentTint, RoundedCornerShape(OttershelfTheme.radii.md)), contentAlignment = Alignment.Center) {
                    LucideIcon("BookPlus", contentDescription = null, tint = colors.primary, size = 22.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.quotes_book), style = MaterialTheme.typography.labelMedium, color = colors.mutedForeground)
                    Text(stringResource(R.string.quotes_choose_book), style = MaterialTheme.typography.titleMedium, color = colors.primary)
                }
                LucideIcon("ChevronRight", contentDescription = null, tint = colors.mutedForeground, size = 18.dp)
            }
        }
    }
}

@Composable
private fun PageField(value: String, onValue: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(OttershelfTheme.radii.md),
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
    )
}

/** The web's ten highlight colours as dots; the picked one ringed. */
@Composable
internal fun ColourRow(picked: String, onColor: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        HighlightColors.all.forEach { (name, hex) ->
            val on = picked.equals(hex, ignoreCase = true)
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (on) colors.foreground else Color.Transparent, CircleShape)
                    .padding(4.dp)
                    .background(highlightColor(hex), CircleShape)
                    .clickable(role = Role.Button) { onColor(hex) }
                    .semantics { contentDescription = name },
            )
        }
    }
}

/** Choosing the book: what the user is reading first, or the library searched. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookPickerSheet(state: PickerState, onQuery: (String) -> Unit, onPick: (QuoteBook) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        modifier = Modifier.belowStatusBar(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OttershelfTheme.colors.card,
    ) {
        BookPickerContent(state, onQuery, onPick)
    }
}

@Composable
internal fun BookPickerContent(state: PickerState, onQuery: (String) -> Unit, onPick: (QuoteBook) -> Unit) {
    val colors = OttershelfTheme.colors
    Column(Modifier.fillMaxWidth().heightIn(min = 360.dp).padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.quotes_search_books)) },
            leadingIcon = { LucideIcon("Search", contentDescription = null, tint = colors.mutedForeground, size = 18.dp) },
            singleLine = true,
            shape = RoundedCornerShape(OttershelfTheme.radii.md),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        Text(
            stringResource(if (state.query.isBlank()) R.string.quotes_reading_now else R.string.quotes_results),
            style = MaterialTheme.typography.labelMedium,
            color = colors.mutedForeground,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
        )
        when {
            state.loading && state.books.isEmpty() -> LoadingState(Modifier.fillMaxWidth().heightIn(min = 200.dp))
            state.error != null && state.books.isEmpty() ->
                ErrorState({ onQuery(state.query) }, Modifier.fillMaxWidth(), message = stringResource(R.string.quotes_books_failed), detail = state.error, compact = true)
            state.books.isEmpty() -> EmptyState(stringResource(R.string.quotes_no_books), Modifier.fillMaxWidth(), icon = "Search", compact = true)
            else -> LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.books, key = { it.id }) { book ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(OttershelfTheme.radii.md))
                            .clickable { onPick(book) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BookCover(book.cover, book.title, Modifier.width(36.dp), authors = book.authors, seed = book.id.toString())
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                book.title ?: stringResource(R.string.notes_untitled),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.foreground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            book.authors?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
