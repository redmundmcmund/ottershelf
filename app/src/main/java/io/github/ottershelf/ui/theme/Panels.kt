package io.github.ottershelf.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import io.github.ottershelf.core.theme.PanelStyle

/** Which of the web's two see-through fills a panel takes. */
enum class PanelKind {
    /** A dashboard card (`bg-card/30`): mostly page. */
    Card,

    /** A settings or detail panel (`bg-card/60`): mostly card, for denser text. */
    Panel,
}

/**
 * Fills a card or panel in the user's [PanelStyle] ([OttershelfColors.panels]):
 *
 * - **Solid:** the card colour, opaque (a [PanelKind.Card] is the web's 30% flattened onto the page).
 * - **Glass:** the card colour at the web's 30% / 60%, so the pattern and glow show through, with a
 *   faint sheen across the top edge.
 * - **Gradient:** from an accent-tinted card in the top start corner to see-through card colour in
 *   the bottom end corner.
 *
 * Brushes are built once per size (drawWithCache); the fill follows [shape].
 */
fun Modifier.panelFill(colors: OttershelfColors, kind: PanelKind, shape: Shape): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val card = colors.card
    val brushes: List<Brush> = when (colors.panels) {
        PanelStyle.SOLID -> listOf(SolidColor(if (kind == PanelKind.Card) colors.dashCard else card))
        PanelStyle.GLASS -> {
            val alpha = if (kind == PanelKind.Card) 0.30f else 0.62f
            val sheen = Color.White.atAlpha(if (colors.isDark) 0.06f else 0.40f)
            listOf(
                SolidColor(card.atAlpha(alpha)),
                Brush.verticalGradient(0f to sheen, 0.45f to sheen.copy(alpha = 0f), endY = size.height),
            )
        }
        PanelStyle.GRADIENT -> {
            val tint = colors.primary.over(card, if (colors.isDark) 0.22f else 0.14f)
            val (from, to) = if (kind == PanelKind.Card) 0.85f to 0.35f else 0.95f to 0.70f
            val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
            val start = if (rtl) Offset(size.width, 0f) else Offset.Zero
            val end = if (rtl) Offset(0f, size.height) else Offset(size.width, size.height)
            listOf(Brush.linearGradient(0f to tint.atAlpha(from), 1f to card.atAlpha(to), start = start, end = end))
        }
    }
    onDrawBehind { brushes.forEach { drawOutline(outline, it) } }
}
