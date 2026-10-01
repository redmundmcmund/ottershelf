package io.github.ottershelf.feature.timer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.core.settings.ProgressUnit
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.theme.OttershelfTheme
import io.github.ottershelf.ui.components.belowStatusBar

/** The save form in a bottom sheet (the timer screen's Save). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SaveSessionSheet(
    form: SaveForm,
    saving: Boolean,
    error: String?,
    onSave: (ProgressEntry, ProgressUnit) -> Unit,
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
) {
    ModalBottomSheet(
        modifier = Modifier.belowStatusBar(),
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OttershelfTheme.colors.card,
    ) {
        SaveSessionForm(
            form = form,
            saving = saving,
            error = error,
            onSave = onSave,
            onCancel = onDismiss,
            onDiscard = onDiscard,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        )
    }
}

/**
 * Where did the user get to? The page reached with the total of the user's edition (shown and editable), or a
 * percentage; empty saves the time only. Under 10 s there is nothing to save: Discard instead.
 */
@Composable
internal fun SaveSessionForm(
    form: SaveForm,
    saving: Boolean,
    error: String?,
    onSave: (ProgressEntry, ProgressUnit) -> Unit,
    onCancel: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
    cancelLabel: String = stringResource(R.string.timer_cancel),
) {
    val colors = OttershelfTheme.colors
    var unit by rememberSaveable { mutableStateOf(form.unit) }
    var pageText by rememberSaveable { mutableStateOf(form.startPage?.toString().orEmpty()) }
    var totalText by rememberSaveable { mutableStateOf(form.total?.toString().orEmpty()) }
    var percentText by rememberSaveable { mutableStateOf(ProgressInput.percentText(form.startPercent)) }
    val (entry, problem) = ProgressInput.parse(unit, pageText, totalText, percentText)

    Column(modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.timer_save_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = colors.foreground,
        )
        Spacer(Modifier.height(2.dp))
        val read = stringResource(R.string.timer_save_read, durationText(form.activeMs / 1000))
        Text(
            listOfNotNull(read, form.title?.ifBlank { null }).joinToString("  ·  "),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.mutedForeground,
            maxLines = 1,
        )
        Spacer(Modifier.height(18.dp))

        if (form.tooShort) {
            Text(stringResource(R.string.timer_too_short), style = MaterialTheme.typography.bodyLarge, color = colors.foreground)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(stringResource(R.string.timer_keep_reading), onClick = onCancel, modifier = Modifier.weight(1f))
                AccentButton(stringResource(R.string.timer_cancel_confirm), onClick = onDiscard, icon = "Trash", modifier = Modifier.weight(1f))
            }
        } else {
            Segmented(
                options = listOf(stringResource(R.string.timer_unit_pages), stringResource(R.string.timer_unit_percent)),
                selected = if (unit == ProgressUnit.PAGE) 0 else 1,
                onSelect = { unit = if (it == 0) ProgressUnit.PAGE else ProgressUnit.PERCENT },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            val fieldShape = RoundedCornerShape(OttershelfTheme.radii.md)
            val bigText = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, fontFeatureSettings = "tnum")
            when (unit) {
                ProgressUnit.PAGE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = pageText,
                            onValueChange = { pageText = it.filter(Char::isDigit).take(6) },
                            label = { Text(stringResource(R.string.timer_page_reached)) },
                            singleLine = true,
                            enabled = !saving,
                            shape = fieldShape,
                            textStyle = bigText,
                            isError = problem == EntryError.PageTooHigh,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.timer_of),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.mutedForeground,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        OutlinedTextField(
                            value = totalText,
                            onValueChange = { totalText = it.filter(Char::isDigit).take(6) },
                            label = { Text(stringResource(R.string.timer_total_pages)) },
                            singleLine = true,
                            enabled = !saving,
                            shape = fieldShape,
                            textStyle = bigText,
                            isError = problem == EntryError.NeedTotal,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            modifier = Modifier.width(128.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        form.startPage?.let { stringResource(R.string.timer_started_page, it) } ?: stringResource(R.string.timer_no_page_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.mutedForeground,
                    )
                }
                ProgressUnit.PERCENT -> {
                    OutlinedTextField(
                        value = percentText,
                        onValueChange = { text -> percentText = text.filter { it.isDigit() || it == '.' || it == ',' }.take(6) },
                        label = { Text(stringResource(R.string.timer_percent_reached)) },
                        suffix = { Text("%") },
                        singleLine = true,
                        enabled = !saving,
                        shape = fieldShape,
                        textStyle = bigText,
                        isError = problem != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        form.startPercent?.let { stringResource(R.string.timer_started_percent, ProgressInput.percentText(it)) }
                            ?: stringResource(R.string.timer_no_page_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.mutedForeground,
                    )
                }
            }

            val message = when (problem) {
                EntryError.NotANumber -> stringResource(R.string.timer_error_number)
                EntryError.NeedTotal -> stringResource(R.string.timer_error_total)
                EntryError.PageTooHigh -> stringResource(R.string.timer_error_page_high)
                EntryError.PercentRange -> stringResource(R.string.timer_error_percent)
                null -> error?.let { stringResource(R.string.timer_save_failed, it) }
            }
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(message, style = MaterialTheme.typography.bodySmall, color = colors.destructive)
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(cancelLabel, onClick = onCancel, enabled = !saving, modifier = Modifier.weight(1f))
                AccentButton(
                    stringResource(if (saving) R.string.timer_saving else R.string.timer_save),
                    onClick = { entry?.let { onSave(it, unit) } },
                    icon = "Check",
                    enabled = entry != null && !saving,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
