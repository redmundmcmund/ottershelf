package io.github.ottershelf.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(prefs: ReaderPrefs, onChange: (ReaderPrefs) -> Unit, onDismiss: () -> Unit, fixedLayout: Boolean = false) {
    val window = LocalWindowInfo.current
    val density = LocalDensity.current
    val widthDp = with(density) { window.containerSize.width.toDp().value.roundToInt() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OttershelfTheme.colors.card,
    ) {
        ReaderSettingsContent(prefs, onChange, Modifier.verticalScroll(rememberScrollState()), fixedLayout, widthDp)
    }
}

/**
 * The reading settings (applied to the page as they change), in sections: the page colour, then
 * Text, Layout and Spacing. A fixed-layout book has only its page colour and spreads (its text is
 * laid out by the publisher). [pageWidthDp]: this screen's width, to say when the page width can't
 * show here.
 */
@Composable
fun ReaderSettingsContent(
    prefs: ReaderPrefs,
    onChange: (ReaderPrefs) -> Unit,
    modifier: Modifier = Modifier,
    fixedLayout: Boolean = false,
    pageWidthDp: Int? = null,
) {
    var picking by rememberSaveable { mutableStateOf<ColorTarget?>(null) }
    val colors = OttershelfTheme.colors
    Column(modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        SheetTitle(stringResource(R.string.reader_settings))
        SectionLabel(stringResource(R.string.reader_section_page), first = true)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ReaderTheme.entries.forEach { theme ->
                ThemeSwatch(theme, prefs, selected = ReaderTheme.of(prefs.theme) == theme) { onChange(prefs.copy(theme = theme.id)) }
            }
        }
        if (ReaderTheme.of(prefs.theme) == ReaderTheme.CUSTOM) {
            val bg = PageColor.parse(prefs.customBg) ?: colors.background
            val fg = PageColor.parse(prefs.customFg) ?: colors.foreground
            ColorRow(stringResource(R.string.reader_custom_background), bg) { picking = ColorTarget.BACKGROUND }
            ColorRow(stringResource(R.string.reader_custom_text), fg) { picking = ColorTarget.TEXT }
            ContrastNote(PageColor.contrast(fg, bg))
        }

        if (fixedLayout) {
            Section(stringResource(R.string.reader_layout)) {
                SettingRow(stringResource(R.string.reader_page_spreads)) {
                    Segmented(
                        listOf(stringResource(R.string.reader_spread_auto), stringResource(R.string.reader_spread_none)),
                        if (prefs.fixedLayoutSpread == ReaderPrefs.SPREAD_NONE) 1 else 0,
                    ) { onChange(prefs.copy(fixedLayoutSpread = if (it == 1) ReaderPrefs.SPREAD_NONE else ReaderPrefs.SPREAD_AUTO)) }
                }
                Hint(stringResource(R.string.reader_page_spreads_hint))
            }
        } else {
            TextSection(prefs, onChange)
            LayoutSection(prefs, onChange, pageWidthDp)
            SpacingSection(prefs, onChange)
        }
    }
    picking?.let { target ->
        PageColorDialog(
            target = target,
            background = PageColor.parse(prefs.customBg) ?: colors.background,
            foreground = PageColor.parse(prefs.customFg) ?: colors.foreground,
            onPick = { hex ->
                picking = null
                onChange(if (target == ColorTarget.BACKGROUND) prefs.copy(customBg = hex) else prefs.copy(customFg = hex))
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun TextSection(prefs: ReaderPrefs, onChange: (ReaderPrefs) -> Unit) {
    Section(stringResource(R.string.reader_section_text)) {
        SettingRow(stringResource(R.string.reader_text_size)) {
            Stepper(
                value = stringResource(R.string.reader_text_size_value, prefs.fontSize),
                decreaseLabel = stringResource(R.string.reader_smaller),
                increaseLabel = stringResource(R.string.reader_larger),
                decreaseText = "A",
                increaseText = "A",
                canDecrease = prefs.fontSize > ReaderPrefs.MIN_FONT_SIZE,
                canIncrease = prefs.fontSize < ReaderPrefs.MAX_FONT_SIZE,
                onDecrease = { onChange(prefs.copy(fontSize = prefs.fontSize - 1)) },
                onIncrease = { onChange(prefs.copy(fontSize = prefs.fontSize + 1)) },
            )
        }
        SettingRow(stringResource(R.string.reader_font)) {
            val labels = listOf(R.string.reader_font_publisher, R.string.reader_font_serif, R.string.reader_font_sans)
            Segmented(labels.map { stringResource(it) }, ReaderPrefs.FONTS.indexOf(prefs.font).coerceAtLeast(0)) {
                onChange(prefs.copy(font = ReaderPrefs.FONTS[it]))
            }
        }
        SwitchRow(stringResource(R.string.reader_justify), prefs.justify) { onChange(prefs.copy(justify = it)) }
        SwitchRow(stringResource(R.string.reader_hyphenate), prefs.hyphenate) { onChange(prefs.copy(hyphenate = it)) }
    }
}

@Composable
private fun LayoutSection(prefs: ReaderPrefs, onChange: (ReaderPrefs) -> Unit, pageWidthDp: Int?) {
    Section(stringResource(R.string.reader_layout)) {
        SettingRow(stringResource(R.string.reader_reading_flow)) {
            Segmented(
                listOf(stringResource(R.string.reader_flow_paginated), stringResource(R.string.reader_flow_scrolled)),
                if (prefs.scrolled) 1 else 0,
            ) { onChange(prefs.copy(flow = if (it == 1) ReaderPrefs.FLOW_SCROLLED else ReaderPrefs.FLOW_PAGINATED)) }
        }
        SwitchRow(stringResource(R.string.reader_animated), prefs.animated, enabled = !prefs.scrolled) {
            onChange(prefs.copy(animated = it))
        }
        SettingRow(stringResource(R.string.reader_margins)) {
            val labels = listOf(R.string.reader_margin_narrow, R.string.reader_margin_normal, R.string.reader_margin_wide)
            val selected = ReaderPrefs.MARGINS.indices.minBy { abs(ReaderPrefs.MARGINS[it] - prefs.margin) }
            Segmented(labels.map { stringResource(it) }, selected) { onChange(prefs.copy(margin = ReaderPrefs.MARGINS[it])) }
        }
        SettingRow(stringResource(R.string.reader_page_width)) {
            val labels = listOf(R.string.reader_width_narrow, R.string.reader_width_medium, R.string.reader_width_wide, R.string.reader_width_full)
            Segmented(labels.map { stringResource(it) }, ReaderPrefs.pageWidthIndex(prefs.maxInlineSize)) {
                onChange(prefs.copy(maxInlineSize = ReaderPrefs.PAGE_WIDTHS[it]))
            }
        }
        // Narrower than the narrowest page width: every choice fills the screen here.
        if (pageWidthDp != null && pageWidthDp < ReaderPrefs.PAGE_WIDTHS.first()) Hint(stringResource(R.string.reader_page_width_hint))
    }
}

@Composable
private fun SpacingSection(prefs: ReaderPrefs, onChange: (ReaderPrefs) -> Unit) {
    Section(stringResource(R.string.reader_section_spacing)) {
        SettingRow(stringResource(R.string.reader_line_spacing)) {
            Stepper(
                value = String.format(Locale.getDefault(), "%.1f", prefs.lineHeight),
                decreaseLabel = stringResource(R.string.reader_less),
                increaseLabel = stringResource(R.string.reader_more),
                decreaseIcon = "Minus",
                increaseIcon = "Plus",
                canDecrease = prefs.lineHeight > ReaderPrefs.MIN_LINE_HEIGHT + 0.01,
                canIncrease = prefs.lineHeight < ReaderPrefs.MAX_LINE_HEIGHT - 0.01,
                onDecrease = { onChange(prefs.copy(lineHeight = ((prefs.lineHeight - 0.1) * 10).roundToInt() / 10.0)) },
                onIncrease = { onChange(prefs.copy(lineHeight = ((prefs.lineHeight + 0.1) * 10).roundToInt() / 10.0)) },
            )
        }
        EmStepper(
            stringResource(R.string.reader_paragraph_spacing),
            SpacingSteps.Spec(ReaderPrefs.PARAGRAPH_SPACING_STEP, ReaderPrefs.PARAGRAPH_SPACING_MAX, zeroIsBook = true),
            prefs.paragraphSpacing,
        ) { onChange(prefs.copy(paragraphSpacing = it ?: 0.0)) }
        EmStepper(
            stringResource(R.string.reader_letter_spacing),
            SpacingSteps.Spec(ReaderPrefs.LETTER_SPACING_STEP, ReaderPrefs.LETTER_SPACING_MAX),
            prefs.letterSpacing,
        ) { onChange(prefs.copy(letterSpacing = it)) }
        EmStepper(
            stringResource(R.string.reader_word_spacing),
            SpacingSteps.Spec(ReaderPrefs.WORD_SPACING_STEP, ReaderPrefs.WORD_SPACING_MAX),
            prefs.wordSpacing,
        ) { onChange(prefs.copy(wordSpacing = it)) }
        EmStepper(
            stringResource(R.string.reader_text_indent),
            SpacingSteps.Spec(ReaderPrefs.TEXT_INDENT_STEP, ReaderPrefs.TEXT_INDENT_MAX),
            prefs.textIndent,
        ) { onChange(prefs.copy(textIndent = it)) }
    }
}

/**
 * The spacing steppers' values: "Book" (the book's own: null, or 0 for paragraph spacing, as the
 * web stores them), then the web's steps up to its maximum. Pure; `ReaderStylesTest`.
 */
internal object SpacingSteps {
    /** [zeroIsBook]: 0 means the book's own (paragraph spacing) rather than null. */
    data class Spec(val step: Double, val max: Double, val zeroIsBook: Boolean = false)

    fun isBook(value: Double?, spec: Spec): Boolean = value == null || (spec.zeroIsBook && value <= 0.0)

    fun canDecrease(value: Double?, spec: Spec): Boolean = !isBook(value, spec)

    fun canIncrease(value: Double?, spec: Spec): Boolean = isBook(value, spec) || value!! < spec.max - spec.step / 2

    /** From Book to the first step: 0 em (letter, word, indent) or one step (paragraph spacing). */
    fun up(value: Double?, spec: Spec): Double? {
        if (isBook(value, spec)) return if (spec.zeroIsBook) spec.step else 0.0
        return round(minOf(spec.max, value!! + spec.step), spec)
    }

    /** Down one step; below the lowest, back to Book. */
    fun down(value: Double?, spec: Spec): Double? {
        if (isBook(value, spec)) return if (spec.zeroIsBook) 0.0 else null
        val next = value!! - spec.step
        return when {
            spec.zeroIsBook -> round(maxOf(0.0, next), spec)
            next < -spec.step / 2 -> null
            else -> round(maxOf(0.0, next), spec)
        }
    }

    private fun round(v: Double, spec: Spec): Double = ReaderPrefs.step(v, 0.0, spec.max, spec.step) ?: 0.0
}

@Composable
private fun EmStepper(label: String, spec: SpacingSteps.Spec, value: Double?, onChange: (Double?) -> Unit) {
    SettingRow(label) {
        Stepper(
            value = if (SpacingSteps.isBook(value, spec)) stringResource(R.string.reader_spacing_book)
            else stringResource(R.string.reader_em_value, ReaderStyles.number(value!!)),
            decreaseLabel = stringResource(R.string.reader_less),
            increaseLabel = stringResource(R.string.reader_more),
            decreaseIcon = "Minus",
            increaseIcon = "Plus",
            canDecrease = SpacingSteps.canDecrease(value, spec),
            canIncrease = SpacingSteps.canIncrease(value, spec),
            onDecrease = { onChange(SpacingSteps.down(value, spec)) },
            onIncrease = { onChange(SpacingSteps.up(value, spec)) },
        )
    }
}

// --- building blocks (the Nexus reader sheet's rows) --------------------------------------------

/** A section: a hairline, then its label in the web's group-label style, then its rows. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(title)
        content()
    }
}

@Composable
private fun SectionLabel(title: String, first: Boolean = false) {
    val colors = OttershelfTheme.colors
    if (!first) HorizontalDivider(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp), thickness = 1.dp, color = colors.border)
    Text(
        title.uppercase(Locale.getDefault()),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = if (first) 4.dp else 14.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.05.em),
        color = colors.mutedForeground,
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
        color = OttershelfTheme.colors.mutedForeground,
    )
}

@Composable
private fun ThemeSwatch(theme: ReaderTheme, prefs: ReaderPrefs, selected: Boolean, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    val (background, foreground) = when (theme) {
        ReaderTheme.APP -> colors.background to colors.foreground
        ReaderTheme.CUSTOM -> (PageColor.parse(prefs.customBg) ?: colors.background) to (PageColor.parse(prefs.customFg) ?: colors.foreground)
        else -> theme.background!! to theme.foreground!!
    }
    val label = stringResource(
        when (theme) {
            ReaderTheme.APP -> R.string.reader_theme_app
            ReaderTheme.ORIGINAL -> R.string.reader_theme_original
            ReaderTheme.SEPIA -> R.string.reader_theme_sepia
            ReaderTheme.DARK -> R.string.reader_theme_dark
            ReaderTheme.BLACK -> R.string.reader_theme_black
            ReaderTheme.CUSTOM -> R.string.reader_theme_custom
        },
    )
    Column(
        Modifier
            .clip(RoundedCornerShape(OttershelfTheme.radii.md))
            .clickable(role = Role.RadioButton, onClickLabel = label, onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.border, CircleShape)
                .padding(if (selected) 4.dp else 1.dp)
                .background(background, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (theme == ReaderTheme.CUSTOM && !selected) {
                LucideIcon("Palette", contentDescription = null, tint = foreground, size = 20.dp)
            } else {
                Text("Aa", color = foreground, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = if (selected) colors.primary else colors.mutedForeground,
            maxLines = 1,
        )
    }
}

/** A custom page colour: its swatch and hex; a tap opens the picker. */
@Composable
private fun ColorRow(label: String, color: Color, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            color = colors.foreground,
        )
        Box(Modifier.size(26.dp).border(1.dp, colors.border, CircleShape).padding(1.dp).background(color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(
            PageColor.hex(color).uppercase(Locale.ROOT),
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = colors.foreground,
        )
        LucideIcon("ChevronRight", contentDescription = null, tint = colors.mutedForeground, size = 18.dp)
    }
}

/** Below WCAG's 4.5:1 the custom colours get a warning; above it, the ratio quietly. */
@Composable
internal fun ContrastNote(ratio: Double, modifier: Modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 6.dp)) {
    val colors = OttershelfTheme.colors
    val low = ratio < PageColor.MIN_CONTRAST
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LucideIcon(if (low) "TriangleAlert" else "Contrast", contentDescription = null, tint = if (low) colors.warning else colors.mutedForeground, size = 15.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(if (low) R.string.reader_contrast_low else R.string.reader_contrast_ok, PageColor.ratioText(ratio)),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = if (low) colors.warning else colors.mutedForeground,
        )
    }
}

@Composable
private fun SettingRow(label: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            color = OttershelfTheme.colors.foreground,
        )
        control()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SettingRow(label) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** − value +, as bordered square buttons (the web's icon buttons). */
@Composable
private fun Stepper(
    value: String,
    decreaseLabel: String,
    increaseLabel: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseText: String? = null,
    increaseText: String? = null,
    decreaseIcon: String? = null,
    increaseIcon: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(decreaseLabel, canDecrease, onDecrease) {
            if (decreaseIcon != null) LucideIcon(decreaseIcon, contentDescription = null, size = 18.dp)
            else Text(decreaseText.orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            value,
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = OttershelfTheme.colors.foreground,
            maxLines = 1,
        )
        StepButton(increaseLabel, canIncrease, onIncrease) {
            if (increaseIcon != null) LucideIcon(increaseIcon, contentDescription = null, size = 18.dp)
            else Text(increaseText.orEmpty(), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.md)
    Surface(
        color = colors.card,
        contentColor = if (enabled) colors.foreground else colors.mutedForeground.copy(alpha = 0.5f),
        shape = shape,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/** The web's segmented tabs: a muted track, the selected option raised on the card colour. */
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = OttershelfTheme.colors
    val radii = OttershelfTheme.radii
    Row(
        Modifier
            .clip(RoundedCornerShape(radii.md))
            .background(colors.muted)
            .padding(3.dp),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(radii.sm))
                    .background(if (isSelected) colors.card else Color.Transparent)
                    .clickable(role = Role.RadioButton, onClick = { onSelect(index) })
                    .padding(horizontal = if (options.size > 3) 10.dp else 12.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    color = if (isSelected) colors.foreground else colors.mutedForeground,
                    maxLines = 1,
                )
            }
        }
    }
}
