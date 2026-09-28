package io.github.ottershelf.core.theme

/**
 * The web app's six appearance settings (packages/types/src/theme.ts, client/src/stores/theme.ts),
 * with its defaults. Ids are the web's, so the same object goes to and from
 * `api/v1/user-preferences/theme` unchanged. Labels are the web's English names (data mirrored from
 * theme-accent-meta.ts, like ReadStatus's labels).
 *
 * Pure data: ui.theme turns it into colours, shapes and a background pattern.
 */
data class ThemePrefs(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: Accent = Accent.DEFAULT,
    val radius: Radius = Radius.DEFAULT,
    val background: ThemeBackground = ThemeBackground.DEFAULT,
    /** Dark-mode surface lift, 0..100 (`--bg-lift = brightness / 100 * 0.12`). No effect in light mode. */
    val brightness: Int = DEFAULT_BRIGHTNESS,
    /** Opacity of the shell surfaces (toolbar, drawer), 80..100 percent. */
    val surfaceOpacity: Int = DEFAULT_SURFACE_OPACITY,
) {
    /** The same preferences with the numbers clamped to what the server accepts. */
    fun clamped(): ThemePrefs = copy(
        brightness = brightness.coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX),
        surfaceOpacity = surfaceOpacity.coerceIn(SURFACE_OPACITY_MIN, SURFACE_OPACITY_MAX),
    )

    companion object {
        const val BRIGHTNESS_MIN = 0
        const val BRIGHTNESS_MAX = 100
        /** The web's DEFAULT_SURFACE_BRIGHTNESS. Its Appearance "Reset" button sets 0, not this. */
        const val DEFAULT_BRIGHTNESS = 35
        const val SURFACE_OPACITY_MIN = 80
        const val SURFACE_OPACITY_MAX = 100
        const val DEFAULT_SURFACE_OPACITY = 92
    }
}

/** `theme`: light, dark, or follow the system (the default). */
enum class ThemeMode(val id: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> systemDark
    }

    companion object {
        fun fromId(id: String?): ThemeMode? = entries.firstOrNull { it.id == id }
    }
}

enum class AccentTone { Vivid, Pastel }

/**
 * `accent`: the 64 web accents, in the picker's order (vivid/pastel pairs, 32 pairs; the picker
 * shows them in four rows of 16). Each sets the tint hue of every neutral surface and the primary
 * colour: oklch([lightL] [lightC] [hue]) in light mode, oklch([darkL] [darkC] [hue]) in dark mode
 * (client/src/assets/theme/accents.css). Text on the primary is near-white in light mode when
 * [whiteOnLight], near-black otherwise, and always near-black in dark mode.
 *
 * [WHITE] and [GREY] are neutral: no tint on the surfaces, and a fixed sidebar lightness.
 */
enum class Accent(
    val id: String,
    val label: String,
    val tone: AccentTone,
    val hue: Float,
    val lightL: Float,
    val lightC: Float,
    val darkL: Float,
    val darkC: Float,
    val whiteOnLight: Boolean,
) {
    WHITE("white", "White", AccentTone.Vivid, hue = 0f, lightL = 0.2f, lightC = 0f, darkL = 0.985f, darkC = 0f, whiteOnLight = true),
    GREY("grey", "Grey", AccentTone.Pastel, hue = 0f, lightL = 0.55f, lightC = 0f, darkL = 0.75f, darkC = 0f, whiteOnLight = false),
    ORANGE("orange", "Orange", AccentTone.Vivid, hue = 42f, lightL = 0.64f, lightC = 0.22f, darkL = 0.78f, darkC = 0.18f, whiteOnLight = true),
    PEACH("peach", "Peach", AccentTone.Pastel, hue = 42f, lightL = 0.76f, lightC = 0.11f, darkL = 0.84f, darkC = 0.1f, whiteOnLight = false),
    COPPER("copper", "Copper", AccentTone.Vivid, hue = 56f, lightL = 0.65f, lightC = 0.19f, darkL = 0.79f, darkC = 0.16f, whiteOnLight = false),
    SAND("sand", "Sand", AccentTone.Pastel, hue = 56f, lightL = 0.77f, lightC = 0.095f, darkL = 0.85f, darkC = 0.09f, whiteOnLight = false),
    AMBER("amber", "Amber", AccentTone.Vivid, hue = 70f, lightL = 0.72f, lightC = 0.17f, darkL = 0.82f, darkC = 0.17f, whiteOnLight = false),
    BUTTER("butter", "Butter", AccentTone.Pastel, hue = 70f, lightL = 0.82f, lightC = 0.085f, darkL = 0.88f, darkC = 0.09f, whiteOnLight = false),
    MARIGOLD("marigold", "Marigold", AccentTone.Vivid, hue = 82f, lightL = 0.76f, lightC = 0.18f, darkL = 0.84f, darkC = 0.15f, whiteOnLight = false),
    FLAX("flax", "Flax", AccentTone.Pastel, hue = 82f, lightL = 0.82f, lightC = 0.09f, darkL = 0.88f, darkC = 0.08f, whiteOnLight = false),
    YELLOW("yellow", "Yellow", AccentTone.Vivid, hue = 95f, lightL = 0.75f, lightC = 0.18f, darkL = 0.84f, darkC = 0.16f, whiteOnLight = false),
    LEMON("lemon", "Lemon", AccentTone.Pastel, hue = 95f, lightL = 0.82f, lightC = 0.09f, darkL = 0.88f, darkC = 0.09f, whiteOnLight = false),
    CHARTREUSE("chartreuse", "Chartreuse", AccentTone.Vivid, hue = 106f, lightL = 0.76f, lightC = 0.2f, darkL = 0.83f, darkC = 0.17f, whiteOnLight = false),
    PEAR("pear", "Pear", AccentTone.Pastel, hue = 106f, lightL = 0.82f, lightC = 0.1f, darkL = 0.88f, darkC = 0.09f, whiteOnLight = false),
    ACID_GREEN("acid-green", "Acid Green", AccentTone.Vivid, hue = 112f, lightL = 0.7f, lightC = 0.2f, darkL = 0.82f, darkC = 0.17f, whiteOnLight = false),
    PISTACHIO("pistachio", "Pistachio", AccentTone.Pastel, hue = 112f, lightL = 0.8f, lightC = 0.11f, darkL = 0.88f, darkC = 0.09f, whiteOnLight = false),
    LIME("lime", "Lime", AccentTone.Vivid, hue = 118f, lightL = 0.64f, lightC = 0.2f, darkL = 0.77f, darkC = 0.18f, whiteOnLight = false),
    CELADON("celadon", "Celadon", AccentTone.Pastel, hue = 118f, lightL = 0.76f, lightC = 0.1f, darkL = 0.83f, darkC = 0.1f, whiteOnLight = false),
    WASABI("wasabi", "Wasabi", AccentTone.Vivid, hue = 130f, lightL = 0.68f, lightC = 0.19f, darkL = 0.79f, darkC = 0.16f, whiteOnLight = false),
    SPROUT("sprout", "Sprout", AccentTone.Pastel, hue = 130f, lightL = 0.8f, lightC = 0.095f, darkL = 0.85f, darkC = 0.09f, whiteOnLight = false),
    GREEN("green", "Green", AccentTone.Vivid, hue = 142f, lightL = 0.527f, lightC = 0.18f, darkL = 0.72f, darkC = 0.18f, whiteOnLight = true),
    SAGE("sage", "Sage", AccentTone.Pastel, hue = 142f, lightL = 0.68f, lightC = 0.09f, darkL = 0.78f, darkC = 0.1f, whiteOnLight = false),
    MALACHITE("malachite", "Malachite", AccentTone.Vivid, hue = 152f, lightL = 0.56f, lightC = 0.18f, darkL = 0.73f, darkC = 0.15f, whiteOnLight = true),
    ALOE("aloe", "Aloe", AccentTone.Pastel, hue = 152f, lightL = 0.68f, lightC = 0.09f, darkL = 0.79f, darkC = 0.08f, whiteOnLight = false),
    JADE("jade", "Jade", AccentTone.Vivid, hue = 155f, lightL = 0.62f, lightC = 0.2f, darkL = 0.78f, darkC = 0.17f, whiteOnLight = false),
    SEA_GLASS("sea-glass", "Sea Glass", AccentTone.Pastel, hue = 155f, lightL = 0.74f, lightC = 0.1f, darkL = 0.84f, darkC = 0.09f, whiteOnLight = false),
    EMERALD("emerald", "Emerald", AccentTone.Vivid, hue = 162f, lightL = 0.52f, lightC = 0.17f, darkL = 0.72f, darkC = 0.15f, whiteOnLight = true),
    MINT("mint", "Mint", AccentTone.Pastel, hue = 162f, lightL = 0.68f, lightC = 0.085f, darkL = 0.78f, darkC = 0.08f, whiteOnLight = false),
    VIRIDIAN("viridian", "Viridian", AccentTone.Vivid, hue = 171f, lightL = 0.54f, lightC = 0.17f, darkL = 0.73f, darkC = 0.14f, whiteOnLight = true),
    FOAM("foam", "Foam", AccentTone.Pastel, hue = 171f, lightL = 0.68f, lightC = 0.085f, darkL = 0.79f, darkC = 0.08f, whiteOnLight = false),
    TEAL("teal", "Teal", AccentTone.Vivid, hue = 180f, lightL = 0.52f, lightC = 0.18f, darkL = 0.74f, darkC = 0.16f, whiteOnLight = true),
    SEAFOAM("seafoam", "Seafoam", AccentTone.Pastel, hue = 180f, lightL = 0.68f, lightC = 0.09f, darkL = 0.8f, darkC = 0.09f, whiteOnLight = false),
    TURQUOISE("turquoise", "Turquoise", AccentTone.Vivid, hue = 188f, lightL = 0.58f, lightC = 0.18f, darkL = 0.75f, darkC = 0.15f, whiteOnLight = true),
    AQUA("aqua", "Aqua", AccentTone.Pastel, hue = 188f, lightL = 0.7f, lightC = 0.09f, darkL = 0.81f, darkC = 0.08f, whiteOnLight = false),
    CYAN("cyan", "Cyan", AccentTone.Vivid, hue = 197f, lightL = 0.52f, lightC = 0.2f, darkL = 0.75f, darkC = 0.17f, whiteOnLight = true),
    POWDER("powder", "Powder", AccentTone.Pastel, hue = 197f, lightL = 0.68f, lightC = 0.1f, darkL = 0.81f, darkC = 0.09f, whiteOnLight = false),
    ELECTRIC_BLUE("electric-blue", "Electric Blue", AccentTone.Vivid, hue = 230f, lightL = 0.54f, lightC = 0.24f, darkL = 0.73f, darkC = 0.19f, whiteOnLight = true),
    BABY_BLUE("baby-blue", "Baby Blue", AccentTone.Pastel, hue = 230f, lightL = 0.68f, lightC = 0.12f, darkL = 0.79f, darkC = 0.1f, whiteOnLight = false),
    ULTRAMARINE("ultramarine", "Ultramarine", AccentTone.Vivid, hue = 247f, lightL = 0.5f, lightC = 0.28f, darkL = 0.7f, darkC = 0.22f, whiteOnLight = true),
    CORNFLOWER("cornflower", "Cornflower", AccentTone.Pastel, hue = 247f, lightL = 0.68f, lightC = 0.14f, darkL = 0.76f, darkC = 0.12f, whiteOnLight = false),
    BLUE("blue", "Blue", AccentTone.Vivid, hue = 263f, lightL = 0.487f, lightC = 0.25f, darkL = 0.72f, darkC = 0.2f, whiteOnLight = true),
    PERIWINKLE("periwinkle", "Periwinkle", AccentTone.Pastel, hue = 263f, lightL = 0.68f, lightC = 0.125f, darkL = 0.78f, darkC = 0.11f, whiteOnLight = false),
    IRIS("iris", "Iris", AccentTone.Vivid, hue = 270f, lightL = 0.52f, lightC = 0.25f, darkL = 0.72f, darkC = 0.21f, whiteOnLight = true),
    BLUEBELL("bluebell", "Bluebell", AccentTone.Pastel, hue = 270f, lightL = 0.68f, lightC = 0.125f, darkL = 0.78f, darkC = 0.12f, whiteOnLight = false),
    INDIGO("indigo", "Indigo", AccentTone.Vivid, hue = 276f, lightL = 0.51f, lightC = 0.26f, darkL = 0.72f, darkC = 0.22f, whiteOnLight = true),
    WISTERIA("wisteria", "Wisteria", AccentTone.Pastel, hue = 276f, lightL = 0.68f, lightC = 0.13f, darkL = 0.78f, darkC = 0.12f, whiteOnLight = false),
    PURPLE("purple", "Purple", AccentTone.Vivid, hue = 284f, lightL = 0.51f, lightC = 0.25f, darkL = 0.72f, darkC = 0.21f, whiteOnLight = true),
    THISTLE("thistle", "Thistle", AccentTone.Pastel, hue = 284f, lightL = 0.68f, lightC = 0.125f, darkL = 0.78f, darkC = 0.12f, whiteOnLight = false),
    VIOLET("violet", "Violet", AccentTone.Vivid, hue = 292f, lightL = 0.491f, lightC = 0.27f, darkL = 0.72f, darkC = 0.23f, whiteOnLight = true),
    LAVENDER("lavender", "Lavender", AccentTone.Pastel, hue = 292f, lightL = 0.68f, lightC = 0.135f, darkL = 0.78f, darkC = 0.13f, whiteOnLight = false),
    AMETHYST("amethyst", "Amethyst", AccentTone.Vivid, hue = 302f, lightL = 0.54f, lightC = 0.24f, darkL = 0.73f, darkC = 0.2f, whiteOnLight = true),
    MAUVE("mauve", "Mauve", AccentTone.Pastel, hue = 302f, lightL = 0.68f, lightC = 0.12f, darkL = 0.79f, darkC = 0.11f, whiteOnLight = false),
    FUCHSIA("fuchsia", "Fuchsia", AccentTone.Vivid, hue = 312f, lightL = 0.56f, lightC = 0.27f, darkL = 0.75f, darkC = 0.22f, whiteOnLight = true),
    ORCHID("orchid", "Orchid", AccentTone.Pastel, hue = 312f, lightL = 0.68f, lightC = 0.135f, darkL = 0.81f, darkC = 0.12f, whiteOnLight = false),
    PINK("pink", "Pink", AccentTone.Vivid, hue = 328f, lightL = 0.56f, lightC = 0.26f, darkL = 0.75f, darkC = 0.22f, whiteOnLight = true),
    BLUSH("blush", "Blush", AccentTone.Pastel, hue = 328f, lightL = 0.68f, lightC = 0.13f, darkL = 0.81f, darkC = 0.12f, whiteOnLight = false),
    RASPBERRY("raspberry", "Raspberry", AccentTone.Vivid, hue = 344f, lightL = 0.56f, lightC = 0.23f, darkL = 0.74f, darkC = 0.19f, whiteOnLight = true),
    ROSE_QUARTZ("rose-quartz", "Rose Quartz", AccentTone.Pastel, hue = 344f, lightL = 0.68f, lightC = 0.115f, darkL = 0.8f, darkC = 0.1f, whiteOnLight = false),
    SCARLET("scarlet", "Scarlet", AccentTone.Vivid, hue = 2f, lightL = 0.58f, lightC = 0.25f, darkL = 0.74f, darkC = 0.2f, whiteOnLight = true),
    ROSEWATER("rosewater", "Rosewater", AccentTone.Pastel, hue = 2f, lightL = 0.7f, lightC = 0.125f, darkL = 0.8f, darkC = 0.11f, whiteOnLight = false),
    ROSE("rose", "Rose", AccentTone.Vivid, hue = 15f, lightL = 0.57f, lightC = 0.24f, darkL = 0.73f, darkC = 0.2f, whiteOnLight = true),
    CORAL("coral", "Coral", AccentTone.Pastel, hue = 15f, lightL = 0.69f, lightC = 0.12f, darkL = 0.79f, darkC = 0.11f, whiteOnLight = false),
    VERMILION("vermilion", "Vermilion", AccentTone.Vivid, hue = 28f, lightL = 0.62f, lightC = 0.24f, darkL = 0.77f, darkC = 0.19f, whiteOnLight = true),
    SALMON("salmon", "Salmon", AccentTone.Pastel, hue = 28f, lightL = 0.74f, lightC = 0.12f, darkL = 0.83f, darkC = 0.1f, whiteOnLight = false);

    /** White and grey: untinted surfaces and a fixed sidebar lightness. */
    val isNeutral: Boolean get() = this == WHITE || this == GREY

    companion object {
        val DEFAULT = BLUE

        fun fromId(id: String?): Accent? = entries.firstOrNull { it.id == id }

        /** The picker's 32 (vivid, pastel) pairs, in order. */
        val pairs: List<Pair<Accent, Accent>> by lazy { entries.chunked(2) { it[0] to it[1] } }

        /**
         * The picker's four rows of 16: the vivid then pastel accents of the first 16 pairs, then
         * the same for the last 16 (theme-accent-meta.ts ACCENT_ROWS).
         */
        val rows: List<List<Accent>> by lazy {
            val (first, second) = pairs.take(16) to pairs.drop(16)
            listOf(first.map { it.first }, first.map { it.second }, second.map { it.first }, second.map { it.second })
        }
    }
}

/** `radius`: the base corner radius in dp (the web's px), from which every other size derives. */
enum class Radius(val id: String, val label: String, val baseDp: Float) {
    SHARP("sharp", "Sharp", 0f),
    DEFAULT("default", "Default", 10f),
    ROUNDED("rounded", "Rounded", 20f),
    PILL("pill", "Pill", 40f);

    companion object {
        fun fromId(id: String?): Radius? = entries.firstOrNull { it.id == id }
    }
}

/** `background`: the page pattern, in the web picker's groups and order (stores/theme.ts). */
enum class ThemeBackground(val id: String, val label: String) {
    NONE("none", "None"),
    DOTS("dots", "Dots"),
    CROSS("cross", "Cross"),
    MILLIMETER("millimeter", "Millimeter"),
    BLUEPRINT("blueprint", "Blueprint"),
    BRUSHED("brushed", "Brushed"),
    SCANLINES("scanlines", "Scanlines"),
    VINYL("vinyl", "Vinyl"),
    CARBON("carbon", "Carbon"),
    PERFORATED("perforated", "Perforated"),
    AURORA("aurora", "Aurora"),
    HORIZON("horizon", "Horizon"),
    GLOW("glow", "Glow"),
    MESH("mesh", "Mesh"),
    ELEVATION("elevation", "Elevation"),
    PRISM("prism", "Prism"),
    SPECTRUM("spectrum", "Spectrum"),
    SPECTRUM_X("spectrum-x", "Spectrum X"),
    SPECTRUM_PLUS("spectrum-plus", "Spectrum Plus"),
    ECLIPSE("eclipse", "Eclipse");

    companion object {
        /** What the web uses when nothing is stored. */
        val DEFAULT = VINYL

        /** What the web falls back to when the stored value isn't a known id. */
        val INVALID_FALLBACK = DOTS

        fun fromId(id: String?): ThemeBackground? = entries.firstOrNull { it.id == id }
    }
}
