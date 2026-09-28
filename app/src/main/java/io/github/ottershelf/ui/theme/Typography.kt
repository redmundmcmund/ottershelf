package io.github.ottershelf.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The web's fonts are Inter (body) and Fraunces (headings, always semibold with -0.025em tracking).
 * Both are SIL OFL, so they may be bundled; until their TTFs are added to `res/font` (then set
 * [Sans] and [Serif] here, nothing else changes) the closest system faces are used: the system
 * sans (Roboto, variable, like Inter a neo-grotesque with every weight) and the system serif.
 * That keeps the APK light; the sizes and weights below are the web's and the Nexus app's.
 */
object OttershelfFonts {
    val Sans: FontFamily = FontFamily.SansSerif
    val Serif: FontFamily = FontFamily.Serif
    val Mono: FontFamily = FontFamily.Monospace

    /** Fraunces headings' tracking. */
    val SerifTracking = (-0.025).em
}

private fun sans(size: Int, weight: FontWeight, lineHeight: Float, tracking: Float = 0f) = TextStyle(
    fontFamily = OttershelfFonts.Sans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * lineHeight).sp,
    letterSpacing = tracking.em,
)

private fun serif(size: Int, lineHeight: Float = 1.25f) = TextStyle(
    fontFamily = OttershelfFonts.Serif,
    fontWeight = FontWeight.SemiBold,
    fontSize = size.sp,
    lineHeight = (size * lineHeight).sp,
    letterSpacing = OttershelfFonts.SerifTracking,
)

/**
 * Material's type scale, filled with the sizes the Nexus app and the web use:
 *
 * | Slot | Size / weight | Used for |
 * |---|---|---|
 * | display*, headline* | serif semibold 32/28/26, 24/22/20 | page titles, the wordmark, dialog titles |
 * | titleLarge | 19 medium | the toolbar title (Nexus `ToolbarTitle`) |
 * | titleMedium | 15 semibold | card and section titles (`DashTitle`, the web's 15px bold) |
 * | titleSmall | 13 medium | a book's title under its cover, list row titles |
 * | bodyLarge | 15 regular | drawer rows, list rows, paragraphs |
 * | bodyMedium | 13 regular | secondary lines, descriptions |
 * | bodySmall | 12 regular | an author under a cover, messages in cards (dim) |
 * | labelLarge | 14 medium | buttons |
 * | labelMedium | 12 medium | count pills, chips |
 * | labelSmall | 11 medium | small badges, the drawer's section headers (caps, tracked) |
 */
val OttershelfTypography: Typography = Typography(
    displayLarge = serif(32),
    displayMedium = serif(28),
    displaySmall = serif(26),
    headlineLarge = serif(24),
    headlineMedium = serif(22),
    headlineSmall = serif(20),
    titleLarge = sans(19, FontWeight.Medium, 1.3f),
    titleMedium = sans(15, FontWeight.SemiBold, 1.35f),
    titleSmall = sans(13, FontWeight.Medium, 1.35f),
    bodyLarge = sans(15, FontWeight.Normal, 1.45f),
    bodyMedium = sans(13, FontWeight.Normal, 1.45f),
    bodySmall = sans(12, FontWeight.Normal, 1.4f),
    labelLarge = sans(14, FontWeight.Medium, 1.3f),
    labelMedium = sans(12, FontWeight.Medium, 1.3f),
    labelSmall = sans(11, FontWeight.Medium, 1.3f),
)
