package io.github.ottershelf.core.theme

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The three server calls [ThemeRepository] makes, implemented by the Api. An interface only so the
 * sync rules can be unit-tested against a fake server.
 */
interface ThemeRemote {
    /**
     * `GET user-preferences/theme`: the stored preferences object as the server returns it (it does
     * not validate what it hands back), or null when the account has none yet.
     */
    suspend fun themePreferences(): JsonObject?

    /** `PUT user-preferences/theme` with `{ "settings": ... }`: replaces the whole stored object (204). */
    suspend fun saveThemePreferences(body: ThemePreferencesBody)

    /** `PATCH users/me/theme-storage-mode` with `{ "sync": ... }` (204). Refused for demo accounts. */
    suspend fun setThemeStorageMode(body: ThemeStorageModeBody)
}

/**
 * The `PUT user-preferences/theme` body. The server's DTO allows only `settings`, and its zod schema
 * is strict: these keys only (`surfaceOpacity` and the web-only three optional), ids from the enums,
 * integers in range. [ThemePrefs.toBody] always produces a valid one.
 */
@Serializable
data class ThemePreferencesBody(val settings: ThemePreferencesDto)

@Serializable
data class ThemePreferencesDto(
    val theme: String,
    val accent: String,
    val radius: String,
    val background: String,
    val brightness: Int,
    val surfaceOpacity: Int,
    /**
     * BookOrbit 3.5's web-only settings (shell style, surface palette, glow reach): this app doesn't
     * show or change them, but the PUT replaces the whole stored object, so they go back as the
     * server last sent them ([WebOnlyTheme]). Left out when unknown (null), which older servers
     * need: their schema refuses the keys.
     */
    val style: String? = null,
    val surfacePalette: String? = null,
    val glowReach: Int? = null,
)

/** The stored theme keys only the web edits, kept from the last `GET` to send back unchanged. */
data class WebOnlyTheme(val style: String? = null, val surfacePalette: String? = null, val glowReach: Int? = null) {
    companion object {
        /** From the server's stored object: each key only if it has the type the schema wants. */
        fun from(raw: JsonObject) = WebOnlyTheme(raw.string("style"), raw.string("surfacePalette"), raw.integer("glowReach"))
    }
}

/** `UpdateThemeStorageModeDto`: `{ "sync": boolean }`. */
@Serializable
data class ThemeStorageModeBody(val sync: Boolean)

fun ThemePrefs.toBody(webOnly: WebOnlyTheme = WebOnlyTheme()): ThemePreferencesBody = clamped().let {
    ThemePreferencesBody(
        ThemePreferencesDto(
            theme = it.theme.id,
            accent = it.accent.id,
            radius = it.radius.id,
            background = it.background.id,
            brightness = it.brightness,
            surfaceOpacity = it.surfaceOpacity,
            style = webOnly.style,
            surfacePalette = webOnly.surfacePalette,
            glowReach = webOnly.glowReach,
        )
    )
}

/**
 * The web's `sanitizeServerPrefs` (useThemeSync.ts) applied on top of [current]: every field that is
 * a known id or an integer in range replaces the current value; anything else is ignored, field by
 * field. The server returns whatever was stored, so nothing is trusted.
 */
fun ThemePrefs.withServerFields(raw: JsonObject): ThemePrefs {
    var out = this
    raw.string("theme")?.let(ThemeMode::fromId)?.let { out = out.copy(theme = it) }
    raw.string("accent")?.let(Accent::fromId)?.let { out = out.copy(accent = it) }
    raw.string("radius")?.let(Radius::fromId)?.let { out = out.copy(radius = it) }
    raw.string("background")?.let(ThemeBackground::fromId)?.let { out = out.copy(background = it) }
    raw.integer("brightness")
        ?.takeIf { it in ThemePrefs.BRIGHTNESS_MIN..ThemePrefs.BRIGHTNESS_MAX }
        ?.let { out = out.copy(brightness = it) }
    raw.integer("surfaceOpacity")
        ?.takeIf { it in ThemePrefs.SURFACE_OPACITY_MIN..ThemePrefs.SURFACE_OPACITY_MAX }
        ?.let { out = out.copy(surfaceOpacity = it) }
    return out
}

private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

private fun JsonObject.string(key: String): String? = primitive(key)?.takeIf { it.isString }?.content

/** A JSON number that is a whole number (JavaScript's `Number.isInteger`); not a string, not a boolean. */
private fun JsonObject.integer(key: String): Int? {
    val p = primitive(key)?.takeIf { !it.isString && it.booleanOrNull == null } ?: return null
    val value = p.doubleOrNull ?: return null
    if (value != Math.rint(value) || value < Int.MIN_VALUE || value > Int.MAX_VALUE) return null
    return value.toInt()
}
