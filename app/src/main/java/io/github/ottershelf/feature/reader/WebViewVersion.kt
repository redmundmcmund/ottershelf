package io.github.ottershelf.feature.reader

import android.content.Context
import androidx.webkit.WebViewCompat

/**
 * Whether the phone's WebView can run the reader. foliate's EPUB code calls Object.groupBy and
 * Map.groupBy (Chrome 117). Phones from Android 12 on update their WebView through Google Play,
 * but one that never has (an emulator image, a phone kept offline) fails on every book with
 * "Object.groupBy is not a function"; the reader then says to update Android System WebView.
 */
internal object WebViewVersion {
    const val MIN_MAJOR = 117

    /** The installed WebView's version when it is older than [MIN_MAJOR]; null when new enough or unknown. */
    fun outdated(context: Context): String? {
        val version = runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.versionName }.getOrNull() ?: return null
        return version.takeIf { isOutdated(it) }
    }

    /** [version] ("91.0.4472.114") is below [MIN_MAJOR]; false when it doesn't start with a number. */
    fun isOutdated(version: String): Boolean {
        val major = version.takeWhile(Char::isDigit).toIntOrNull() ?: return false
        return major < MIN_MAJOR
    }
}
