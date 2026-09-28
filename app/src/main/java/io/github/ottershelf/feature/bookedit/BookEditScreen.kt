package io.github.ottershelf.feature.bookedit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import io.github.ottershelf.R
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.CardTitle
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.DetailTopBar
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme

/** The boxes of the form, for showing a box's suggestions while it has the focus. */
enum class EditField { TITLE, AUTHORS, SERIES, NUMBER }

/** The form's callbacks, grouped so previews and screenshot tests pass none. */
internal class BookEditActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onTitle: (String) -> Unit = {},
    val onAuthorInput: (String) -> Unit = {},
    val onAddAuthor: (String) -> Unit = {},
    val onRemoveAuthor: (Int) -> Unit = {},
    val onSeries: (String) -> Unit = {},
    val onPickSeries: (String) -> Unit = {},
    val onClearSeries: () -> Unit = {},
    val onSeriesIndex: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    /** Asks first (the screen's dialog), then [BookEditViewModel.unlock]. */
    val onUnlock: (LockGroup) -> Unit = {},
    val onChoosePhoto: () -> Unit = {},
    val onTakePhoto: () -> Unit = {},
    val onFindOnline: () -> Unit = {},
    val onFileCover: () -> Unit = {},
)

/** Edit details (pushed from the book page's pencil). */
@Composable
fun BookEditScreen(route: Route.BookEdit, navigator: AppNavigator) {
    val context = LocalContext.current
    val viewModel = appViewModel { BookEditViewModel.create(it, context, route.bookId, createSavedStateHandle()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) navigator.back() }

    var focused by remember { mutableStateOf<EditField?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var unlockAsk by rememberSaveable { mutableStateOf<LockGroup?>(null) }
    var rationale by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let(viewModel::photoPicked)
    }
    val choosePhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.openCamera() else denied = true
    }
    val takePhoto = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) viewModel.openCamera()
        else rationale = true
    }
    val back: () -> Unit = {
        val s = viewModel.state.value
        when {
            s.saving -> Unit // the save finishes (or fails) first
            s.dirty -> discard = true
            else -> navigator.back()
        }
    }

    if (state.camera) {
        BackHandler(onBack = viewModel::closeCamera)
        CoverCameraContent(onClose = viewModel::closeCamera, onTaken = viewModel::photoTaken, onFailed = viewModel::cameraFailed, onChoosePhoto = {
            viewModel.closeCamera()
            choosePhoto()
        })
        return
    }

    BackHandler(enabled = state.saving || state.dirty, onBack = back)
    BookEditContent(
        state = state,
        focused = focused,
        onFocus = { field, has -> focused = if (has) field else focused.takeUnless { it == field } },
        actions = BookEditActions(
            onBack = back,
            onRetry = viewModel::load,
            onTitle = viewModel::setTitle,
            onAuthorInput = viewModel::setAuthorInput,
            onAddAuthor = viewModel::addAuthor,
            onRemoveAuthor = viewModel::removeAuthor,
            onSeries = viewModel::setSeries,
            onPickSeries = viewModel::pickSeries,
            onClearSeries = viewModel::clearSeries,
            onSeriesIndex = viewModel::setSeriesIndex,
            onSave = viewModel::save,
            onUnlock = { unlockAsk = it },
            onChoosePhoto = choosePhoto,
            onTakePhoto = takePhoto,
            onFindOnline = viewModel::openCoverSearch,
            onFileCover = viewModel::useFileCover,
        ),
    )

    state.coverSearch?.let { search ->
        CoverSearchSheet(
            state = search,
            onTitle = viewModel::setSearchTitle,
            onAuthor = viewModel::setSearchAuthor,
            onProvider = viewModel::setProvider,
            onSearch = viewModel::searchCovers,
            onPick = viewModel::pickOnline,
            onDismiss = viewModel::closeCoverSearch,
        )
    }
    state.pendingCover?.let { pending ->
        PendingCoverDialog(
            pending = pending,
            busy = state.coverBusy,
            failed = (state.coverMessage as? CoverMessage.Failed)?.error,
            onConfirm = viewModel::confirmCover,
            onCancel = viewModel::cancelCover,
        )
    }
    if (discard) {
        DiscardDialog(onDiscard = { discard = false; navigator.back() }, onKeep = { discard = false })
    }
    unlockAsk?.let { group ->
        UnlockDialog(group, onUnlock = { unlockAsk = null; viewModel.unlock(group) }, onCancel = { unlockAsk = null })
    }
    if (rationale) {
        CameraRationaleDialog(
            onContinue = { rationale = false; permission.launch(Manifest.permission.CAMERA) },
            onChoosePhoto = { rationale = false; choosePhoto() },
            onDismiss = { rationale = false },
        )
    }
    if (denied) {
        CameraDeniedDialog(
            onChoosePhoto = { denied = false; choosePhoto() },
            onSettings = {
                denied = false
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            onDismiss = { denied = false },
        )
    }
}

/**
 * The form in the app's look: the cover and its actions, then the title, authors (chips, with the
 * library's authors suggested as the user types), series (the library's series suggested) and number,
 * then Save and the line saying it's for everyone. [focused] is the box with the focus: its
 * suggestions show under it.
 */
@Composable
internal fun BookEditContent(
    state: BookEditUiState,
    actions: BookEditActions = BookEditActions(),
    focused: EditField? = null,
    onFocus: (EditField, Boolean) -> Unit = { _, _ -> },
) {
    Scaffold(
        topBar = { DetailTopBar(title = stringResource(R.string.bookedit_title), subtitle = state.title, onBack = actions.onBack) },
    ) { padding ->
        val body = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())
        when {
            !state.canEdit -> EmptyState(stringResource(R.string.bookedit_cant_edit), body, icon = "Lock")
            state.book == null && state.loadError != null -> Box(body, contentAlignment = Alignment.Center) {
                ErrorState(actions.onRetry, message = stringResource(R.string.bookedit_load_failed), detail = errorText(state.loadError).replaceFirstChar { it.uppercase() } + ".")
            }
            state.book == null -> LoadingState(body)
            else -> Column(
                body
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CoverCard(state, actions)
                DetailsCard(state, actions, focused, onFocus)
                SaveArea(state, actions)
            }
        }
    }
}

// --- the cover ------------------------------------------------------------------------------------

@Composable
private fun CoverCard(state: BookEditUiState, actions: BookEditActions) {
    val colors = OttershelfTheme.colors
    val book = state.book ?: return
    val locked = state.isLocked(LockGroup.COVER)
    val enabled = state.coverActionsEnabled
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle(stringResource(R.string.bookedit_cover), Modifier.weight(1f), icon = "Image")
            if (locked) LockPill()
        }
        Row(Modifier.padding(top = 12.dp)) {
            Box(Modifier.width(COVER_WIDTH)) {
                BookCover(
                    model = state.cover,
                    title = state.form.title.ifBlank { book.title.orEmpty() }.ifBlank { null },
                    modifier = Modifier.fillMaxWidth(),
                    authors = state.form.authors.joinToString(", ").ifBlank { null },
                    seed = book.title ?: book.id.toString(),
                    shape = RoundedCornerShape(OttershelfTheme.radii.md),
                    requestWidth = COVER_WIDTH,
                )
                if (state.coverBusy) {
                    val label = stringResource(R.string.bookedit_changing_cover)
                    Box(
                        Modifier
                            .matchParentSize()
                            .clip(RoundedCornerShape(OttershelfTheme.radii.md))
                            .background(colors.background.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Spinner(label)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (locked) {
                    Text(stringResource(R.string.bookedit_cover_locked), style = MaterialTheme.typography.bodyMedium, color = colors.mutedForeground)
                    UnlockButton(state, LockGroup.COVER, actions.onUnlock)
                } else {
                    CoverAction("Image", stringResource(R.string.bookedit_choose_photo), actions.onChoosePhoto, enabled)
                    CoverAction("Camera", stringResource(R.string.bookedit_take_photo), actions.onTakePhoto, enabled)
                    CoverAction("Search", stringResource(R.string.bookedit_find_online), actions.onFindOnline, enabled)
                    if (state.fromFile != null) CoverAction("FileImage", stringResource(R.string.bookedit_file_cover), actions.onFileCover, enabled)
                }
            }
        }
        // A failure of the confirmation shows in its dialog; the others here.
        val message = state.coverMessage.takeUnless { it is CoverMessage.Failed && state.pendingCover != null }
        message?.let { CoverMessageLine(it, Modifier.padding(top = 12.dp)) }
    }
}

@Composable
private fun CoverMessageLine(message: CoverMessage, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val (icon, text, tint) = when (message) {
        CoverMessage.Updated -> Triple("CircleCheckBig", stringResource(R.string.bookedit_cover_updated), colors.success)
        CoverMessage.NoneInFile -> Triple("Info", stringResource(R.string.bookedit_cover_none_in_file), colors.mutedForeground)
        CoverMessage.NoCoverLeft -> Triple("Info", stringResource(R.string.bookedit_cover_none_left), colors.mutedForeground)
        CoverMessage.PhotoUnreadable -> Triple("TriangleAlert", stringResource(R.string.bookedit_photo_unreadable), colors.destructive)
        CoverMessage.CameraFailed -> Triple("TriangleAlert", stringResource(R.string.bookedit_camera_failed), colors.destructive)
        is CoverMessage.Failed -> Triple("TriangleAlert", stringResource(R.string.bookedit_cover_failed, errorText(message.error)), colors.destructive)
    }
    Row(modifier, verticalAlignment = Alignment.Top) {
        LucideIcon(icon, contentDescription = null, tint = tint, size = 16.dp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (tint == colors.success) colors.foreground else tint)
    }
}

/** A cover action: the book page's DetailAction look (card fill, border, md radius, icon and label at the start), a little shorter. */
@Composable
private fun CoverAction(icon: String, text: String, onClick: () -> Unit, enabled: Boolean) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.md)
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.border, shape)
            .clickable(enabled = enabled, role = Role.Button, indication = ripple(color = colors.primary), interactionSource = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon(icon, contentDescription = null, tint = if (enabled) colors.primary else colors.mutedForeground, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            color = if (enabled) colors.foreground else colors.mutedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --- the text fields ------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsCard(state: BookEditUiState, actions: BookEditActions, focused: EditField?, onFocus: (EditField, Boolean) -> Unit) {
    val colors = OttershelfTheme.colors
    val form = state.form
    val problems = state.problems
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        CardTitle(stringResource(R.string.bookedit_details), icon = "Pencil")

        // Title
        val titleLocked = state.isLocked(LockGroup.TITLE)
        FieldLabel(stringResource(R.string.bookedit_field_title), state, LockGroup.TITLE, actions.onUnlock, Modifier.padding(top = 14.dp))
        val focusManager = LocalFocusManager.current
        OutlinedTextField(
            value = form.title,
            // It wraps, but it's one line: the keyboard's Enter goes on to the authors.
            onValueChange = { text ->
                if ('\n' in text) {
                    actions.onTitle(text.replace("\n", ""))
                    focusManager.moveFocus(FocusDirection.Down)
                } else {
                    actions.onTitle(text)
                }
            },
            modifier = Modifier.fillMaxWidth().onFocusChanged { onFocus(EditField.TITLE, it.isFocused) },
            enabled = !titleLocked,
            textStyle = MaterialTheme.typography.bodyLarge,
            maxLines = 3,
            isError = FormProblem.TITLE_EMPTY in problems,
            supportingText = if (FormProblem.TITLE_EMPTY in problems) ({ Text(stringResource(R.string.bookedit_title_empty)) }) else null,
            trailingIcon = if (titleLocked) ({ LucideIcon("Lock", contentDescription = null, tint = colors.mutedForeground, size = 16.dp) }) else null,
            shape = RoundedCornerShape(OttershelfTheme.radii.md),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
        )

        // Authors
        val authorsLocked = state.isLocked(LockGroup.AUTHORS)
        FieldLabel(stringResource(R.string.bookedit_field_authors), state, LockGroup.AUTHORS, actions.onUnlock, Modifier.padding(top = 14.dp))
        if (form.authors.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                form.authors.forEachIndexed { i, name -> AuthorChip(name, removable = !authorsLocked) { actions.onRemoveAuthor(i) } }
            }
        }
        if (!authorsLocked) {
            val rows = authorRows(state.authorInput, state.authorSuggestions, form.authors)
            OutlinedTextField(
                value = state.authorInput,
                onValueChange = actions.onAuthorInput,
                modifier = Modifier.fillMaxWidth().onFocusChanged { onFocus(EditField.AUTHORS, it.isFocused) },
                placeholder = { Text(stringResource(R.string.bookedit_author_hint)) },
                leadingIcon = { LucideIcon("Plus", contentDescription = null, tint = colors.mutedForeground, size = 18.dp) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                isError = FormProblem.NO_AUTHORS in problems,
                supportingText = if (FormProblem.NO_AUTHORS in problems) ({ Text(stringResource(R.string.bookedit_no_authors)) }) else null,
                shape = RoundedCornerShape(OttershelfTheme.radii.md),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (state.authorInput.isNotBlank()) actions.onAddAuthor(state.authorInput) else defaultKeyboardAction(ImeAction.Done) }),
            )
            if (focused == EditField.AUTHORS && rows.isNotEmpty()) {
                SuggestionList(Modifier.padding(top = 6.dp)) {
                    rows.forEach { row ->
                        when (row) {
                            is AuthorRow.Add -> SuggestionRow(
                                icon = "Plus",
                                text = stringResource(R.string.bookedit_add_author, row.name),
                                detail = stringResource(R.string.bookedit_new_to_library),
                                accent = true,
                                onClick = { actions.onAddAuthor(row.name) },
                            )
                            is AuthorRow.Existing -> SuggestionRow(
                                icon = "User",
                                text = row.suggestion.name,
                                trailing = pluralStringResource(R.plurals.bookedit_books, row.suggestion.bookCount, row.suggestion.bookCount),
                                onClick = { actions.onAddAuthor(row.suggestion.name) },
                            )
                        }
                    }
                    if (state.authorSuggestions.loading && rows.none { it is AuthorRow.Existing }) SearchingRow()
                }
            }
        }

        // Series and its number
        val seriesLocked = state.isLocked(LockGroup.SERIES)
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.Bottom) {
            FieldLabel(stringResource(R.string.bookedit_field_series), state, LockGroup.SERIES, actions.onUnlock, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            FieldLabel(stringResource(R.string.bookedit_field_number), state = null, group = null, onUnlock = {}, modifier = Modifier.width(NUMBER_WIDTH))
        }
        val hasSeries = form.series.isNotBlank()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = form.series,
                onValueChange = actions.onSeries,
                modifier = Modifier.weight(1f).onFocusChanged { onFocus(EditField.SERIES, it.isFocused) },
                enabled = !seriesLocked,
                placeholder = { Text(stringResource(R.string.bookedit_series_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                trailingIcon = when {
                    seriesLocked -> ({ LucideIcon("Lock", contentDescription = null, tint = colors.mutedForeground, size = 16.dp) })
                    hasSeries -> ({ ClearButton(stringResource(R.string.bookedit_clear_series), actions.onClearSeries) })
                    else -> null
                },
                shape = RoundedCornerShape(OttershelfTheme.radii.md),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(
                value = form.seriesIndex,
                onValueChange = actions.onSeriesIndex,
                modifier = Modifier.width(NUMBER_WIDTH).onFocusChanged { onFocus(EditField.NUMBER, it.isFocused) },
                enabled = hasSeries && !seriesLocked,
                placeholder = { Text(stringResource(R.string.bookedit_number_hint), maxLines = 1) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                isError = FormProblem.SERIES_INDEX_INVALID in problems,
                shape = RoundedCornerShape(OttershelfTheme.radii.md),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            )
        }
        if (FormProblem.SERIES_INDEX_INVALID in problems) {
            HelperLine(stringResource(R.string.bookedit_index_invalid), error = true)
        }
        val seriesListRows = if (!seriesLocked && focused == EditField.SERIES) seriesRows(form.series, state.seriesSuggestions) else emptyList()
        if (!seriesLocked && focused == EditField.SERIES) {
            val rows = seriesListRows
            if (rows.isNotEmpty()) {
                SuggestionList(Modifier.padding(top = 6.dp)) {
                    rows.forEach { s ->
                        val count = pluralStringResource(R.plurals.bookedit_books, s.bookCount, s.bookCount)
                        SuggestionRow(
                            icon = "Library",
                            text = s.name,
                            detail = if (s.authors.isEmpty()) count else stringResource(R.string.bookedit_series_line, count, s.authors.take(2).joinToString(", ")),
                            onClick = { actions.onPickSeries(s.name) },
                        )
                    }
                }
            } else if (state.seriesSuggestions.loading && form.series.isNotBlank() && state.seriesSuggestions.items.isEmpty()) {
                SuggestionList(Modifier.padding(top = 6.dp)) { SearchingRow() }
            }
        }
        // A new series, said once the user has stopped at a name (not while the list offers others).
        val original = state.original
        if (!seriesLocked && original != null && seriesListRows.isEmpty() &&
            EditRules.normalizeName(form.series) != EditRules.normalizeName(original.series) &&
            isNewSeries(form.series, state.seriesSuggestions)
        ) {
            HelperLine(stringResource(R.string.bookedit_new_series), error = false)
        }
    }
}

/** A field's label, with the lock and Unlock at the end when the server locked it. */
@Composable
private fun FieldLabel(text: String, state: BookEditUiState?, group: LockGroup?, onUnlock: (LockGroup) -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    Row(modifier.heightIn(min = 28.dp).padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = colors.mutedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state != null && group != null && state.isLocked(group)) {
            LockPill()
            Spacer(Modifier.width(6.dp))
            UnlockLink(state, group, onUnlock)
        }
    }
}

@Composable
private fun LockPill() {
    val colors = OttershelfTheme.colors
    Row(
        Modifier
            .clip(CircleShape)
            .background(colors.accentTint)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon("Lock", contentDescription = null, tint = colors.primary, size = 12.dp)
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.bookedit_locked), style = MaterialTheme.typography.labelMedium, color = colors.primary)
    }
}

@Composable
private fun UnlockLink(state: BookEditUiState, group: LockGroup, onUnlock: (LockGroup) -> Unit) {
    val colors = OttershelfTheme.colors
    val busy = state.unlocking == group
    Text(
        stringResource(if (busy) R.string.bookedit_unlocking else R.string.bookedit_unlock),
        modifier = Modifier
            .clip(RoundedCornerShape(OttershelfTheme.radii.sm))
            .clickable(enabled = state.unlocking == null && state.canEdit, role = Role.Button) { onUnlock(group) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (state.unlocking == null) colors.primary else colors.mutedForeground,
    )
}

@Composable
private fun UnlockButton(state: BookEditUiState, group: LockGroup, onUnlock: (LockGroup) -> Unit) {
    val busy = state.unlocking == group
    CoverAction(
        icon = "LockOpen",
        text = stringResource(if (busy) R.string.bookedit_unlocking else R.string.bookedit_unlock),
        onClick = { onUnlock(group) },
        enabled = state.unlocking == null && state.canEdit,
    )
}

@Composable
private fun AuthorChip(name: String, removable: Boolean, onRemove: () -> Unit) {
    val colors = OttershelfTheme.colors
    Row(
        Modifier
            .clip(CircleShape)
            .background(colors.muted)
            .padding(start = 12.dp, end = if (removable) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            // Measured after the X, so a long name ends in "…" and the X keeps its size.
            modifier = Modifier.weight(1f, fill = false).padding(vertical = 7.dp),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = if (removable) colors.foreground else colors.mutedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (removable) {
            val label = stringResource(R.string.bookedit_remove_author, name)
            Box(
                Modifier
                    .padding(start = 2.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = label, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon("X", contentDescription = label, tint = colors.mutedForeground, size = 14.dp)
            }
        }
    }
}

@Composable
private fun ClearButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon("X", contentDescription = label, tint = OttershelfTheme.colors.mutedForeground, size = 16.dp)
    }
}

/** The list under a box: the popover colour, a border, the lg radius; brought into view as it opens. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestionList(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.lg)
    val requester = remember { BringIntoViewRequester() }
    val inPreview = LocalInspectionMode.current
    LaunchedEffect(Unit) { if (!inPreview) runCatching { requester.bringIntoView() } }
    Column(
        modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .clip(shape)
            .background(colors.popover)
            .border(1.dp, colors.border, shape)
            .padding(vertical = 4.dp),
    ) {
        content()
    }
}

@Composable
private fun SuggestionRow(
    icon: String,
    text: String,
    onClick: () -> Unit,
    detail: String? = null,
    trailing: String? = null,
    accent: Boolean = false,
) {
    val colors = OttershelfTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon(icon, contentDescription = null, tint = if (accent) colors.primary else colors.mutedForeground, size = 18.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (accent) colors.primary else colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground, maxLines = 1)
        }
    }
}

@Composable
private fun SearchingRow() {
    val colors = OttershelfTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Spinner(null, size = 16.dp)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.bookedit_searching), style = MaterialTheme.typography.bodyMedium, color = colors.mutedForeground)
    }
}

@Composable
private fun HelperLine(text: String, error: Boolean) {
    val colors = OttershelfTheme.colors
    Text(
        text,
        // Where a text field's own supporting text starts.
        modifier = Modifier.padding(top = 4.dp, start = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (error) colors.destructive else colors.mutedForeground,
    )
}

/** An accent spinner (a still one in previews and screenshots); [label] is read out. */
@Composable
internal fun Spinner(label: String?, size: Dp = 28.dp) {
    val colors = OttershelfTheme.colors
    val modifier = Modifier.size(size).then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
    if (LocalInspectionMode.current) {
        CircularProgressIndicator(progress = { 0.3f }, modifier = modifier, color = colors.primary, trackColor = colors.muted, strokeWidth = 3.dp)
    } else {
        CircularProgressIndicator(modifier = modifier, color = colors.primary, trackColor = colors.muted, strokeWidth = 3.dp)
    }
}

// --- saving ---------------------------------------------------------------------------------------

@Composable
private fun SaveArea(state: BookEditUiState, actions: BookEditActions) {
    val colors = OttershelfTheme.colors
    val error = state.unlockError?.let { stringResource(R.string.bookedit_unlock_failed, errorText(it)) }
        ?: state.saveError?.let { stringResource(R.string.bookedit_save_failed, errorText(it)) }
    error?.let {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            LucideIcon("TriangleAlert", contentDescription = null, tint = colors.destructive, size = 16.dp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.destructive)
        }
    }
    AccentButton(
        text = stringResource(if (state.saving) R.string.bookedit_saving else R.string.bookedit_save),
        onClick = actions.onSave,
        icon = "Check",
        enabled = state.canSave,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
        LucideIcon("Users", contentDescription = null, tint = colors.mutedForeground, size = 16.dp, modifier = Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.bookedit_everyone), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
    }
}

/** An [EditError] as the end of a sentence ("Not saved: you're offline."). */
@Composable
internal fun errorText(error: EditError): String = when (error) {
    EditError.Offline -> stringResource(R.string.bookedit_error_offline)
    EditError.Forbidden -> stringResource(R.string.bookedit_error_forbidden)
    is EditError.Server -> error.message.trim().trimEnd('.')
    is EditError.Http -> stringResource(R.string.bookedit_error_http, error.code)
}

private val COVER_WIDTH = 120.dp
private val NUMBER_WIDTH = 96.dp
