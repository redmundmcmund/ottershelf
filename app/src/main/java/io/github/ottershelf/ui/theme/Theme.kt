package io.github.ottershelf.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.ottershelf.core.theme.ThemePrefs

/** The appearance settings in effect (for the pattern, and screens that show them). */
val LocalThemePrefs = staticCompositionLocalOf { ThemePrefs() }

/**
 * BookOrbit's look: the web's token system computed for the user's [ThemePrefs] (accent, radius,
 * brightness, surface opacity), in light or dark ([ThemePrefs.theme]; `system` follows
 * [isSystemInDarkTheme]). Provides:
 *
 * - Material 3's `MaterialTheme` (colorScheme, shapes, typography) mapped from the tokens, so
 *   stock components already look like BookOrbit;
 * - [OttershelfTheme.colors]: every token (card, surface1-4, border, dim text, success/warning/info,
 *   format and read-status colours, dashboard-card colours);
 * - [OttershelfTheme.radii]: the radius scale (sm/md/lg/xl/2xl/3xl/shell).
 *
 * The app passes `container.themePrefs` (MainActivity); previews and screenshot tests use the web
 * defaults (blue, default radius) unless they pass their own.
 */
@Composable
fun OttershelfTheme(
    prefs: ThemePrefs = ThemePrefs(),
    darkTheme: Boolean = prefs.theme.isDark(isSystemInDarkTheme()),
    content: @Composable () -> Unit,
) {
    val colors = remember(prefs.accent, prefs.brightness, prefs.surfaceOpacity, darkTheme) {
        ottershelfColors(prefs, darkTheme)
    }
    val radii = remember(prefs.radius) { OttershelfRadii.of(prefs.radius) }
    val colorScheme = remember(colors) { colors.toColorScheme() }
    val shapes = remember(radii) { radii.toMaterialShapes() }

    // Status and navigation bar icons follow the app's theme, not the system's (they differ when
    // the theme is set to light or dark explicitly).
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalOttershelfColors provides colors,
        LocalOttershelfRadii provides radii,
        LocalThemePrefs provides prefs,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = shapes,
            typography = OttershelfTypography,
            content = content,
        )
    }
}

/** The BookOrbit tokens beyond Material's: `OttershelfTheme.colors.dashCard`, `OttershelfTheme.radii.xl2`. */
object OttershelfTheme {
    val colors: OttershelfColors
        @Composable @ReadOnlyComposable get() = LocalOttershelfColors.current

    val radii: OttershelfRadii
        @Composable @ReadOnlyComposable get() = LocalOttershelfRadii.current

    val prefs: ThemePrefs
        @Composable @ReadOnlyComposable get() = LocalThemePrefs.current
}

/**
 * Material's roles from the web tokens (theming.md section 2e), with two choices of our own:
 * secondaryContainer is the accent tint with primary text (Material uses it for selected drawer
 * rows, filter chips and tonal buttons, which the web and the Nexus app draw as `bg-primary/12`
 * with accent text), and surfaceTint equals surface, so tonal elevation never tints anything.
 */
fun OttershelfColors.toColorScheme(): ColorScheme {
    val scheme = if (isDark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primary.over(card, 0.15f),
        onPrimaryContainer = primary,
        inversePrimary = primary,
        secondary = mutedForeground,
        onSecondary = background,
        secondaryContainer = primary.over(card, 0.12f),
        onSecondaryContainer = primary,
        tertiary = info,
        onTertiary = onInfo,
        tertiaryContainer = info.over(card, 0.15f),
        onTertiaryContainer = info,
        background = background,
        onBackground = foreground,
        surface = card,
        onSurface = foreground,
        surfaceVariant = muted,
        onSurfaceVariant = mutedForeground,
        surfaceTint = card,
        inverseSurface = foreground,
        inverseOnSurface = background,
        error = destructive,
        onError = onDestructive,
        errorContainer = destructive.over(card, 0.15f),
        onErrorContainer = destructive,
        outline = input,
        outlineVariant = border,
        scrim = Color.Black,
        surfaceBright = if (isDark) surface4 else background,
        surfaceDim = if (isDark) background else surface3,
        surfaceContainerLowest = background,
        surfaceContainerLow = surface1,
        surfaceContainer = surface2,
        surfaceContainerHigh = surface3,
        surfaceContainerHighest = surface4,
    )
}
