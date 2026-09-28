package io.github.ottershelf.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A CSS `oklch(l c h / alpha)` colour, as the web's tokens define every colour: built in Oklab
 * (a = c·cos h, b = c·sin h) and converted to sRGB. Out-of-gamut colours are clipped per channel,
 * as browsers draw them on an sRGB screen, so the values match what the web shows.
 */
fun oklch(l: Float, c: Float, h: Float, alpha: Float = 1f): Color {
    val radians = h * (PI.toFloat() / 180f)
    val lab = Color(
        red = l.coerceIn(0f, 1f),
        green = (c * cos(radians)).coerceIn(-0.5f, 0.5f),
        blue = (c * sin(radians)).coerceIn(-0.5f, 0.5f),
        alpha = alpha,
        colorSpace = ColorSpaces.Oklab,
    )
    return lab.convert(ColorSpaces.Srgb).clipped()
}

/** Per-channel clip into [0, 1] (Compose may carry out-of-range floats through a conversion). */
private fun Color.clipped(): Color = Color(
    red = red.coerceIn(0f, 1f),
    green = green.coerceIn(0f, 1f),
    blue = blue.coerceIn(0f, 1f),
    alpha = alpha.coerceIn(0f, 1f),
)

/** CSS `color-mix(in oklch, this p%, transparent)`: the same colour at that alpha. */
fun Color.atAlpha(fraction: Float): Color = copy(alpha = alpha * fraction)

/** This colour at [fraction] opacity flattened onto [background] (an opaque result). */
fun Color.over(background: Color, fraction: Float = 1f): Color = atAlpha(fraction).compositeOver(background)
