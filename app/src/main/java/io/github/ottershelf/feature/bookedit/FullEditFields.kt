package io.github.ottershelf.feature.bookedit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ottershelf.R
import io.github.ottershelf.ui.components.CardTitle
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme

/*
 * The full editor's fields (Settings > Full metadata editing, [FullEditing]): the subtitle and the
 * other series sit in the Details card beside the light editor's fields; the description, the
 * publication details, genres and tags, and a comic's details get cards of their own. Each field has
 * its lock (Lock while it is unchanged, Unlock once locked: [FieldLabel]).
 */

/** The subtitle, under the title. */
@Composable
internal fun SubtitleField(state: BookEditUiState, actions: BookEditActions) {
    FieldLabel(stringResource(R.string.bookedit_field_subtitle), state, LockGroup.SUBTITLE, actions, Modifier.padding(top = 14.dp))
    LineField(
        value = state.form.subtitle,
        onValue = { v -> actions.onEdit { it.copy(subtitle = v.replace('\n', ' ').take(EditRules.SUBTITLE_MAX)) } },
        locked = state.isLocked(LockGroup.SUBTITLE),
        capitalization = KeyboardCapitalization.Sentences,
    )
}

/** The book's other series (after its main one), each with its number; X takes one out, + adds one. */
@Composable
internal fun OtherSeriesField(state: BookEditUiState, actions: BookEditActions) {
    val colors = OttershelfTheme.colors
    val locked = state.isLocked(LockGroup.SERIES)
    val others = state.form.otherSeries
    Text(
        stringResource(R.string.bookedit_field_other_series),
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = colors.mutedForeground,
    )
    others.forEachIndexed { i, entry ->
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = entry.name,
                onValueChange = { v -> actions.onEdit { f -> f.copy(otherSeries = f.otherSeries.replaced(i) { it.copy(name = v.take(EditRules.NAME_MAX)) }) } },
                modifier = Modifier.weight(1f),
                enabled = !locked,
                placeholder = { Text(stringResource(R.string.bookedit_series_hint), maxLines = 1) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(OttershelfTheme.radii.md),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = entry.index,
                onValueChange = { v -> actions.onEdit { f -> f.copy(otherSeries = f.otherSeries.replaced(i) { it.copy(index = v.trim().take(EditRules.SERIES_INDEX_MAX)) }) } },
                modifier = Modifier.width(80.dp),
                enabled = !locked,
                placeholder = { Text(stringResource(R.string.bookedit_number_hint), maxLines = 1) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                isError = EditRules.seriesIndexOf(entry.index).let { it.isNotEmpty() && !EditRules.isValidSeriesIndex(it) },
                shape = RoundedCornerShape(OttershelfTheme.radii.md),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            )
            if (!locked) {
                ClearButton(stringResource(R.string.bookedit_remove_series, entry.name)) {
                    actions.onEdit { f -> f.copy(otherSeries = f.otherSeries.filterIndexed { j, _ -> j != i }) }
                }
            }
        }
    }
    if (FormProblem.OTHER_SERIES_INVALID in state.problems) HelperLine(stringResource(R.string.bookedit_index_invalid), error = true)
    if (!locked) {
        AddRow(stringResource(R.string.bookedit_add_series)) { actions.onEdit { it.copy(otherSeries = it.otherSeries + SeriesEntry()) } }
    }
}

/** The description, as plain paragraphs (a blank line between them). */
@Composable
internal fun DescriptionCard(state: BookEditUiState, actions: BookEditActions) {
    val locked = state.isLocked(LockGroup.DESCRIPTION)
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        CardTitle(stringResource(R.string.bookedit_description), icon = "TextAlignStart")
        FieldLabel(stringResource(R.string.bookedit_field_description), state, LockGroup.DESCRIPTION, actions, Modifier.padding(top = 14.dp))
        OutlinedTextField(
            value = state.form.description,
            onValueChange = { v -> actions.onEdit { it.copy(description = v) } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            enabled = !locked,
            textStyle = MaterialTheme.typography.bodyLarge,
            minLines = 5,
            maxLines = 16,
            shape = RoundedCornerShape(OttershelfTheme.radii.md),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
    }
}

/** Publisher, published, language, pages and the ISBNs. */
@Composable
internal fun PublicationCard(state: BookEditUiState, actions: BookEditActions) {
    val form = state.form
    val problems = state.problems
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        CardTitle(stringResource(R.string.bookedit_publication), icon = "BookOpen")

        FieldLabel(stringResource(R.string.bookedit_field_publisher), state, LockGroup.PUBLISHER, actions, Modifier.padding(top = 14.dp))
        LineField(form.publisher, { v -> actions.onEdit { it.copy(publisher = v.take(EditRules.NAME_MAX)) } }, state.isLocked(LockGroup.PUBLISHER), KeyboardCapitalization.Words)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                FieldLabel(stringResource(R.string.bookedit_field_published), state, LockGroup.PUBLISHED, actions, Modifier.padding(top = 14.dp))
                LineField(
                    form.published,
                    { v -> actions.onEdit { it.copy(published = v.trim().take(10)) } },
                    state.isLocked(LockGroup.PUBLISHED),
                    placeholder = stringResource(R.string.bookedit_published_hint),
                    keyboardType = KeyboardType.Number,
                    error = FormProblem.PUBLISHED_INVALID in problems,
                )
            }
            Column(Modifier.weight(1f)) {
                FieldLabel(stringResource(R.string.bookedit_field_pages), state, LockGroup.PAGES, actions, Modifier.padding(top = 14.dp))
                LineField(
                    form.pages,
                    { v -> actions.onEdit { it.copy(pages = v.filter(Char::isDigit).take(6)) } },
                    state.isLocked(LockGroup.PAGES),
                    keyboardType = KeyboardType.Number,
                    error = FormProblem.PAGES_INVALID in problems,
                )
            }
        }
        if (FormProblem.PUBLISHED_INVALID in problems) HelperLine(stringResource(R.string.bookedit_published_invalid), error = true)
        if (FormProblem.PAGES_INVALID in problems) HelperLine(stringResource(R.string.bookedit_pages_invalid), error = true)

        FieldLabel(stringResource(R.string.bookedit_field_language), state, LockGroup.LANGUAGE, actions, Modifier.padding(top = 14.dp))
        LineField(
            form.language,
            { v -> actions.onEdit { it.copy(language = v.take(EditRules.LANGUAGE_MAX)) } },
            state.isLocked(LockGroup.LANGUAGE),
            placeholder = stringResource(R.string.bookedit_language_hint),
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1.3f)) {
                FieldLabel(stringResource(R.string.bookedit_field_isbn13), state, LockGroup.ISBN13, actions, Modifier.padding(top = 14.dp))
                LineField(
                    form.isbn13,
                    { v -> actions.onEdit { it.copy(isbn13 = v.take(20)) } },
                    state.isLocked(LockGroup.ISBN13),
                    keyboardType = KeyboardType.Number,
                    error = FormProblem.ISBN13_INVALID in problems,
                )
            }
            Column(Modifier.weight(1f)) {
                FieldLabel(stringResource(R.string.bookedit_field_isbn10), state, LockGroup.ISBN10, actions, Modifier.padding(top = 14.dp))
                LineField(
                    form.isbn10,
                    { v -> actions.onEdit { it.copy(isbn10 = v.take(16)) } },
                    state.isLocked(LockGroup.ISBN10),
                    capitalization = KeyboardCapitalization.Characters,
                    error = FormProblem.ISBN10_INVALID in problems,
                )
            }
        }
        if (FormProblem.ISBN13_INVALID in problems || FormProblem.ISBN10_INVALID in problems) {
            HelperLine(stringResource(R.string.bookedit_isbn_invalid), error = true)
        }
    }
}

/** Genres and tags, as chips. */
@Composable
internal fun GenresTagsCard(state: BookEditUiState, actions: BookEditActions) {
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        CardTitle(stringResource(R.string.bookedit_genres_tags), icon = "Tags")
        NamesField(stringResource(R.string.bookedit_field_genres), state.form.genres, state, LockGroup.GENRES, actions) { f, names -> f.copy(genres = names) }
        NamesField(stringResource(R.string.bookedit_field_tags), state.form.tags, state, LockGroup.TAGS, actions) { f, names -> f.copy(tags = names) }
    }
}

/** A comic's issue, volume and credits. */
@Composable
internal fun ComicCard(state: BookEditUiState, actions: BookEditActions) {
    val comic = state.form.comic
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        CardTitle(stringResource(R.string.bookedit_comic), icon = "BookImage")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                FieldLabel(stringResource(R.string.bookedit_field_issue), state, LockGroup.COMIC_ISSUE, actions, Modifier.padding(top = 14.dp))
                LineField(comic.issueNumber, { v -> actions.onEdit { it.copy(comic = it.comic.copy(issueNumber = v.take(EditRules.ISSUE_MAX))) } }, state.isLocked(LockGroup.COMIC_ISSUE))
            }
            Column(Modifier.weight(2f)) {
                FieldLabel(stringResource(R.string.bookedit_field_volume), state, LockGroup.COMIC_VOLUME, actions, Modifier.padding(top = 14.dp))
                LineField(
                    comic.volumeName,
                    { v -> actions.onEdit { it.copy(comic = it.comic.copy(volumeName = v.take(EditRules.NAME_MAX))) } },
                    state.isLocked(LockGroup.COMIC_VOLUME),
                    KeyboardCapitalization.Words,
                )
            }
        }
        ComicList.entries.forEach { list ->
            NamesField(stringResource(comicLabel(list)), comic.list(list), state, LockGroup.of(list), actions) { f, names ->
                f.copy(comic = f.comic.withList(list, names))
            }
        }
    }
}

private fun comicLabel(list: ComicList): Int = when (list) {
    ComicList.STORY_ARCS -> R.string.bookedit_field_story_arcs
    ComicList.PENCILLERS -> R.string.bookedit_field_pencillers
    ComicList.INKERS -> R.string.bookedit_field_inkers
    ComicList.COLORISTS -> R.string.bookedit_field_colorists
    ComicList.LETTERERS -> R.string.bookedit_field_letterers
    ComicList.COVER_ARTISTS -> R.string.bookedit_field_cover_artists
    ComicList.CHARACTERS -> R.string.bookedit_field_characters
    ComicList.TEAMS -> R.string.bookedit_field_teams
    ComicList.LOCATIONS -> R.string.bookedit_field_locations
}

/**
 * A list of names as chips (removable), with a box below that adds what's typed on Done or at a
 * comma (a pasted "A, B; C" becomes three), as the authors' box does.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NamesField(
    label: String,
    names: List<String>,
    state: BookEditUiState,
    group: LockGroup,
    actions: BookEditActions,
    set: (EditForm, List<String>) -> EditForm,
) {
    val colors = OttershelfTheme.colors
    val locked = state.isLocked(group)
    var typed by rememberSaveable(label) { mutableStateOf("") }
    FieldLabel(label, state, group, actions, Modifier.padding(top = 14.dp))
    if (names.isNotEmpty()) {
        FlowRow(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            names.forEachIndexed { i, name ->
                AuthorChip(name, removable = !locked) { actions.onEdit { f -> set(f, names.filterIndexed { j, _ -> j != i }) } }
            }
        }
    }
    if (!locked) {
        OutlinedTextField(
            value = typed,
            onValueChange = { text ->
                val (added, rest) = EditRules.typeAuthor(names, text)
                if (added != names) actions.onEdit { f -> set(f, added) }
                typed = rest
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.bookedit_add_name_hint)) },
            leadingIcon = { LucideIcon("Plus", contentDescription = null, tint = colors.mutedForeground, size = 18.dp) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(OttershelfTheme.radii.md),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (typed.isNotBlank()) {
                    actions.onEdit { f -> set(f, EditRules.addName(names, typed)) }
                    typed = ""
                } else {
                    defaultKeyboardAction(ImeAction.Done)
                }
            }),
        )
    }
}

/** A one-line box; locked: disabled with a lock at its end. */
@Composable
private fun LineField(
    value: String,
    onValue: (String) -> Unit,
    locked: Boolean,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    error: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth(),
        enabled = !locked,
        placeholder = placeholder?.let { { Text(it, maxLines = 1) } },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        isError = error,
        trailingIcon = if (locked) ({ LucideIcon("Lock", contentDescription = null, tint = OttershelfTheme.colors.mutedForeground, size = 16.dp) }) else null,
        shape = RoundedCornerShape(OttershelfTheme.radii.md),
        keyboardOptions = KeyboardOptions(capitalization = capitalization, keyboardType = keyboardType, imeAction = ImeAction.Next),
    )
}

/** "+ Add another series": an accent text row. */
@Composable
private fun AddRow(text: String, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(OttershelfTheme.radii.sm))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon("Plus", contentDescription = null, tint = colors.primary, size = 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = colors.primary)
    }
}

private fun <T> List<T>.replaced(index: Int, change: (T) -> T): List<T> = mapIndexed { i, t -> if (i == index) change(t) else t }
