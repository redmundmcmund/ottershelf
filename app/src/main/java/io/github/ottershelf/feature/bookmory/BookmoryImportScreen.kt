package io.github.ottershelf.feature.bookmory

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ottershelf.R
import io.github.ottershelf.feature.settings.SettingsCard
import io.github.ottershelf.feature.settings.SettingsDivider
import io.github.ottershelf.feature.settings.SettingsGroupLabel
import io.github.ottershelf.feature.settings.SettingsHint
import io.github.ottershelf.feature.settings.SettingsLabel
import io.github.ottershelf.feature.settings.SettingsRowPadding
import io.github.ottershelf.feature.settings.SettingsSwitchRow
import io.github.ottershelf.feature.settings.SettingsValue
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.DetailTopBar
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.components.PillProgressBar
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfFonts
import io.github.ottershelf.ui.theme.OttershelfTheme
import io.github.ottershelf.ui.theme.atAlpha
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The MIME types the picker offers: Bookmory's CSV, and anything (some file managers call it text/plain). */
private val CSV_TYPES = arrayOf("text/csv", "text/comma-separated-values", "*/*")

@Composable
fun BookmoryImportScreen(navigator: AppNavigator) {
    val viewModel = appViewModel { BookmoryImportViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.open(uri, resolver)
    }
    var askStop by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = state.step == ImportStep.RUNNING) { askStop = true }
    if (askStop && state.step == ImportStep.RUNNING) {
        AlertDialog(
            onDismissRequest = { askStop = false },
            title = { Text(stringResource(R.string.bookmory_stop_title)) },
            text = { Text(stringResource(R.string.bookmory_stop_body)) },
            confirmButton = {
                TextButton(onClick = { askStop = false; viewModel.stop() }) { Text(stringResource(R.string.bookmory_stop)) }
            },
            dismissButton = { TextButton(onClick = { askStop = false }) { Text(stringResource(R.string.bookmory_keep_going)) } },
        )
    }
    BookmoryImportContent(
        state = state,
        onBack = { if (state.step == ImportStep.RUNNING) askStop = true else navigator.back() },
        onPickFile = { picker.launch(CSV_TYPES) },
        onLibrary = viewModel::pickLibrary,
        onKeepDates = viewModel::keepDates,
        onRowChoice = viewModel::chooseRow,
        onImport = viewModel::import,
        onAgain = viewModel::again,
        onDone = { navigator.back() },
    )
}

/**
 * The import, step by step, in the Settings screens' look: the intro and file picker, the
 * preview (totals, the library for placeholder books, the backlog days, every row with its match),
 * the progress, and the summary.
 */
@Composable
fun BookmoryImportContent(
    state: BookmoryUiState,
    onBack: () -> Unit = {},
    onPickFile: () -> Unit = {},
    onLibrary: (Long) -> Unit = {},
    onKeepDates: (LocalDate, Boolean) -> Unit = { _, _ -> },
    onRowChoice: (Int, RowChoice?) -> Unit = { _, _ -> },
    onImport: () -> Unit = {},
    onAgain: () -> Unit = {},
    onDone: () -> Unit = {},
    initiallyShowRows: Boolean = false,
    initiallyOpenRow: Int? = null,
) {
    val colors = OttershelfTheme.colors
    var showRows by rememberSaveable { mutableStateOf(initiallyShowRows) }
    var openRow by rememberSaveable { mutableStateOf(initiallyOpenRow) }
    Scaffold(
        topBar = { DetailTopBar(title = stringResource(R.string.bookmory_title), onBack = onBack) },
        containerColor = colors.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when (state.step) {
                    ImportStep.INTRO -> item { Intro(onPickFile) }
                    ImportStep.READING, ImportStep.MATCHING -> item {
                        Working(stringResource(if (state.step == ImportStep.READING) R.string.bookmory_reading_file else R.string.bookmory_matching))
                    }
                    ImportStep.FAILED -> item { Failed(state, onPickFile, onAgain) }
                    ImportStep.PREVIEW -> state.preview?.let { preview ->
                        preview(state, preview, showRows, openRow, { showRows = !showRows }, { openRow = if (openRow == it) null else it }, onLibrary, onKeepDates, onRowChoice, onImport)
                    }
                    ImportStep.RUNNING -> item { Running(state) }
                    ImportStep.DONE -> state.summary?.let { summary -> done(summary, onAgain, onDone) }
                }
            }
        }
    }
}

@Composable
private fun Intro(onPickFile: () -> Unit) {
    SettingsCard {
        Column(SettingsRowPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBox("FileUp")
                SettingsLabel(stringResource(R.string.bookmory_intro_title))
            }
            BodyText(stringResource(R.string.bookmory_intro_body))
            BodyText(stringResource(R.string.bookmory_intro_stubs))
            AccentButton(stringResource(R.string.bookmory_choose_file), onClick = onPickFile, icon = "FolderOpen", modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Working(message: String) {
    SettingsCard {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LoadingState(Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            BodyText(message)
        }
    }
}

@Composable
private fun Failed(state: BookmoryUiState, onPickFile: () -> Unit, onAgain: () -> Unit) {
    val message = stringResource(
        when (state.failure) {
            ImportFailure.NOT_BOOKMORY -> R.string.bookmory_failed_not_bookmory
            ImportFailure.TOO_LARGE -> R.string.bookmory_failed_too_large
            ImportFailure.EMPTY -> R.string.bookmory_failed_empty
            ImportFailure.SERVER -> R.string.bookmory_failed_server
            ImportFailure.UNREADABLE, null -> R.string.bookmory_failed_unreadable
        },
    )
    SettingsCard {
        Column(SettingsRowPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBox("TriangleAlert", danger = true)
                Column(Modifier.weight(1f)) {
                    SettingsLabel(message)
                    state.failureDetail?.let { SettingsHint(it) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.failure == ImportFailure.SERVER) AccentButton(stringResource(R.string.bookmory_try_again), onClick = onAgain, icon = "RotateCw")
                SecondaryButton(stringResource(R.string.bookmory_choose_other), onClick = onPickFile, icon = "FolderOpen")
            }
        }
    }
}

private fun LazyListScope.preview(
    state: BookmoryUiState,
    preview: ImportPreview,
    showRows: Boolean,
    openRow: Int?,
    onToggleRows: () -> Unit,
    onOpenRow: (Int) -> Unit,
    onLibrary: (Long) -> Unit,
    onKeepDates: (LocalDate, Boolean) -> Unit,
    onRowChoice: (Int, RowChoice?) -> Unit,
    onImport: () -> Unit,
) {
    val t = preview.totals
    item(key = "totals") {
        Column {
            SettingsGroupLabel(stringResource(R.string.bookmory_group_summary))
            SettingsCard {
                ValueRow(stringResource(R.string.bookmory_total_rows), t.rows)
                SettingsDivider()
                ValueRow(stringResource(R.string.bookmory_total_matched), t.matched)
                if (t.ambiguous > 0) { SettingsDivider(); ValueRow(stringResource(R.string.bookmory_total_ambiguous), t.ambiguous, highlight = true) }
                SettingsDivider()
                ValueRow(stringResource(R.string.bookmory_total_stubs), t.stubs)
                SettingsDivider()
                ValueRow(stringResource(R.string.bookmory_total_readings), t.readingsToAdd)
                SettingsDivider()
                ValueRow(stringResource(R.string.bookmory_total_present), t.readingsPresent)
                if (t.readingsLeftAlone > 0) { SettingsDivider(); ValueRow(stringResource(R.string.bookmory_total_left_alone), t.readingsLeftAlone) }
                SettingsDivider()
                ValueRow(stringResource(R.string.bookmory_total_statuses), t.statuses)
                SettingsDivider()
                ValueRow(stringResource(R.string.bookmory_total_ratings), t.ratings)
                if (t.ratingsBlocked > 0) { SettingsDivider(); ValueRow(stringResource(R.string.bookmory_total_ratings_blocked), t.ratingsBlocked) }
                SettingsDivider()
                ValueRow(stringResource(R.string.bookmory_total_skipped), t.skipped)
            }
        }
    }
    if (t.ratingsNotPermitted) item(key = "ratings") {
        SettingsCard {
            Row(SettingsRowPadding, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBox("StarOff")
                BodyText(stringResource(R.string.bookmory_ratings_not_permitted), Modifier.weight(1f))
            }
        }
    }
    item(key = "action") { ImportAction(preview, onImport) }

    val needsLibrary = preview.rows.any { it.kind == RowKind.STUB || it.kind == RowKind.NO_LIBRARY }
    if (needsLibrary) item(key = "library") {
        Column {
            SettingsGroupLabel(stringResource(R.string.bookmory_group_library))
            SettingsCard {
                Column(SettingsRowPadding) {
                    SettingsHint(stringResource(if (state.canUpload) R.string.bookmory_library_hint else R.string.bookmory_library_none))
                }
                state.libraries.takeIf { state.canUpload }?.forEach { library ->
                    SettingsDivider()
                    RadioRow(library.name, selected = state.choices.libraryId == library.id, icon = "Library") { onLibrary(library.id) }
                }
                if (state.canUpload && state.choices.libraryId == null) {
                    SettingsDivider()
                    Column(SettingsRowPadding) { SettingsHint(stringResource(R.string.bookmory_library_pick)) }
                }
            }
        }
    }

    if (preview.backlog.isNotEmpty()) item(key = "backlog") {
        Column {
            SettingsGroupLabel(stringResource(R.string.bookmory_group_backlog))
            SettingsCard {
                Column(SettingsRowPadding) { SettingsHint(stringResource(R.string.bookmory_backlog_hint)) }
                preview.backlog.forEach { (day, count) ->
                    val keep = day !in state.choices.undated
                    SettingsDivider()
                    SettingsSwitchRow(
                        icon = "CalendarDays",
                        title = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                        summary = pluralStringResource(
                            if (keep) R.plurals.bookmory_backlog_count else R.plurals.bookmory_backlog_count_undated,
                            count,
                            count,
                        ),
                        checked = keep,
                        onChange = { keep -> onKeepDates(day, keep) },
                    )
                }
            }
        }
    }

    item(key = "rows-header") {
        Column {
            SettingsGroupLabel(stringResource(R.string.bookmory_group_rows, preview.rows.size))
            SettingsCard {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onToggleRows).then(SettingsRowPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconBox("List")
                    SettingsLabel(stringResource(if (showRows) R.string.bookmory_hide_rows else R.string.bookmory_show_rows), Modifier.weight(1f))
                    LucideIcon(if (showRows) "ChevronUp" else "ChevronDown", contentDescription = null, tint = OttershelfTheme.colors.mutedForeground, size = 18.dp)
                }
            }
        }
    }
    if (showRows) {
        // Rows that need the user come first.
        val ordered = preview.rows.sortedBy { if (it.kind == RowKind.AMBIGUOUS) 0 else 1 }
        items(ordered, key = { "row-${it.line}" }) { row ->
            RowCard(row, open = openRow == row.line, onOpen = { onOpenRow(row.line) }, onChoice = { onRowChoice(row.line, it) })
        }
    }

    if (preview.problems.isNotEmpty()) item(key = "problems") {
        Column {
            SettingsGroupLabel(stringResource(R.string.bookmory_group_problems, preview.problems.size))
            SettingsCard {
                preview.problems.forEachIndexed { i, p ->
                    if (i > 0) SettingsDivider()
                    val reason = stringResource(
                        when (p.reason) {
                            BookmoryProblem.Reason.WRONG_COLUMN_COUNT -> R.string.bookmory_problem_columns
                            BookmoryProblem.Reason.NO_TITLE -> R.string.bookmory_problem_title
                            BookmoryProblem.Reason.BAD_DATE -> R.string.bookmory_problem_date
                        },
                    )
                    Column(SettingsRowPadding) {
                        SettingsLabel(p.title ?: stringResource(R.string.bookmory_problem_untitled))
                        SettingsHint(stringResource(R.string.bookmory_problem_line, p.line, reason))
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportAction(preview: ImportPreview, onImport: () -> Unit) {
    if (preview.nothingNew) {
        SettingsCard {
            Row(SettingsRowPadding, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBox("CircleCheck")
                Column(Modifier.weight(1f)) {
                    SettingsLabel(stringResource(R.string.bookmory_nothing_new))
                    SettingsHint(stringResource(R.string.bookmory_nothing_new_hint))
                }
            }
        }
    } else {
        AccentButton(
            stringResource(R.string.bookmory_import),
            onClick = onImport,
            icon = "Import",
            modifier = Modifier.fillMaxWidth(),
            enabled = preview.items.isNotEmpty(),
        )
    }
}

@Composable
private fun RowCard(row: PreviewRow, open: Boolean, onOpen: () -> Unit, onChoice: (RowChoice?) -> Unit) {
    val colors = OttershelfTheme.colors
    SettingsCard {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).then(SettingsRowPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    // Bookmory's U+FFFD was nearly always an apostrophe.
                    row.title.replace('�', '\''),
                    style = TextStyle(fontFamily = OttershelfFonts.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp),
                    color = if (row.kind == RowKind.EXCLUDED) colors.mutedForeground else colors.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                SettingsHint(authorsOf(row.authors))
                val detail = when {
                    row.kind == RowKind.EXCLUDED || row.kind == RowKind.AMBIGUOUS || row.kind == RowKind.NO_LIBRARY -> null
                    row.readingsToAdd > 0 -> pluralStringResource(R.plurals.bookmory_row_readings, row.readingsToAdd, row.readingsToAdd)
                    row.writes > 0 -> stringResource(R.string.bookmory_row_changes)
                    row.readingsLeftAlone > 0 -> stringResource(R.string.bookmory_row_left_alone)
                    else -> stringResource(R.string.bookmory_row_nothing)
                }
                detail?.let { SettingsHint(it) }
                (row.ratingBlockedBy as? RatingRule.NotEditor)?.let { SettingsHint(stringResource(R.string.bookmory_row_rating_not_editor, it.library)) }
            }
            KindChip(row.kind)
        }
        if (open) {
            SettingsDivider()
            row.matchedBy?.takeIf { row.kind == RowKind.MATCHED }?.let { by ->
                Column(SettingsRowPadding) {
                    SettingsHint(
                        stringResource(
                            when (by) {
                                MatchedBy.ISBN -> R.string.bookmory_matched_by_isbn
                                MatchedBy.TITLE -> R.string.bookmory_matched_by_title
                                MatchedBy.SHORT_TITLE -> R.string.bookmory_matched_by_short_title
                            },
                        ),
                    )
                }
            }
            row.candidates.forEach { c ->
                RadioRow(
                    stringResource(R.string.bookmory_candidate, listOfNotNull(c.title, c.subtitle?.takeIf { it.isNotBlank() }).joinToString(": "), authorsOf(c.authors)),
                    selected = row.kind == RowKind.MATCHED && row.book?.id == c.id,
                    icon = "Book",
                ) { onChoice(RowChoice.Book(c.id)) }
            }
            if (row.canStub && row.kind != RowKind.STUB && row.kind != RowKind.NO_LIBRARY) {
                RadioRow(stringResource(R.string.bookmory_use_placeholder), selected = false, icon = "FilePlus") { onChoice(RowChoice.Stub) }
            }
            if (row.kind == RowKind.EXCLUDED) {
                RadioRow(stringResource(R.string.bookmory_include), selected = false, icon = "Undo2") { onChoice(null) }
            } else {
                RadioRow(stringResource(R.string.bookmory_leave_out), selected = false, icon = "Ban") { onChoice(RowChoice.Exclude) }
            }
        }
    }
}

@Composable
private fun authorsOf(authors: List<String>): String =
    authors.joinToString(", ").ifEmpty { stringResource(R.string.bookmory_unknown_author) }

@Composable
private fun KindChip(kind: RowKind) {
    val colors = OttershelfTheme.colors
    val (label, color) = when (kind) {
        RowKind.MATCHED -> R.string.bookmory_kind_matched to colors.success
        RowKind.STUB -> R.string.bookmory_kind_stub to colors.primary
        RowKind.AMBIGUOUS -> R.string.bookmory_kind_ambiguous to colors.warning
        RowKind.EXCLUDED -> R.string.bookmory_kind_excluded to colors.mutedForeground
        RowKind.NO_LIBRARY -> R.string.bookmory_kind_no_library to colors.warning
    }
    Text(
        stringResource(label),
        modifier = Modifier
            .background(color.atAlpha(0.14f), OttershelfTheme.radii.mdShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = TextStyle(fontFamily = OttershelfFonts.Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp),
        color = color,
        maxLines = 1,
    )
}

@Composable
private fun Running(state: BookmoryUiState) {
    SettingsCard {
        Column(SettingsRowPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBox("Import")
                Column(Modifier.weight(1f)) {
                    SettingsLabel(stringResource(R.string.bookmory_running))
                    SettingsHint(stringResource(R.string.bookmory_running_progress, state.done, state.total))
                }
            }
            PillProgressBar(if (state.total == 0) 0f else state.done.toFloat() / state.total)
            SettingsHint(stringResource(R.string.bookmory_running_hint))
        }
    }
}

private fun LazyListScope.done(summary: ImportSummary, onAgain: () -> Unit, onDone: () -> Unit) {
    if (summary.interrupted) item(key = "interrupted") {
        SettingsCard {
            Row(SettingsRowPadding, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBox("TriangleAlert", danger = true)
                BodyText(stringResource(R.string.bookmory_interrupted), Modifier.weight(1f))
            }
        }
    }
    item(key = "done") {
        Column {
            SettingsGroupLabel(stringResource(R.string.bookmory_group_done))
            SettingsCard {
                if (summary.nothingChanged && !summary.interrupted) {
                    Column(SettingsRowPadding) { SettingsHint(stringResource(R.string.bookmory_done_nothing)) }
                    SettingsDivider()
                }
                ValueRow(stringResource(R.string.bookmory_done_stubs), summary.stubsCreated.size)
                if (summary.stubsFound > 0) { SettingsDivider(); ValueRow(stringResource(R.string.bookmory_done_stubs_found), summary.stubsFound) }
                SettingsDivider()
                ValueRow(stringResource(R.string.bookmory_done_readings), summary.readingsAdded)
                SettingsDivider()
                ValueRow(stringResource(R.string.bookmory_done_present), summary.readingsPresent)
                if (summary.readingsLeftAlone > 0) { SettingsDivider(); ValueRow(stringResource(R.string.bookmory_done_left_alone), summary.readingsLeftAlone) }
                SettingsDivider()
                ValueRow(stringResource(R.string.bookmory_done_statuses), summary.statusesSet)
                SettingsDivider()
                ValueRow(stringResource(R.string.bookmory_done_ratings), summary.ratingsSet)
                if (summary.ratingsLeftOut > 0) { SettingsDivider(); ValueRow(stringResource(R.string.bookmory_done_ratings_left_out), summary.ratingsLeftOut) }
            }
        }
    }
    item(key = "buttons") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton(stringResource(R.string.bookmory_done), onClick = onDone, icon = "Check", modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.bookmory_import_again), onClick = onAgain, icon = "RotateCw", modifier = Modifier.weight(1f))
        }
    }
    if (summary.problems.isNotEmpty()) item(key = "errors") {
        Column {
            SettingsGroupLabel(stringResource(R.string.bookmory_group_errors, summary.problems.size))
            SettingsCard {
                summary.problems.forEachIndexed { i, p ->
                    if (i > 0) SettingsDivider()
                    val reason = stringResource(
                        when (p.kind) {
                            ImportProblem.Kind.STUB_DELETED -> R.string.bookmory_error_stub_deleted
                            ImportProblem.Kind.BOOK_GONE -> R.string.bookmory_error_book_gone
                            ImportProblem.Kind.UPLOAD_FAILED -> R.string.bookmory_error_upload
                            ImportProblem.Kind.UPLOAD_PENDING -> R.string.bookmory_error_pending
                            ImportProblem.Kind.WRITE_FAILED -> R.string.bookmory_error_write
                            ImportProblem.Kind.RATING_REFUSED -> R.string.bookmory_error_rating
                            ImportProblem.Kind.RATING_NOT_EDITOR -> R.string.bookmory_error_rating_library
                        },
                    )
                    Column(SettingsRowPadding) {
                        SettingsLabel(p.title)
                        SettingsHint(p.detail?.let { stringResource(R.string.bookmory_error_line, reason, it) } ?: reason)
                    }
                }
            }
        }
    }
    if (summary.stubsCreated.isNotEmpty()) item(key = "created") {
        Column {
            SettingsGroupLabel(stringResource(R.string.bookmory_group_created, summary.stubsCreated.size))
            SettingsCard {
                summary.stubsCreated.forEachIndexed { i, title ->
                    if (i > 0) SettingsDivider()
                    Column(SettingsRowPadding) { SettingsLabel(title) }
                }
            }
        }
    }
}

@Composable
private fun ValueRow(label: String, value: Int, highlight: Boolean = false) {
    Row(SettingsRowPadding.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SettingsLabel(label, Modifier.weight(1f), color = if (highlight) OttershelfTheme.colors.warning else OttershelfTheme.colors.foreground)
        SettingsValue(value.toString())
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, icon: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).then(SettingsRowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LucideIcon(icon, contentDescription = null, tint = OttershelfTheme.colors.mutedForeground, size = 18.dp)
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = TextStyle(fontFamily = OttershelfFonts.Sans, fontSize = 14.sp, lineHeight = 19.sp),
            color = OttershelfTheme.colors.foreground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun IconBox(icon: String, danger: Boolean = false) {
    val accent = if (danger) OttershelfTheme.colors.destructive else OttershelfTheme.colors.primary
    Box(Modifier.size(32.dp).background(accent.atAlpha(0.15f), OttershelfTheme.radii.lgShape), contentAlignment = Alignment.Center) {
        LucideIcon(icon, contentDescription = null, tint = accent, size = 16.dp)
    }
}

@Composable
private fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = TextStyle(fontFamily = OttershelfFonts.Sans, fontSize = 13.sp, lineHeight = 19.sp),
        color = OttershelfTheme.colors.mutedForeground,
    )
}
