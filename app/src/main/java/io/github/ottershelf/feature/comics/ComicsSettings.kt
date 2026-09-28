package io.github.ottershelf.feature.comics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.core.readerprefs.CbxReaderSettings
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme

/** What the settings sheet can do. */
class ComicsSettingsActions(
    val onChange: ((CbxReaderSettings) -> CbxReaderSettings) -> Unit = {},
    val onReset: () -> Unit = {},
    val onUseAsDefault: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicsSettingsSheet(settings: CbxReaderSettings, customized: Boolean, actions: ComicsSettingsActions, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OttershelfTheme.colors.card,
    ) {
        ComicsSettingsContent(settings, customized, actions, Modifier.verticalScroll(rememberScrollState()))
    }
}

/**
 * The web's CBZ settings panel (CbzSettingsPanel.vue): fit, scroll mode, direction, page view and
 * the spread options, background, auto-advance. Changes apply at once and belong to this comic (the
 * web's per-book settings, Reset drops them); "Use for all comics" makes them the account's default
 * for comics.
 */
@Composable
fun ComicsSettingsContent(
    settings: CbxReaderSettings,
    customized: Boolean,
    actions: ComicsSettingsActions,
    modifier: Modifier = Modifier,
) {
    val colors = OttershelfTheme.colors
    val change = actions.onChange
    val paged = settings.scrollMode == ComicsUiState.SCROLL_PAGED
    Column(modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.comics_settings),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                    color = colors.foreground,
                )
                if (customized) {
                    Text(
                        stringResource(R.string.comics_customized),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = colors.mutedForeground,
                    )
                }
            }
            TextButton(onClick = actions.onReset, enabled = customized) {
                LucideIcon("RotateCcw", contentDescription = null, size = 16.dp, tint = if (customized) colors.primary else colors.mutedForeground)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.comics_reset), color = if (customized) colors.primary else colors.mutedForeground)
            }
        }

        Block(stringResource(R.string.comics_fit_mode)) {
            Segmented(
                options = listOf(R.string.comics_fit_page, R.string.comics_fit_width, R.string.comics_fit_height, R.string.comics_fit_actual).map { stringResource(it) },
                selected = FIT_IDS.indexOf(settings.fitMode).coerceAtLeast(0),
            ) { i -> change { it.copy(fitMode = FIT_IDS[i]) } }
        }
        Block(stringResource(R.string.comics_scroll_mode), hint = stringResource(R.string.comics_scroll_hint)) {
            Segmented(
                options = listOf(R.string.comics_scroll_paged, R.string.comics_scroll_infinite, R.string.comics_scroll_no_gaps).map { stringResource(it) },
                selected = SCROLL_IDS.indexOf(settings.scrollMode).coerceAtLeast(0),
            ) { i -> change { it.copy(scrollMode = SCROLL_IDS[i]) } }
        }
        Block(stringResource(R.string.comics_direction)) {
            Segmented(
                options = listOf(stringResource(R.string.comics_direction_ltr), stringResource(R.string.comics_direction_rtl)),
                selected = if (settings.direction == ComicsUiState.DIRECTION_RTL) 1 else 0,
            ) { i -> change { it.copy(direction = if (i == 1) ComicsUiState.DIRECTION_RTL else "ltr") } }
        }
        Block(
            stringResource(R.string.comics_page_view),
            hint = stringResource(if (paged) R.string.comics_two_page_hint else R.string.comics_two_page_paged_only),
        ) {
            Segmented(
                options = listOf(stringResource(R.string.comics_view_single), stringResource(R.string.comics_view_two_page)),
                selected = if (settings.viewMode == "two-page") 1 else 0,
                enabled = paged,
            ) { i -> change { it.copy(viewMode = if (i == 1) "two-page" else "single") } }
        }
        if (settings.viewMode == "two-page" && paged) {
            Block(stringResource(R.string.comics_spread_alignment)) {
                Segmented(
                    options = listOf(stringResource(R.string.comics_spread_normal), stringResource(R.string.comics_spread_shifted)),
                    selected = if (settings.spreadAlignment == "shifted") 1 else 0,
                ) { i -> change { it.copy(spreadAlignment = if (i == 1) "shifted" else "normal") } }
            }
            Block(stringResource(R.string.comics_wide_pages)) {
                Segmented(
                    options = listOf(stringResource(R.string.comics_wide_auto), stringResource(R.string.comics_wide_in_spreads)),
                    selected = if (settings.widePageSingletonMode == "disable") 1 else 0,
                ) { i -> change { it.copy(widePageSingletonMode = if (i == 1) "disable" else "auto") } }
            }
            InlineRow(stringResource(R.string.comics_spread_gap)) {
                Stepper(
                    value = stringResource(R.string.comics_spread_gap_value, settings.spreadGap),
                    canDecrease = settings.spreadGap > 0,
                    canIncrease = settings.spreadGap < MAX_GAP,
                    onDecrease = { change { it.copy(spreadGap = (it.spreadGap - GAP_STEP).coerceAtLeast(0)) } },
                    onIncrease = { change { it.copy(spreadGap = (it.spreadGap + GAP_STEP).coerceAtMost(MAX_GAP)) } },
                )
            }
            InlineRow(stringResource(R.string.comics_force_two_page)) {
                Switch(checked = settings.forceTwoPage, onCheckedChange = { v -> change { it.copy(forceTwoPage = v) } })
            }
        }
        Block(stringResource(R.string.comics_background)) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                BG_IDS.forEachIndexed { i, id ->
                    Swatch(
                        color = comicBackground(id),
                        label = stringResource(BG_LABELS[i]),
                        selected = settings.bgColor == id,
                    ) { change { it.copy(bgColor = id) } }
                }
            }
        }
        InlineRow(
            stringResource(R.string.comics_auto_advance),
            hint = stringResource(if (paged) R.string.comics_auto_advance_hint else R.string.comics_auto_advance_paged_only),
        ) {
            Switch(checked = settings.autoAdvance, onCheckedChange = { v -> change { it.copy(autoAdvance = v) } })
        }

        HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), thickness = 1.dp, color = colors.border)
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                stringResource(R.string.comics_default_note),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = colors.mutedForeground,
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton(text = stringResource(R.string.comics_use_as_default), onClick = actions.onUseAsDefault, icon = "Copy")
        }
    }
}

private val FIT_IDS = listOf(Fit.PAGE, Fit.WIDTH, Fit.HEIGHT, Fit.ACTUAL)
private val SCROLL_IDS = listOf(ComicsUiState.SCROLL_PAGED, ComicsUiState.SCROLL_INFINITE, ComicsUiState.SCROLL_STRIP)
private val BG_IDS = listOf("black", "gray", "white")
private val BG_LABELS = listOf(R.string.comics_bg_black, R.string.comics_bg_gray, R.string.comics_bg_white)
private const val GAP_STEP = 4
private const val MAX_GAP = 64

/** A label (and a dim hint) over a full-width control. */
@Composable
private fun Block(label: String, hint: String? = null, control: @Composable () -> Unit) {
    val colors = OttershelfTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), color = colors.foreground)
        Spacer(Modifier.height(8.dp))
        control()
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = colors.mutedForeground)
        }
    }
}

/** A label beside its control (switches, the stepper). */
@Composable
private fun InlineRow(label: String, hint: String? = null, control: @Composable () -> Unit) {
    val colors = OttershelfTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), color = colors.foreground)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = colors.mutedForeground)
            }
        }
        control()
    }
}

/** The web's segmented tabs across the width: a muted track, the selected option raised on the card colour. */
@Composable
private fun Segmented(options: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    val colors = OttershelfTheme.colors
    val radii = OttershelfTheme.radii
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radii.md))
            .background(colors.muted)
            .padding(3.dp),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(radii.sm))
                    .background(if (isSelected) colors.card else Color.Transparent)
                    .clickable(enabled = enabled, role = Role.RadioButton, onClick = { onSelect(index) })
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    color = when {
                        !enabled -> colors.mutedForeground.copy(alpha = 0.5f)
                        isSelected -> colors.foreground
                        else -> colors.mutedForeground
                    },
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** A colour disc with its name; the selected one ringed in the accent. */
@Composable
private fun Swatch(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    Column(
        Modifier
            .clip(RoundedCornerShape(OttershelfTheme.radii.md))
            .clickable(role = Role.RadioButton, onClickLabel = label, onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.border, CircleShape)
                .padding(if (selected) 4.dp else 1.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = if (selected) colors.primary else colors.mutedForeground,
            maxLines = 1,
        )
    }
}

/** − value +, as bordered square buttons (the EPUB reader's stepper). */
@Composable
private fun Stepper(value: String, canDecrease: Boolean, canIncrease: Boolean, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(stringResource(R.string.comics_less), canDecrease, onDecrease) {
            LucideIcon("Minus", contentDescription = null, size = 18.dp)
        }
        Text(
            value,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = OttershelfTheme.colors.foreground,
        )
        StepButton(stringResource(R.string.comics_more), canIncrease, onIncrease) {
            LucideIcon("Plus", contentDescription = null, size = 18.dp)
        }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val colors = OttershelfTheme.colors
    Surface(
        color = colors.card,
        contentColor = if (enabled) colors.foreground else colors.mutedForeground.copy(alpha = 0.5f),
        shape = RoundedCornerShape(OttershelfTheme.radii.md),
        border = BorderStroke(1.dp, colors.border),
    ) {
        Box(
            Modifier.size(40.dp).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}
