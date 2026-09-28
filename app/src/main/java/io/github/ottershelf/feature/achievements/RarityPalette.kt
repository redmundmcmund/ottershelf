package io.github.ottershelf.feature.achievements

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import io.github.ottershelf.ui.theme.OttershelfTheme

/**
 * The four rarities as categorical colours (the dataviz method: identity, fixed order, selected
 * steps per mode, never the status colours). Slate, blue, magenta and gold, stepped so that every
 * pair clears normal-vision Delta E 15 and protan/deutan Delta E 8 and each clears 3:1 on the card
 * in its mode (RarityPaletteTest runs the validator's math on them). The rarity is always also
 * written out (the pill), so colour is never the only cue.
 */
object RarityPalette {
    val RARITIES = listOf("common", "rare", "epic", "legendary")

    private val light = listOf(0xFF3F4652, 0xFF2A78D6, 0xFFC2437A, 0xFFA86A00).map { Color(it) }
    private val dark = listOf(0xFF9CA3AF, 0xFF3987E5, 0xFFD55181, 0xFFC98500).map { Color(it) }

    fun colors(dark: Boolean): List<Color> = if (dark) this.dark else light

    fun color(rarity: String?, dark: Boolean): Color = colors(dark)[RARITIES.indexOf(rarity).coerceAtLeast(0)]
}

@Composable
@ReadOnlyComposable
fun rarityColor(rarity: String?): Color = RarityPalette.color(rarity, OttershelfTheme.colors.isDark)
