package io.github.ottershelf.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ottershelf.core.theme.Radius

/**
 * The web's radius scale (bridge.css, radius.css) for one [Radius] option, in dp for the web's px:
 * sm = r-4, md = r-2, lg = r, xl = r+4, 2xl = r+8, 3xl = r+12 (never below 0), and the shell's
 * `min(r, 20)`. Inputs and buttons use [md], cards [lg], dashboard cards [xl2].
 *
 * Read it with `OttershelfTheme.radii`; Material's [Shapes] are mapped from it.
 */
@Immutable
data class OttershelfRadii(
    val base: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xl2: Dp,
    val xl3: Dp,
    /** Toolbar, drawer and other full-height shell panels: capped so "pill" doesn't make lozenges. */
    val shell: Dp,
) {
    val smShape get() = RoundedCornerShape(sm)
    val mdShape get() = RoundedCornerShape(md)
    val lgShape get() = RoundedCornerShape(lg)
    val xlShape get() = RoundedCornerShape(xl)
    val xl2Shape get() = RoundedCornerShape(xl2)
    val shellShape get() = RoundedCornerShape(shell)

    companion object {
        fun of(radius: Radius): OttershelfRadii {
            val r = radius.baseDp
            fun d(v: Float) = v.coerceAtLeast(0f).dp
            return OttershelfRadii(
                base = d(r),
                sm = d(r - 4f),
                md = d(r - 2f),
                lg = d(r),
                xl = d(r + 4f),
                xl2 = d(r + 8f),
                xl3 = d(r + 12f),
                shell = d(minOf(r, 20f)),
            )
        }
    }
}

val LocalOttershelfRadii = staticCompositionLocalOf { OttershelfRadii.of(Radius.DEFAULT) }

/**
 * Material's shape slots from the radius scale: extraSmall = sm (chips, small badges), small = md
 * (buttons, fields, menus), medium = lg (cards), large = xl (sheets' inner cards), extraLarge = 2xl
 * (dialogs, bottom sheets, dashboard cards).
 */
fun OttershelfRadii.toMaterialShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(sm),
    small = RoundedCornerShape(md),
    medium = RoundedCornerShape(lg),
    large = RoundedCornerShape(xl),
    extraLarge = RoundedCornerShape(xl2),
)
