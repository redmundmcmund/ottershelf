package io.github.ottershelf.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import io.github.ottershelf.core.theme.ThemeBackground
import io.github.ottershelf.ui.theme.patterns.AppearancePatternPainters
import kotlin.math.roundToInt

/**
 * Draws one of the web's page patterns (main.css `.pattern-*`, theming.md section 2d) behind
 * content. [prepare] runs once per size and colour set (in a [CacheDrawScope], so shaders, paths
 * and bitmaps are built there, not on every frame) and returns what to draw.
 *
 * The web draws patterns in CSS px; draw them in dp (`24.dp.toPx()`), so a 24px grid is 24dp here.
 */
fun interface BackgroundPainter {
    fun CacheDrawScope.prepare(colors: OttershelfColors): DrawScope.() -> Unit
}

/**
 * The patterns there are painters for. `none` and `dots` are here; the others live in
 * `ui/theme/patterns/AppearancePatterns.kt` ([AppearancePatternPainters]), where the appearance work
 * adds them. A pattern with no painter draws nothing (as `none`).
 */
object BackgroundPatterns {
    val None = BackgroundPainter { { } }

    /** 1px dots of `--dot-color` on a 24px grid, one in the middle of each cell (`.pattern-dots`). */
    val Dots = BackgroundPainter { colors -> dotGrid(colors, offsets = listOf(Offset(0.5f, 0.5f))) }

    private val builtIn: Map<ThemeBackground, BackgroundPainter> = mapOf(
        ThemeBackground.NONE to None,
        ThemeBackground.DOTS to Dots,
    )

    fun painter(pattern: ThemeBackground): BackgroundPainter =
        builtIn[pattern] ?: AppearancePatternPainters[pattern] ?: None

    /** Whether [pattern] draws anything yet (the Appearance screen can mark the rest). */
    fun isAvailable(pattern: ThemeBackground): Boolean = pattern in builtIn || pattern in AppearancePatternPainters

    /**
     * A repeating grid of 1dp-radius dots in `--dot-color`, [cellDp] square, with a dot at each of
     * [offsets] (fractions of the cell). One bitmap tile drawn as a repeating shader: a single draw
     * call however large the page. `cross` is the same with a second offset.
     */
    fun CacheDrawScope.dotGrid(
        colors: OttershelfColors,
        offsets: List<Offset>,
        cellDp: Float = 24f,
        radiusDp: Float = 1f,
    ): DrawScope.() -> Unit {
        val cell = cellDp.dp.toPx().roundToInt().coerceAtLeast(1)
        val tile = ImageBitmap(cell, cell)
        val paint = Paint().apply {
            color = colors.dotColor
            isAntiAlias = true
        }
        val canvas = Canvas(tile)
        val radius = radiusDp.dp.toPx()
        offsets.forEach { canvas.drawCircle(Offset(it.x * cell, it.y * cell), radius, paint) }
        val brush = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
        return { drawRect(brush) }
    }
}

/** Draws [pattern] behind this element's content, in [colors]. */
fun Modifier.backgroundPattern(pattern: ThemeBackground, colors: OttershelfColors): Modifier {
    val painter = BackgroundPatterns.painter(pattern)
    if (painter === BackgroundPatterns.None) return this
    return drawWithCache {
        val draw = with(painter) { prepare(colors) }
        onDrawBehind(draw)
    }
}

/**
 * The page behind the app's content: the background colour with the user's pattern on it. The
 * shell puts this behind its content area; screens themselves stay transparent (or draw cards).
 */
@Composable
fun PatternBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val colors = OttershelfTheme.colors
    Box(
        modifier = modifier
            .background(colors.background)
            .backgroundPattern(OttershelfTheme.prefs.background, colors),
        content = content,
    )
}
