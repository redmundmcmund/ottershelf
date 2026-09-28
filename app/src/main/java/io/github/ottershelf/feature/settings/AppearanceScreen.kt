package io.github.ottershelf.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ottershelf.R
import io.github.ottershelf.core.theme.Accent
import io.github.ottershelf.core.theme.Radius
import io.github.ottershelf.core.theme.ThemeBackground
import io.github.ottershelf.core.theme.ThemeMode
import io.github.ottershelf.core.theme.ThemePrefs
import io.github.ottershelf.ui.components.DetailTopBar
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfFonts
import io.github.ottershelf.ui.theme.OttershelfTheme
import io.github.ottershelf.ui.theme.PatternBackground
import io.github.ottershelf.ui.theme.atAlpha
import io.github.ottershelf.ui.theme.backgroundPattern
import io.github.ottershelf.ui.theme.oklch
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun AppearanceScreen(navigator: AppNavigator) {
    val viewModel = appViewModel { AppearanceViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbar.showSnackbar(resources.getString(message.text))
        }
    }
    AppearanceContent(
        state = state,
        snackbar = snackbar,
        actions = AppearanceActions(
            onBack = { navigator.back() },
            onStorageMode = viewModel::setStorageMode,
            onTheme = viewModel::setTheme,
            onAccent = viewModel::setAccent,
            onRadius = viewModel::setRadius,
            onSurfaceOpacity = viewModel::setSurfaceOpacity,
            onBrightness = viewModel::setBrightness,
            onResetBrightness = viewModel::resetBrightness,
            onBackground = viewModel::setBackground,
        ),
    )
}

/** Everything the Appearance screen can do, so the stateless content takes one parameter. */
data class AppearanceActions(
    val onBack: () -> Unit = {},
    val onStorageMode: (sync: Boolean) -> Unit = {},
    val onTheme: (ThemeMode) -> Unit = {},
    val onAccent: (Accent) -> Unit = {},
    val onRadius: (Radius) -> Unit = {},
    val onSurfaceOpacity: (Int) -> Unit = {},
    val onBrightness: (Int) -> Unit = {},
    val onResetBrightness: () -> Unit = {},
    val onBackground: (ThemeBackground) -> Unit = {},
)

private val AppearanceMessage.text: Int
    get() = when (this) {
        AppearanceMessage.Synced -> R.string.settings_storage_synced
        AppearanceMessage.Local -> R.string.settings_storage_local
        AppearanceMessage.DemoRestricted -> R.string.settings_storage_demo_restricted
        AppearanceMessage.UpdateFailed -> R.string.settings_storage_update_failed
        AppearanceMessage.Error -> R.string.settings_storage_error
    }

/**
 * The web's Display > Theme page (AppearanceThemeSettings.vue) on a phone: where preferences are
 * kept, then the Theme card (colour scheme, the 64 accents in their vivid/pastel rows, corner
 * radius, surface opacity, and surface brightness in dark mode), then the background patterns in
 * the web's four groups. Every change applies at once, so the page itself (drawn on the chosen
 * pattern) is the preview.
 */
@Composable
fun AppearanceContent(
    state: AppearanceUiState,
    actions: AppearanceActions = AppearanceActions(),
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val direction = LocalLayoutDirection.current
    PatternBackground(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { DetailTopBar(title = stringResource(R.string.settings_appearance), onBack = actions.onBack) },
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = Color.Transparent,
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = padding.calculateStartPadding(direction),
                        end = padding.calculateEndPadding(direction),
                        top = padding.calculateTopPadding(),
                        bottom = padding.calculateBottomPadding(),
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = SETTINGS_MAX_WIDTH).fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    StorageSection(state, actions.onStorageMode)
                    ThemeSection(state.prefs, actions)
                    BackgroundSection(state.prefs.background, actions.onBackground)
                }
            }
        }
    }
}

// ---- Where to save (AppearancePreferenceStorage.vue) ----

@Composable
private fun StorageSection(state: AppearanceUiState, onStorageMode: (Boolean) -> Unit) {
    Column {
        SettingsGroupLabel(stringResource(R.string.settings_storage_title))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StorageOption(
                icon = "Monitor",
                title = stringResource(R.string.settings_storage_device_title),
                description = stringResource(R.string.settings_storage_device_description),
                active = !state.sync,
                enabled = !state.switching,
                unavailable = false,
                onClick = { onStorageMode(false) },
            )
            StorageOption(
                icon = "Cloud",
                title = stringResource(R.string.settings_storage_account_title),
                description = stringResource(R.string.settings_storage_account_description),
                active = state.sync,
                enabled = !state.switching,
                unavailable = state.demoRestricted,
                onClick = { onStorageMode(true) },
            )
        }
    }
}

/** A storage mode card: a 2dp accent edge and a faint accent wash when active; dimmed when unavailable. */
@Composable
private fun StorageOption(
    icon: String,
    title: String,
    description: String,
    active: Boolean,
    enabled: Boolean,
    unavailable: Boolean,
    onClick: () -> Unit,
) {
    val colors = OttershelfTheme.colors
    val shape = OttershelfTheme.radii.lgShape
    val highlighted = active && !unavailable
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (unavailable) 0.5f else 1f)
            .clip(shape)
            .background(if (highlighted) colors.primary.atAlpha(0.05f).compositeOver(colors.card) else colors.card)
            .border(2.dp, if (highlighted) colors.primary else colors.border, shape)
            .selectable(selected = active, enabled = enabled && !unavailable, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(32.dp)
                .background(if (highlighted) colors.primary.atAlpha(0.15f) else colors.muted, OttershelfTheme.radii.lgShape),
            contentAlignment = Alignment.Center,
        ) {
            LucideIcon(icon, contentDescription = null, tint = if (highlighted) colors.primary else colors.mutedForeground, size = 16.dp)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsLabel(title, modifier = Modifier.weight(1f, fill = false))
                if (highlighted) StateBadge(stringResource(R.string.settings_storage_active), colors.primary.atAlpha(0.15f), colors.primary)
                if (unavailable) StateBadge(stringResource(R.string.settings_storage_not_available), colors.muted, colors.mutedForeground)
            }
            Text(
                description,
                modifier = Modifier.padding(top = 4.dp),
                style = TextStyle(fontFamily = OttershelfFonts.Sans, fontSize = 12.sp, lineHeight = 19.sp),
                color = colors.mutedForeground,
            )
        }
    }
}

/** "ACTIVE": 12sp semibold capitals on a tinted pill (sm radius). */
@Composable
private fun StateBadge(text: String, container: Color, content: Color) {
    Text(
        text.uppercase(),
        modifier = Modifier
            .background(container, OttershelfTheme.radii.smShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = TextStyle(fontFamily = OttershelfFonts.Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.025.em),
        color = content,
        maxLines = 1,
    )
}

// ---- Theme ----

@Composable
private fun ThemeSection(prefs: ThemePrefs, actions: AppearanceActions) {
    val colors = OttershelfTheme.colors
    Column {
        SettingsGroupLabel(stringResource(R.string.settings_theme_title))
        SettingsCard {
            Column(SettingsRowPadding) {
                SettingsLabel(stringResource(R.string.settings_color_scheme))
                SettingsHint(stringResource(R.string.settings_color_scheme_hint))
                Spacer(Modifier.height(12.dp))
                ThemeModePicker(prefs.theme, actions.onTheme)
            }
            SettingsDivider()
            Column(Modifier.padding(top = 14.dp, bottom = 8.dp)) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SettingsLabel(stringResource(R.string.settings_accent))
                    SettingsHint(stringResource(R.string.settings_accent_hint))
                }
                AccentPicker(prefs.accent, colors.isDark, actions.onAccent)
            }
            SettingsDivider()
            Column(SettingsRowPadding) {
                SettingsLabel(stringResource(R.string.settings_radius))
                SettingsHint(stringResource(R.string.settings_radius_hint))
                Spacer(Modifier.height(12.dp))
                RadiusPicker(prefs.radius, actions.onRadius)
            }
            SettingsDivider()
            Column(SettingsRowPadding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsLabel(stringResource(R.string.settings_surface_opacity), modifier = Modifier.weight(1f))
                    SettingsValue(stringResource(R.string.settings_percent, prefs.surfaceOpacity))
                }
                SettingsHint(stringResource(R.string.settings_surface_opacity_hint))
                PercentSlider(
                    value = prefs.surfaceOpacity,
                    min = ThemePrefs.SURFACE_OPACITY_MIN,
                    max = ThemePrefs.SURFACE_OPACITY_MAX,
                    step = 1,
                    onChange = actions.onSurfaceOpacity,
                )
            }
            if (colors.isDark) {
                SettingsDivider()
                Column(SettingsRowPadding) {
                    Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        SettingsLabel(stringResource(R.string.settings_brightness), modifier = Modifier.weight(1f))
                        SettingsValue(stringResource(R.string.settings_percent, prefs.brightness))
                        if (prefs.brightness > 0) {
                            TextButton(
                                onClick = actions.onResetBrightness,
                                shape = OttershelfTheme.radii.mdShape,
                                modifier = Modifier.padding(start = 4.dp).height(32.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                            ) {
                                Text(stringResource(R.string.settings_brightness_reset), color = colors.foreground, fontSize = 13.sp)
                            }
                        }
                    }
                    SettingsHint(stringResource(R.string.settings_brightness_hint))
                    PercentSlider(
                        value = prefs.brightness,
                        min = ThemePrefs.BRIGHTNESS_MIN,
                        max = ThemePrefs.BRIGHTNESS_MAX,
                        step = 5,
                        onChange = actions.onBrightness,
                    )
                }
            }
        }
    }
}

/** ThemePicker.vue (touch): Light, Dark, System in a muted track; the chosen one raised on the page colour. */
@Composable
private fun ThemeModePicker(selected: ThemeMode, onPick: (ThemeMode) -> Unit) {
    val colors = OttershelfTheme.colors
    val radii = OttershelfTheme.radii
    val options = listOf(ThemeMode.LIGHT to "Sun", ThemeMode.DARK to "Moon", ThemeMode.SYSTEM to "Monitor")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(radii.mdShape)
            .border(1.dp, colors.border, radii.mdShape)
            .background(colors.muted.atAlpha(0.5f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (mode, icon) ->
            val on = mode == selected
            val tint = if (on) colors.foreground else colors.mutedForeground
            Row(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .then(if (on) Modifier.shadow(1.dp, radii.smShape) else Modifier)
                    .clip(radii.smShape)
                    .background(if (on) colors.background else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(mode) }),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LucideIcon(icon, contentDescription = null, tint = tint, size = 14.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    themeModeLabel(mode),
                    style = TextStyle(fontFamily = OttershelfFonts.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Accent pairs per row the picker tries, widest first: the web's 16, else 8, else 4. */
private val ACCENT_COLUMNS = listOf(16, 8, 4)
private const val SWATCH_MIN_DP = 28f
private const val SWATCH_MAX_DP = 40f
private const val SWATCH_MIN_GAP_DP = 8f

/** The gap between swatches, as a share of a swatch: wide enough for the chosen one's ring. */
private const val SWATCH_GAP_RATIO = 0.3f

/** How many accent pairs go on a row [widthDp] wide: the most of 16, 8, 4 that fit at 28dp with 8dp gaps. */
internal fun accentColumns(widthDp: Float): Int =
    ACCENT_COLUMNS.firstOrNull { it * SWATCH_MIN_DP + (it - 1) * SWATCH_MIN_GAP_DP <= widthDp } ?: ACCENT_COLUMNS.last()

/** The swatch size (whole dp) that fills [widthDp] with [columns] swatches and their gaps, up to 40dp. */
internal fun accentSwatchDp(widthDp: Float, columns: Int): Float =
    floor(widthDp / (columns + (columns - 1) * SWATCH_GAP_RATIO)).coerceAtMost(SWATCH_MAX_DP)

/**
 * The picker's rows with [columns] pairs each: each run of pairs as a row of its vivid accents above
 * a row of their pastel partners, in the web's order. 16 gives the web's four rows ([Accent.rows]).
 */
internal fun accentRows(columns: Int): List<List<Accent>> =
    Accent.pairs.chunked(columns).flatMap { run -> listOf(run.map { it.first }, run.map { it.second }) }

/**
 * The 64 accents as vivid/pastel row pairs, sized to the width so every swatch shows: the web's
 * four rows of 16 where they fit (landscape), else eight rows of 8 (a phone in portrait). The
 * chosen one is enlarged with a ring in its own colour.
 */
@Composable
private fun AccentPicker(selected: Accent, dark: Boolean, onPick: (Accent) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        val width = maxWidth.value
        val columns = accentColumns(width)
        val swatch = accentSwatchDp(width, columns)
        val rows = remember(columns) { accentRows(columns) }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy((swatch * SWATCH_GAP_RATIO).dp)) {
                    row.forEach { accent -> AccentSwatch(accent, swatch.dp, accent == selected, dark) { onPick(accent) } }
                }
            }
        }
    }
}

@Composable
private fun AccentSwatch(accent: Accent, diameter: Dp, selected: Boolean, dark: Boolean, onClick: () -> Unit) {
    val color = swatchColor(accent, dark)
    val ring = OttershelfTheme.colors.border
    val scale = if (selected) 1.25f else 1f
    Box(
        Modifier
            .size(diameter)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                val r = size.minDimension / 2f
                drawCircle(color, radius = r)
                if (accent == Accent.WHITE) drawCircle(ring, radius = r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
                // outline: 2px solid, offset 2px
                if (selected) drawCircle(color, radius = r + 3.dp.toPx(), style = Stroke(2.dp.toPx()))
            }
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = accent.label },
    )
}

/** theme-accent-meta.ts `getAccentPreviewColor`: the accent's light or dark primary; white is always near-white. */
private fun swatchColor(accent: Accent, dark: Boolean): Color = when {
    accent == Accent.WHITE -> oklch(0.985f, 0f, 0f)
    dark -> oklch(accent.darkL, accent.darkC, accent.hue)
    else -> oklch(accent.lightL, accent.lightC, accent.hue)
}

/** The Theme card's radius buttons: each drawn with (roughly) its own corner, the chosen one in the accent. */
@Composable
private fun RadiusPicker(selected: Radius, onPick: (Radius) -> Unit) {
    val colors = OttershelfTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Radius.entries.forEach { radius ->
            val on = radius == selected
            val shape = when (radius) {
                Radius.SHARP -> RoundedCornerShape(2.dp)
                Radius.DEFAULT -> RoundedCornerShape(6.dp)
                Radius.ROUNDED -> RoundedCornerShape(14.dp)
                Radius.PILL -> RoundedCornerShape(percent = 50)
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(shape)
                    .background(if (on) colors.primary.atAlpha(0.08f) else Color.Transparent)
                    .border(2.dp, if (on) colors.primary else colors.border, shape)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(radius) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    radius.label,
                    style = TextStyle(fontFamily = OttershelfFonts.Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp),
                    color = if (on) colors.primary else colors.mutedForeground,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A range input in the accent, snapping to [step]; the value is shown next to the label. */
@Composable
private fun PercentSlider(value: Int, min: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
    val colors = OttershelfTheme.colors
    Slider(
        value = value.toFloat(),
        onValueChange = { raw ->
            val snapped = (((raw - min) / step).roundToInt() * step + min).coerceIn(min, max)
            if (snapped != value) onChange(snapped)
        },
        valueRange = min.toFloat()..max.toFloat(),
        steps = ((max - min) / step - 1).coerceAtLeast(0),
        colors = SliderDefaults.colors(
            thumbColor = colors.primary,
            activeTrackColor = colors.primary,
            inactiveTrackColor = colors.muted,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

// ---- Library background ----

/** The web's four pattern groups, in its order. */
private val BackgroundGroups: List<Pair<Int, List<ThemeBackground>>> = listOf(
    R.string.settings_background_fundamental to listOf(
        ThemeBackground.NONE, ThemeBackground.DOTS, ThemeBackground.CROSS, ThemeBackground.MILLIMETER,
    ),
    R.string.settings_background_structural to listOf(
        ThemeBackground.BLUEPRINT, ThemeBackground.BRUSHED, ThemeBackground.SCANLINES,
        ThemeBackground.VINYL, ThemeBackground.CARBON, ThemeBackground.PERFORATED,
    ),
    R.string.settings_background_ambient to listOf(
        ThemeBackground.AURORA, ThemeBackground.HORIZON, ThemeBackground.GLOW, ThemeBackground.MESH, ThemeBackground.ELEVATION,
    ),
    R.string.settings_background_refractive to listOf(
        ThemeBackground.PRISM, ThemeBackground.SPECTRUM, ThemeBackground.SPECTRUM_X, ThemeBackground.SPECTRUM_PLUS, ThemeBackground.ECLIPSE,
    ),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackgroundSection(selected: ThemeBackground, onPick: (ThemeBackground) -> Unit) {
    val colors = OttershelfTheme.colors
    Column {
        SettingsGroupLabel(stringResource(R.string.settings_background_title))
        SettingsCard {
            Column(SettingsRowPadding) {
                SettingsLabel(stringResource(R.string.settings_background_pattern))
                SettingsHint(stringResource(R.string.settings_background_pattern_hint))
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    BackgroundGroups.forEach { (label, patterns) ->
                        Column {
                            Text(
                                stringResource(label).uppercase(),
                                modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
                                style = TextStyle(
                                    fontFamily = OttershelfFonts.Sans,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.1.em,
                                ),
                                color = colors.mutedForeground,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                patterns.forEach { pattern ->
                                    PatternTile(pattern, pattern == selected) { onPick(pattern) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A pattern preview (w-14 h-10 on the web, a little larger for a finger) with its name under it. */
@Composable
private fun PatternTile(pattern: ThemeBackground, selected: Boolean, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    val shape = OttershelfTheme.radii.smShape
    Column(
        Modifier
            .width(PATTERN_TILE_WIDTH)
            .clip(OttershelfTheme.radii.mdShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(shape)
                .background(colors.background)
                .backgroundPattern(pattern, colors)
                .border(2.dp, if (selected) colors.primary else colors.border, shape),
        )
        Text(
            pattern.label,
            modifier = Modifier.padding(top = 4.dp),
            style = TextStyle(fontFamily = OttershelfFonts.Sans, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, fontSize = 10.sp, lineHeight = 12.sp),
            color = if (selected) colors.primary else colors.mutedForeground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val PATTERN_TILE_WIDTH = 58.dp
