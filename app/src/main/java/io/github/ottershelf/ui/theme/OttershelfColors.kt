package io.github.ottershelf.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.theme.Accent
import io.github.ottershelf.core.theme.AccentTone
import io.github.ottershelf.core.theme.ThemePrefs

/**
 * Every colour of the web's token system (BookOrbit's client/src/assets/theme/tokens.css and
 * accents.css) for one set of [ThemePrefs] and light or dark,
 * plus the few the Nexus app derived from them for its cards. Read it with
 * `OttershelfTheme.colors`; the Material 3 ColorScheme is mapped from it.
 *
 * Names follow the web's tokens (`--muted-foreground` is [mutedForeground], the "dim text").
 */
@Immutable
data class OttershelfColors(
    val isDark: Boolean,
    /** The accent's hue (`--tint-h`), for patterns and placeholder covers that derive colours from it. */
    val tintHue: Float,
    /** Whether the accent is a pastel one (softer placeholder covers). */
    val pastel: Boolean,

    // Neutrals (tinted with the accent's hue)
    val background: Color,
    val foreground: Color,
    val card: Color,
    val cardForeground: Color,
    val popover: Color,
    val sidebar: Color,
    val secondary: Color,
    val secondaryForeground: Color,
    val muted: Color,
    /** The web's "dim text". */
    val mutedForeground: Color,
    /** A count beside a navigation row or title. */
    val countForeground: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val surface4: Color,
    val border: Color,
    val input: Color,
    val ring: Color,
    val dotColor: Color,

    // Accent
    val primary: Color,
    val onPrimary: Color,
    /** Primary at 6% (a pressed row). */
    val accentWash: Color,
    /** Primary at 12% (a selected row, a count pill). */
    val accentTint: Color,
    /** Primary at 30% (the shell's border). */
    val accentLine: Color,
    /** The toolbar and drawer fill: the sidebar colour at the surface opacity. */
    val shellSurface: Color,
    val shellBorder: Color,

    // Semantic
    val destructive: Color,
    val onDestructive: Color,
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    val info: Color,
    val onInfo: Color,
    val starHighlight: Color,
    val volumeRead: Color,
    val volumeReading: Color,
    val volumeUnread: Color,
    val volumeMissing: Color,
    val scoreRed: Color,
    val scoreOrange: Color,
    val scoreYellow: Color,
    val scoreGreen: Color,
    val pillSuccess: Color,
    val pillWarning: Color,
    val pillInfo: Color,

    /** Library composition bar families. */
    val formatEbook: Color,
    val formatKindle: Color,
    val formatDocument: Color,
    val formatComic: Color,
    val formatAudio: Color,
    val formatOther: Color,

    // Cards (the Nexus app's dashboard look, from the web's classes)
    /** A dashboard card: the web's `bg-card/30` flattened onto the page. */
    val dashCard: Color,
    /** Its edge: `border-primary/40`. */
    val dashCardBorder: Color,
    /** A row inside a card (`bg-muted/20`), and pressed (`bg-muted/40`). */
    val cardRow: Color,
    val cardRowPressed: Color,
    /** The small framed icon before a section title (`bg-muted/50`). */
    val iconBox: Color,
    /** A cover's empty box: foreground 12% into the background (cover-effects.css). */
    val coverSurface: Color,
    /** Dark pill over a cover (series number): black at 60%. */
    val overlayPill: Color,
    /** The read-status disc on a cover (the Nexus grid's). */
    val statusDisc: Color,
    /** Reading progress on a cover's bottom edge: primary at 70%, green-500 at 80% once read. */
    val coverProgress: Color,
    val coverProgressRead: Color,
    /** The streak flame (Tailwind orange-500). */
    val flame: Color,

    private val pillFormats: Map<String, Color>,
) {
    /** Text colour of a format chip (`--pill-format-*`), tuned per theme. */
    fun formatPill(format: String?): Color = pillFormats[format?.lowercase()] ?: pillFormats.getValue("default")

    /** A read status's colour (useBookStatus.ts); unread is the dim text. */
    fun readStatus(status: ReadStatus): Color =
        if (status == ReadStatus.UNREAD) mutedForeground else ReadStatusColors.getValue(status)

    companion object {
        /** The web's read-status colours (Tailwind v4 500s, rose-400 for abandoned). */
        val ReadStatusColors: Map<ReadStatus, Color> = mapOf(
            ReadStatus.WANT_TO_READ to Color(0xFF8E51FF),
            ReadStatus.READING to Color(0xFF2B7FFF),
            ReadStatus.ON_HOLD to Color(0xFFFE9A00),
            ReadStatus.REREADING to Color(0xFFE12AFB),
            ReadStatus.READ to Color(0xFF00BC7D),
            ReadStatus.SKIMMED to Color(0xFF00B8DB),
            ReadStatus.ABANDONED to Color(0xFFFF637E),
        )

        /**
         * The format chip over a cover (format-colors.ts), the same in both themes; shown at 90%
         * with white text.
         */
        private val FormatBadgeColors: Map<String, Color> = mapOf(
            "epub" to Color(0xFF16A34A), "kepub" to Color(0xFF0D9488), "mobi" to Color(0xFF6366F1),
            "azw3" to Color(0xFF14B8A6), "azw" to Color(0xFF0EA5E9), "fb2" to Color(0xFFEC4899),
            "djvu" to Color(0xFF64748B), "txt" to Color(0xFF78716C), "pdf" to Color(0xFFDC2626),
            "cbz" to Color(0xFF3B82F6), "cbr" to Color(0xFFF97316), "cb7" to Color(0xFF8B5CF6),
            "cbx" to Color(0xFFC026D3), "m4b" to Color(0xFFF59E0B), "m4a" to Color(0xFFEAB308),
            "mp3" to Color(0xFF22C55E), "opus" to Color(0xFF06B6D4), "ogg" to Color(0xFF84CC16),
            "flac" to Color(0xFF10B981),
        )
        private val FormatBadgeDefault = Color(0xFF6B7280)

        /** A read-along file's chip instead (file-capabilities.ts). */
        val ReadAlongBadge = Color(0xFF0F766E)

        /** The cover chip's fill for [format], opaque (draw it at 90%). */
        fun formatBadge(format: String?): Color = FormatBadgeColors[format?.lowercase()] ?: FormatBadgeDefault

        /** The web's rating-star colours by rounded rating (BookCoverCard.vue). */
        fun ratingStar(rating: Int): Color = when {
            rating <= 1 -> Color(0xFFDC2626)
            rating == 2 -> Color(0xFFEA580C)
            rating == 3 -> Color(0xFFCA8A04)
            rating == 4 -> Color(0xFF65A30D)
            else -> Color(0xFF059669)
        }
    }
}

val LocalOttershelfColors = staticCompositionLocalOf { ottershelfColors(ThemePrefs(), dark = false) }

/**
 * The tokens for [prefs] in light or [dark] mode. Pure: the same inputs always give the same
 * colours (cheap enough to recompute when a preference changes).
 */
fun ottershelfColors(prefs: ThemePrefs, dark: Boolean): OttershelfColors {
    val accent = prefs.accent
    val h = accent.hue
    val c = when {
        accent.isNeutral -> 0f
        dark -> 0.0175f
        else -> 0.006f
    }
    val lift = if (dark) prefs.brightness.coerceIn(0, 100) / 100f * 0.12f else 0f

    /** A neutral at lightness [l] (lifted in dark mode when [lifted]). */
    fun n(l: Float, lifted: Boolean = true) = oklch(l + if (lifted) lift else 0f, c, h)
    fun dimText(l: Float) = oklch(l, 0.001f, h)
    fun pick(light: Color, darkColor: Color) = if (dark) darkColor else light
    fun ok(l: Float, ch: Float, hue: Float) = oklch(l, ch, hue)

    val background = pick(n(0.99f), n(0.145f))
    val foreground = pick(n(0.145f), dimText(0.985f))
    val card = pick(n(0.975f), n(0.18f))
    val muted = pick(n(0.955f), n(0.245f))
    val sidebar = when {
        accent == Accent.WHITE -> pick(ok(0.985f, 0f, 0f), ok(0.205f, 0f, 0f))
        accent == Accent.GREY -> pick(ok(0.96f, 0f, 0f), ok(0.22f, 0f, 0f))
        else -> pick(n(0.965f), n(0.18f))
    }
    val primary = if (dark) ok(accent.darkL, accent.darkC, h) else ok(accent.lightL, accent.lightC, h)
    val onPrimary = if (!dark && accent.whiteOnLight) ok(0.985f, 0f, 0f) else ok(0.145f, 0f, 0f)
    val white = Color.White
    val border = pick(n(0.878f), white.atAlpha(0.10f))
    val nearWhite = ok(0.985f, 0f, 0f)
    val nearBlack = ok(0.145f, 0f, 0f)
    val dashCard = card.over(background, 0.30f)

    return OttershelfColors(
        isDark = dark,
        tintHue = h,
        pastel = accent.tone == AccentTone.Pastel,

        background = background,
        foreground = foreground,
        card = card,
        cardForeground = foreground,
        popover = card,
        sidebar = sidebar,
        secondary = muted,
        secondaryForeground = pick(n(0.21f), dimText(0.985f)),
        muted = muted,
        mutedForeground = pick(dimText(0.52f), dimText(0.725f)),
        countForeground = pick(dimText(0.42f), dimText(0.8f)),
        surface1 = card,
        surface2 = pick(n(0.955f), n(0.235f)),
        surface3 = pick(n(0.93f), n(0.245f)),
        surface4 = pick(n(0.91f), n(0.295f)),
        border = border,
        input = pick(n(0.878f), white.atAlpha(0.15f)),
        ring = primary,
        dotColor = pick(n(0.82f), n(0.3f)),

        primary = primary,
        onPrimary = onPrimary,
        accentWash = primary.atAlpha(0.06f),
        accentTint = primary.atAlpha(0.12f),
        accentLine = primary.atAlpha(0.30f),
        shellSurface = sidebar.atAlpha(prefs.surfaceOpacity.coerceIn(80, 100) / 100f),
        shellBorder = primary.atAlpha(0.30f),

        destructive = pick(ok(0.577f, 0.245f, 27.325f), ok(0.704f, 0.191f, 22.216f)),
        onDestructive = nearWhite,
        success = pick(ok(0.52f, 0.14f, 148f), ok(0.72f, 0.15f, 148f)),
        onSuccess = pick(nearWhite, nearBlack),
        warning = pick(ok(0.52f, 0.13f, 68f), ok(0.8f, 0.13f, 75f)),
        onWarning = pick(nearWhite, nearBlack),
        info = pick(ok(0.55f, 0.12f, 255f), ok(0.72f, 0.12f, 255f)),
        onInfo = pick(nearWhite, nearBlack),
        starHighlight = pick(ok(0.76f, 0.18f, 90f), ok(0.84f, 0.17f, 95f)),
        volumeRead = pick(ok(0.6f, 0.16f, 148f), ok(0.7f, 0.16f, 148f)),
        volumeReading = pick(ok(0.74f, 0.15f, 72f), ok(0.78f, 0.14f, 75f)),
        volumeUnread = pick(n(0.855f, lifted = false), n(0.315f, lifted = false)),
        volumeMissing = pick(ok(0.58f, 0.22f, 27f), ok(0.7f, 0.19f, 25f)),
        scoreRed = pick(ok(0.55f, 0.19f, 25f), ok(0.72f, 0.18f, 25f)),
        scoreOrange = pick(ok(0.55f, 0.16f, 55f), ok(0.78f, 0.15f, 55f)),
        scoreYellow = pick(ok(0.54f, 0.13f, 95f), ok(0.85f, 0.15f, 95f)),
        scoreGreen = pick(ok(0.5f, 0.15f, 148f), ok(0.78f, 0.16f, 148f)),
        pillSuccess = pick(ok(0.58f, 0.15f, 150f), ok(0.75f, 0.13f, 150f)),
        pillWarning = pick(ok(0.62f, 0.14f, 70f), ok(0.78f, 0.13f, 70f)),
        pillInfo = pick(ok(0.55f, 0.16f, 245f), ok(0.72f, 0.14f, 245f)),

        formatEbook = pick(ok(0.58f, 0.16f, 263f), ok(0.72f, 0.16f, 263f)),
        formatKindle = pick(ok(0.6f, 0.13f, 175f), ok(0.76f, 0.12f, 175f)),
        formatDocument = pick(ok(0.58f, 0.17f, 25f), ok(0.7f, 0.17f, 25f)),
        formatComic = pick(ok(0.62f, 0.14f, 95f), ok(0.78f, 0.13f, 95f)),
        formatAudio = pick(ok(0.56f, 0.15f, 150f), ok(0.74f, 0.14f, 150f)),
        formatOther = ok(0.62f, 0.02f, h),

        dashCard = dashCard,
        dashCardBorder = primary.atAlpha(0.40f),
        cardRow = muted.over(dashCard, 0.20f),
        cardRowPressed = muted.over(dashCard, 0.40f),
        iconBox = muted.over(dashCard, 0.50f),
        coverSurface = foreground.over(background, 0.12f),
        overlayPill = Color.Black.atAlpha(0.60f),
        statusDisc = Color(0xE61C1D22),
        coverProgress = primary.atAlpha(0.70f),
        coverProgressRead = Color(0xFF00C950).atAlpha(0.80f),
        flame = Color(0xFFFF6900),

        pillFormats = if (dark) pillFormatsDark else pillFormatsLight,
    )
}

/** `--pill-format-*` in light mode (tokens.css). */
private val pillFormatsLight: Map<String, Color> by lazy {
    mapOf(
        "default" to oklch(0.55f, 0.02f, 260f), "epub" to oklch(0.55f, 0.14f, 150f),
        "kepub" to oklch(0.55f, 0.12f, 178f), "mobi" to oklch(0.55f, 0.18f, 275f),
        "azw3" to oklch(0.53f, 0.11f, 185f), "azw" to oklch(0.55f, 0.14f, 235f),
        "fb2" to oklch(0.55f, 0.17f, 355f), "djvu" to oklch(0.55f, 0.04f, 255f),
        "txt" to oklch(0.55f, 0.03f, 60f), "pdf" to oklch(0.55f, 0.17f, 25f),
        "cbz" to oklch(0.55f, 0.16f, 260f), "cbr" to oklch(0.58f, 0.15f, 55f),
        "cb7" to oklch(0.55f, 0.19f, 300f), "cbx" to oklch(0.55f, 0.17f, 325f),
        "m4b" to oklch(0.58f, 0.13f, 75f), "m4a" to oklch(0.58f, 0.13f, 95f),
        "mp3" to oklch(0.56f, 0.15f, 145f), "opus" to oklch(0.55f, 0.11f, 205f),
        "ogg" to oklch(0.56f, 0.15f, 130f), "flac" to oklch(0.55f, 0.13f, 165f),
    )
}

/** `--pill-format-*` in dark mode (the later block of tokens.css, which wins). */
private val pillFormatsDark: Map<String, Color> by lazy {
    mapOf(
        "default" to oklch(0.75f, 0.02f, 260f), "epub" to oklch(0.75f, 0.13f, 150f),
        "kepub" to oklch(0.75f, 0.11f, 178f), "mobi" to oklch(0.72f, 0.16f, 275f),
        "azw3" to oklch(0.76f, 0.11f, 185f), "azw" to oklch(0.72f, 0.13f, 235f),
        "fb2" to oklch(0.73f, 0.15f, 355f), "djvu" to oklch(0.75f, 0.04f, 255f),
        "txt" to oklch(0.76f, 0.03f, 60f), "pdf" to oklch(0.72f, 0.15f, 25f),
        "cbz" to oklch(0.72f, 0.14f, 260f), "cbr" to oklch(0.76f, 0.13f, 55f),
        "cb7" to oklch(0.72f, 0.17f, 300f), "cbx" to oklch(0.73f, 0.15f, 325f),
        "m4b" to oklch(0.79f, 0.13f, 75f), "m4a" to oklch(0.8f, 0.13f, 95f),
        "mp3" to oklch(0.78f, 0.15f, 145f), "opus" to oklch(0.76f, 0.11f, 205f),
        "ogg" to oklch(0.78f, 0.15f, 130f), "flac" to oklch(0.75f, 0.13f, 165f),
    )
}
