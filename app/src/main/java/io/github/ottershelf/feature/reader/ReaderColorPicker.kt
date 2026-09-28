package io.github.ottershelf.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfFonts
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.util.Locale

/** Which of the custom page colours is being picked. */
enum class ColorTarget { BACKGROUND, TEXT }

/** Paper and ink colours to start from; any other is a drag or a hex code away. */
internal val BACKGROUND_PRESETS = listOf(
    "#ffffff", "#faf7f0", "#f4ecd8", "#eee4c8", "#e8e1d0", "#dfe6d3",
    "#d9e4ec", "#ece3f0", "#3a3a3a", "#2b2b2b", "#1f2a33", "#000000",
)
internal val TEXT_PRESETS = listOf(
    "#000000", "#1b1b1b", "#2f2a24", "#5b4636", "#1f2d3d", "#4a4a4a",
    "#8a8f98", "#b8b8b8", "#d6d0c4", "#e0e0e0", "#f1e8d0", "#ffffff",
)

/**
 * Picks one of the custom page colours: presets, hue / saturation / lightness, or a hex code, with
 * the page previewed in both colours and their contrast. Done hands back `#rrggbb`.
 */
@Composable
fun PageColorDialog(target: ColorTarget, background: Color, foreground: Color, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = OttershelfTheme.colors
    val initial = if (target == ColorTarget.BACKGROUND) background else foreground
    var hex by rememberSaveable(target) { mutableStateOf(PageColor.hex(initial)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.popover,
        title = { Text(stringResource(if (target == ColorTarget.BACKGROUND) R.string.reader_colour_background_title else R.string.reader_colour_text_title)) },
        text = {
            PageColorPicker(
                target = target,
                value = hex,
                other = if (target == ColorTarget.BACKGROUND) foreground else background,
                onChange = { hex = it },
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = { PageColor.normalize(hex)?.let(onPick) ?: onDismiss() }) { Text(stringResource(R.string.reader_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_cancel)) } },
    )
}

/**
 * The picker's body (stateless apart from the hue kept while the colour is grey, so dragging the
 * saturation back up returns to it). [value]: the colour being picked, `#rrggbb`; [other]: the other
 * custom colour, for the preview and the contrast.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PageColorPicker(target: ColorTarget, value: String, other: Color, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val current = PageColor.parse(value) ?: other
    // The sliders keep their own exact values (a colour rounds to 8 bits per channel, which would
    // move the thumbs); they take the colour's again only when it changed elsewhere (a preset, a
    // hex code). A grey keeps the hue it had.
    val start = remember { PageColor.toHsl(current) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var saturation by remember { mutableFloatStateOf(start[1]) }
    var lightness by remember { mutableFloatStateOf(start[2]) }
    var made by remember { mutableStateOf(value) }
    if (value != made) {
        made = value
        val hsl = PageColor.toHsl(current)
        if (hsl[1] > 0.01f) hue = hsl[0]
        saturation = hsl[1]
        lightness = hsl[2]
    }
    val pick: (Float, Float, Float) -> Unit = { h, s, l ->
        hue = h
        saturation = s
        lightness = l
        val hex = PageColor.hex(Color.hsl(h.coerceIn(0f, 359.9f), s.coerceIn(0f, 1f), l.coerceIn(0f, 1f)))
        made = hex
        onChange(hex)
    }
    val (bg, fg) = if (target == ColorTarget.BACKGROUND) current to other else other to current

    Column(modifier.fillMaxWidth()) {
        // The page in both colours.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(OttershelfTheme.radii.md))
                .background(bg)
                .border(1.dp, colors.border, RoundedCornerShape(OttershelfTheme.radii.md))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text("Aa", color = fg, fontFamily = OttershelfFonts.Serif, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.reader_colour_sample), color = fg, fontFamily = OttershelfFonts.Serif, fontSize = 15.sp, lineHeight = 21.sp)
        }
        ContrastNote(PageColor.contrast(fg, bg), Modifier.padding(top = 8.dp, bottom = 4.dp))

        FlowRow(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 6,
        ) {
            val presets = if (target == ColorTarget.BACKGROUND) BACKGROUND_PRESETS else TEXT_PRESETS
            presets.forEach { preset ->
                val color = PageColor.parse(preset)!!
                val selected = PageColor.hex(current) == preset
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        // A ring that shows on the dialog in either theme, so the darkest and lightest swatches don't vanish.
                        .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.mutedForeground.copy(alpha = 0.45f), CircleShape)
                        .padding(if (selected) 3.dp else 1.dp)
                        .background(color, CircleShape)
                        .clickable(role = Role.RadioButton) { onChange(preset) }
                        .semantics { contentDescription = preset.uppercase(Locale.ROOT) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        LucideIcon("Check", contentDescription = null, tint = if (PageColor.isDark(color)) Color.White else Color.Black, size = 16.dp)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        GradientSlider(
            stringResource(R.string.reader_colour_hue),
            hue, 0f..360f,
            Brush.horizontalGradient((0..6).map { Color.hsl(it * 60f % 360f, 0.85f, 0.5f) }),
            current,
        ) { h -> pick(h, saturation, lightness) }
        val h = hue.coerceIn(0f, 359.9f)
        GradientSlider(
            stringResource(R.string.reader_colour_saturation),
            saturation, 0f..1f,
            Brush.horizontalGradient(listOf(Color.hsl(h, 0f, lightness), Color.hsl(h, 1f, lightness))),
            current,
        ) { s -> pick(hue, s, lightness) }
        GradientSlider(
            stringResource(R.string.reader_colour_lightness),
            lightness, 0f..1f,
            Brush.horizontalGradient(listOf(Color.hsl(h, saturation, 0f), Color.hsl(h, saturation, 0.5f), Color.hsl(h, saturation, 1f))),
            current,
        ) { l -> pick(hue, saturation, l) }

        HexField(value, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradientSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    brush: Brush,
    thumbColor: Color,
    onChange: (Float) -> Unit,
) {
    val colors = OttershelfTheme.colors
    Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp), color = colors.mutedForeground)
    Slider(
        value = value.coerceIn(range),
        onValueChange = onChange,
        valueRange = range,
        modifier = Modifier.fillMaxWidth().height(36.dp),
        thumb = {
            Box(
                Modifier
                    .size(22.dp)
                    .border(1.dp, colors.border, CircleShape)
                    .padding(1.dp)
                    .border(3.dp, colors.card, CircleShape)
                    .padding(3.dp)
                    .background(thumbColor, CircleShape),
            )
        },
        track = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(brush)
                    .border(1.dp, colors.border, CircleShape),
            )
        },
    )
}

/** `#` and six hex digits; the colour follows once they make one. */
@Composable
private fun HexField(value: String, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(value.removePrefix("#").uppercase(Locale.ROOT)) }
    // Follow a colour picked elsewhere (a preset, a slider); what the user is typing stays until then.
    var seen by remember { mutableStateOf(value) }
    if (value != seen) {
        seen = value
        if (text.length != 6 || PageColor.normalize(text) != value) text = value.removePrefix("#").uppercase(Locale.ROOT)
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                val clean = input.filter { it.isLetterOrDigit() }.take(6).uppercase(Locale.ROOT)
                text = clean
                if (clean.length == 6) PageColor.normalize(clean)?.let(onChange)
            },
            modifier = Modifier.width(150.dp),
            prefix = { Text("#") },
            label = { Text(stringResource(R.string.reader_colour_hex)) },
            singleLine = true,
            shape = RoundedCornerShape(OttershelfTheme.radii.md),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            isError = text.length == 6 && PageColor.parse(text) == null,
        )
    }
}
