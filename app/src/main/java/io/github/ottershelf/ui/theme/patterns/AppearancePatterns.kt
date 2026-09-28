package io.github.ottershelf.ui.theme.patterns

import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import io.github.ottershelf.core.theme.ThemeBackground
import io.github.ottershelf.ui.theme.BackgroundPainter
import io.github.ottershelf.ui.theme.BackgroundPatterns
import io.github.ottershelf.ui.theme.OttershelfColors
import io.github.ottershelf.ui.theme.oklch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The page patterns beyond `none` and `dots` (which ui.theme.BackgroundPatterns draws itself), one
 * [BackgroundPainter] per [ThemeBackground], following the web's main.css `.pattern-*` recipes
 * (BookOrbit's client/src/assets/main.css). CSS px are drawn as dp.
 *
 * Everything expensive happens once per size and colour set, in the [CacheDrawScope]: repeating
 * patterns are one small bitmap tile or one repeating gradient shader (a single draw call however
 * large the page), the ambient ones a handful of gradient brushes. Nothing allocates per frame.
 *
 * Matching CSS:
 * - `color-mix(in oklch, var(--primary) N%, transparent)` is the primary at N% alpha ([p]).
 * - CSS interpolates gradients with premultiplied alpha, so "fade to transparent" keeps the colour;
 *   here every gradient fades to the same colour at alpha 0 ([clear]), never to transparent black.
 * - CSS ellipses (`radial-gradient(ellipse 60% 45% at ...)`) are circles scaled on one axis. The
 *   default `farthest-corner` ellipse centred on a corner has radii sqrt(2) x the box ([FARTHEST]).
 * - The refractive family's `filter: contrast() brightness()` is applied to the colours themselves
 *   ([filtered]), which is what the filter does to an unblended layer.
 * - The web draws the "fixed" patterns over the viewport; here they cover the element they are
 *   drawn behind (the shell's content area, or a preview tile).
 */
internal val AppearancePatternPainters: Map<ThemeBackground, BackgroundPainter> = mapOf(
    // Fundamental
    ThemeBackground.CROSS to BackgroundPainter { colors ->
        // Two 24px dot grids, the second shifted by 12px: dots at each cell's centre and corners (a
        // corner dot is split over four tiles, so all four quarters are drawn).
        with(BackgroundPatterns) {
            dotGrid(
                colors,
                offsets = listOf(Offset(0.5f, 0.5f), Offset(0f, 0f), Offset(1f, 0f), Offset(0f, 1f), Offset(1f, 1f)),
            )
        }
    },
    ThemeBackground.MILLIMETER to painter { colors -> gridLines(p(colors, 0.05f), cellDp = 2f) },

    // Structural
    ThemeBackground.BLUEPRINT to painter { colors -> gridLines(p(colors, 0.04f), cellDp = 4f) },
    ThemeBackground.BRUSHED to painter { colors ->
        // repeating-linear-gradient(45deg, transparent 0 1px, P6% 1px 2px): one repeating shader.
        val color = p(colors, 0.06f)
        val step = 2.dp.toPx() / sqrt(2f)
        val brush = Brush.linearGradient(
            0f to color.clear(),
            0.5f to color.clear(),
            0.5f to color,
            1f to color,
            start = Offset.Zero,
            end = Offset(step, -step),
            tileMode = TileMode.Repeated,
        )
        ({ drawRect(brush) })
    },
    ThemeBackground.SCANLINES to painter { colors ->
        // A 4px period, its lower half P5%.
        val paint = solid(p(colors, 0.05f))
        tile(cellDp = 4f) { cell -> drawRect(Rect(0f, cell / 2f, cell, cell), paint) }
    },
    ThemeBackground.VINYL to painter { colors ->
        // repeating-radial-gradient(circle at 50% -25%, transparent 0 3px, P8% 3.75px, transparent 4.5px).
        val color = p(colors, 0.08f)
        val brush = Brush.radialGradient(
            0f to color.clear(),
            (3f / 4.5f) to color.clear(),
            (3.75f / 4.5f) to color,
            1f to color.clear(),
            center = Offset(size.width / 2f, -size.height * 0.25f),
            radius = 4.5.dp.toPx(),
            tileMode = TileMode.Repeated,
        )
        ({ drawRect(brush) })
    },
    ThemeBackground.CARBON to painter { colors ->
        // Four ±45° gradients with hard 25%/75% stops on an 8px tile: a right triangle with legs of
        // half the tile in each corner (diamonds where four tiles meet).
        val paint = solid(p(colors, 0.05f), antiAlias = true)
        tile(cellDp = 8f) { cell ->
            val h = cell / 2f
            val path = Path().apply {
                moveTo(0f, 0f); lineTo(h, 0f); lineTo(0f, h); close()
                moveTo(cell, 0f); lineTo(cell, h); lineTo(cell - h, 0f); close()
                moveTo(0f, cell); lineTo(0f, cell - h); lineTo(h, cell); close()
                moveTo(cell, cell); lineTo(cell - h, cell); lineTo(cell, cell - h); close()
            }
            drawPath(path, paint)
        }
    },
    ThemeBackground.PERFORATED to painter { colors ->
        // radial-gradient(circle, P8% 1.5px, transparent 2px) on a 12px grid.
        val color = p(colors, 0.08f)
        val radius = 2.dp.toPx()
        tile(cellDp = 12f) { cell ->
            val center = Offset(cell / 2f, cell / 2f)
            val paint = Paint().apply { isAntiAlias = true }
            Brush.radialGradient(0f to color, 0.75f to color, 1f to color.clear(), center = center, radius = radius)
                .applyTo(Size(cell, cell), paint, 1f)
            drawCircle(center, radius, paint)
        }
    },

    // Ambient
    ThemeBackground.AURORA to painter { colors ->
        val w = size.width
        val h = size.height
        val glows = listOf(
            Glow(p(colors, 0.15f), Offset(0f, h * 0.5f), rx = w * 0.6f, ry = h * 0.45f),
            Glow(p(colors, 0.10f), Offset(w, h * 0.5f), rx = w * 0.5f, ry = h * 0.4f),
            Glow(p(colors, 0.08f), Offset(w * 0.5f, 0f), rx = w * 0.4f, ry = h * 0.35f),
        )
        ({ glows.forEach { draw(it) } })
    },
    ThemeBackground.HORIZON to painter { colors ->
        val color = p(colors, 0.08f)
        val brush = Brush.verticalGradient(0f to color.clear(), 0.5f to color, 1f to color.clear())
        ({ drawRect(brush) })
    },
    ThemeBackground.GLOW to painter { colors ->
        val glow = Glow(
            p(colors, 0.20f),
            Offset(size.width * 0.5f, -size.height * 0.1f),
            rx = size.width * 0.8f,
            ry = size.height * 0.5f,
        )
        ({ draw(glow) })
    },
    ThemeBackground.MESH to painter { colors ->
        // Five farthest-corner ellipses fading out by 50%: the four corners and the centre.
        val w = size.width
        val h = size.height
        val glows = listOf(
            Glow(p(colors, 0.20f), Offset(0f, 0f), rx = w * FARTHEST, ry = h * FARTHEST, end = 0.5f),
            Glow(p(colors, 0.15f), Offset(w, 0f), rx = w * FARTHEST, ry = h * FARTHEST, end = 0.5f),
            Glow(p(colors, 0.20f), Offset(w, h), rx = w * FARTHEST, ry = h * FARTHEST, end = 0.5f),
            Glow(p(colors, 0.15f), Offset(0f, h), rx = w * FARTHEST, ry = h * FARTHEST, end = 0.5f),
            Glow(p(colors, 0.10f), Offset(w / 2f, h / 2f), rx = w / 2f * FARTHEST, ry = h / 2f * FARTHEST, end = 0.5f),
        )
        ({ glows.forEach { draw(it) } })
    },
    ThemeBackground.ELEVATION to painter { colors ->
        val top = p(colors, 0.05f)
        val bottom = p(colors, 0.03f)
        val fromTop = Brush.verticalGradient(0f to top, 0.4f to top.clear(), 1f to top.clear())
        val fromBottom = Brush.verticalGradient(0f to bottom.clear(), 0.7f to bottom.clear(), 1f to bottom)
        ({
            drawRect(fromTop)
            drawRect(fromBottom)
        })
    },

    // Refractive
    ThemeBackground.PRISM to painter {
        // Red, blue and green at 3%: contrast(150%) brightness(110%) leaves pure primaries as they are.
        val brushes = listOf(
            cssLinear(135f, size, Color(1f, 0f, 0f, 0.03f)),
            cssLinear(225f, size, Color(0f, 0f, 1f, 0.03f)),
            cssLinear(45f, size, Color(0f, 1f, 0f, 0.03f)),
        )
        ({ brushes.forEach { drawRect(it) } })
    },
    ThemeBackground.SPECTRUM to painter { colors ->
        val h = colors.tintHue
        val brushes = listOf(
            cssLinear(135f, size, spectrum(h, 0.08f, contrast = 1.6f)),
            cssLinear(225f, size, spectrum(h + 120f, 0.08f, contrast = 1.6f)),
            cssLinear(45f, size, spectrum(h + 240f, 0.08f, contrast = 1.6f)),
        )
        ({ brushes.forEach { drawRect(it) } })
    },
    ThemeBackground.SPECTRUM_X to painter { colors ->
        val h = colors.tintHue
        val brushes = listOf(
            cssLinear(135f, size, spectrum(h, 0.08f, contrast = 1.6f)),
            cssLinear(225f, size, spectrum(h + 90f, 0.08f, contrast = 1.6f)),
            cssLinear(315f, size, spectrum(h + 180f, 0.08f, contrast = 1.6f)),
            cssLinear(45f, size, spectrum(h + 270f, 0.08f, contrast = 1.6f)),
        )
        ({ brushes.forEach { drawRect(it) } })
    },
    ThemeBackground.SPECTRUM_PLUS to painter { colors ->
        // "to bottom", "to left", "to top", "to right".
        val h = colors.tintHue
        val brushes = listOf(
            cssLinear(180f, size, spectrum(h, 0.08f, contrast = 1.6f)),
            cssLinear(270f, size, spectrum(h + 90f, 0.08f, contrast = 1.6f)),
            cssLinear(0f, size, spectrum(h + 180f, 0.08f, contrast = 1.6f)),
            cssLinear(90f, size, spectrum(h + 270f, 0.08f, contrast = 1.6f)),
        )
        ({ brushes.forEach { drawRect(it) } })
    },
    ThemeBackground.ECLIPSE to painter { colors ->
        val hue = colors.tintHue
        val w = size.width
        val h = size.height
        val glows = listOf(
            Glow(spectrum(hue, 0.12f, contrast = 1.5f), Offset(0f, 0f), rx = w * FARTHEST, ry = h * FARTHEST, end = 0.65f),
            Glow(spectrum(hue + 180f, 0.12f, contrast = 1.5f), Offset(w, h), rx = w * FARTHEST, ry = h * FARTHEST, end = 0.65f),
        )
        ({ glows.forEach { draw(it) } })
    },
)

/** A `farthest-corner` ellipse's radius as a multiple of the box, for a centre on a corner. */
private val FARTHEST = sqrt(2f)

/** A painter whose drawing is clipped to the element (the glows reach far outside it). */
private fun painter(build: CacheDrawScope.(OttershelfColors) -> (DrawScope.() -> Unit)) = BackgroundPainter { colors ->
    val draw = build(colors)
    ({ clipRect { draw() } })
}

/** `color-mix(in oklch, var(--primary) [fraction], transparent)`. */
private fun p(colors: OttershelfColors, fraction: Float): Color = colors.primary.copy(alpha = colors.primary.alpha * fraction)

/** The same colour, fully transparent: what CSS's premultiplied gradients fade towards. */
private fun Color.clear(): Color = copy(alpha = 0f)

private fun solid(color: Color, antiAlias: Boolean = false) = Paint().apply {
    this.color = color
    isAntiAlias = antiAlias
}

/**
 * One [cellDp]-square bitmap tile, painted once by [draw] (given the cell size in px) and drawn as a
 * repeating shader.
 */
private fun CacheDrawScope.tile(cellDp: Float, draw: Canvas.(cell: Float) -> Unit): DrawScope.() -> Unit {
    val cell = cellDp.dp.toPx().roundToInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(cell, cell)
    Canvas(bitmap).draw(cell.toFloat())
    val brush = ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
    return { drawRect(brush) }
}

/**
 * A 1px line along the left and top of every [cellDp] cell (`linear-gradient(to right, C 1px,
 * transparent 1px), linear-gradient(to bottom, ...)` with `background-size: N N`); where the lines
 * cross, the two layers stack as they do in CSS.
 */
private fun CacheDrawScope.gridLines(color: Color, cellDp: Float): DrawScope.() -> Unit {
    val line = max(1, 1.dp.toPx().roundToInt()).toFloat()
    val paint = solid(color)
    return tile(cellDp) { cell ->
        drawRect(Rect(0f, 0f, line, cell), paint)
        drawRect(Rect(0f, 0f, cell, line), paint)
    }
}

/** A radial gradient from [color] at [center] to transparent at [end] of the ellipse [rx] x [ry]. */
private class Glow(color: Color, val center: Offset, val rx: Float, val ry: Float, end: Float = 1f) {
    val brush: Brush? = if (rx > 0f && ry > 0f) {
        Brush.radialGradient(0f to color, end to color.clear(), 1f to color.clear(), center = center, radius = rx)
    } else {
        null
    }
}

/** Draws [glow]'s circle squashed into its ellipse; only the ellipse's bounds are painted. */
private fun DrawScope.draw(glow: Glow) {
    val brush = glow.brush ?: return
    scale(scaleX = 1f, scaleY = glow.ry / glow.rx, pivot = glow.center) {
        drawRect(brush, topLeft = glow.center - Offset(glow.rx, glow.rx), size = Size(glow.rx * 2f, glow.rx * 2f))
    }
}

/**
 * `linear-gradient([angleDeg]deg, [color] 0%, transparent 50%)` over [size]: the CSS gradient line
 * runs through the centre at that angle (0 = up, 90 = right) and is just long enough for the
 * corners to sit at 0% and 100%.
 */
private fun cssLinear(angleDeg: Float, size: Size, color: Color): Brush {
    val rad = Math.toRadians(angleDeg.toDouble())
    val dx = sin(rad).toFloat()
    val dy = -cos(rad).toFloat()
    val length = abs(size.width * dx) + abs(size.height * dy)
    val half = Offset(dx, dy) * (length / 2f)
    val center = Offset(size.width / 2f, size.height / 2f)
    return Brush.linearGradient(0f to color, 0.5f to color.clear(), 1f to color.clear(), start = center - half, end = center + half)
}

/** `oklch(0.7 0.15 [hue] / [alpha])` under `filter: contrast([contrast]) brightness(110%)`. */
private fun spectrum(hue: Float, alpha: Float, contrast: Float): Color {
    val base = oklch(0.7f, 0.15f, ((hue % 360f) + 360f) % 360f)
    return filtered(base, contrast, brightness = 1.1f).copy(alpha = alpha)
}

/** CSS `contrast()` then `brightness()` on one colour's sRGB channels. */
private fun filtered(color: Color, contrast: Float, brightness: Float): Color {
    fun f(v: Float) = (((v - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f) * brightness).coerceIn(0f, 1f)
    return Color(f(color.red), f(color.green), f(color.blue), color.alpha)
}
