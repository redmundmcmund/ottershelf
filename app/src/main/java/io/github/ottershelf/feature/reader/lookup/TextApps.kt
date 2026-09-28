package io.github.ottershelf.feature.reader.lookup

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * The installed apps that take text (the manifest's `<queries>` makes them visible): one button per
 * `ACTION_PROCESS_TEXT` activity (Google Translate, DeepL, a dictionary app; the text read-only),
 * then `ACTION_TRANSLATE` handlers of apps that don't already have one. Nothing installed, nothing
 * shown. Call [query] off the main thread (icons are drawn to bitmaps).
 */
internal object TextApps {

    fun query(context: Context): List<TextApp> {
        val pm = context.packageManager
        val own = context.packageName
        val size = (context.resources.displayMetrics.density * ICON_DP).toInt().coerceAtLeast(24)
        fun ResolveInfo.toApp(kind: TextApp.Kind, typed: Boolean): TextApp? {
            val info = activityInfo ?: return null
            if (info.packageName == own) return null
            val label = runCatching { loadLabel(pm).toString() }.getOrNull()?.trim().orEmpty().ifEmpty { info.packageName }
            val icon = runCatching { loadIcon(pm)?.toBitmap(size, size)?.asImageBitmap() }.getOrNull()
            return TextApp(info.packageName, info.name, label, icon, kind, typed)
        }
        val flags = PackageManager.ResolveInfoFlags.of(0)
        val process = runCatching { pm.queryIntentActivities(processTextIntent(), flags) }.getOrDefault(emptyList())
            .mapNotNull { it.toApp(TextApp.Kind.ProcessText, typed = true) }
            .distinctBy { it.key }
            .sortedBy { it.label.lowercase() }
        val translateTyped = runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_TRANSLATE).setType(TEXT), flags) }.getOrDefault(emptyList())
            .mapNotNull { it.toApp(TextApp.Kind.Translate, typed = true) }
        val translatePlain = runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_TRANSLATE), flags) }.getOrDefault(emptyList())
            .mapNotNull { it.toApp(TextApp.Kind.Translate, typed = false) }
        val covered = process.map { it.packageName }.toSet()
        val translate = (translateTyped + translatePlain)
            .distinctBy { it.packageName to it.className }
            .filter { it.packageName !in covered }
            .sortedBy { it.label.lowercase() }
        return process + translate
    }

    /** The intent that hands [text] to [app]; the app's own activity, explicitly. */
    fun intent(app: TextApp, text: String): Intent = when (app.kind) {
        TextApp.Kind.ProcessText -> processTextIntent()
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        TextApp.Kind.Translate -> Intent(Intent.ACTION_TRANSLATE)
            .apply { if (app.typed) type = TEXT }
            .putExtra(Intent.EXTRA_TEXT, text)
    }.setClassName(app.packageName, app.className)

    fun launch(context: Context, app: TextApp, text: String) {
        try {
            context.startActivity(intent(app, text).fromAnyContext(context))
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }

    /** "Open in Wiktionary / Wikipedia": the browser, for a Wikimedia https link only. */
    fun openLink(context: Context, url: String) {
        if (!LookupHttp.isWikimediaUrl(url)) return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).fromAnyContext(context))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun processTextIntent() = Intent(Intent.ACTION_PROCESS_TEXT).setType(TEXT)

    /** Started from outside an activity (never from the reader itself), a new task is needed. */
    private fun Intent.fromAnyContext(context: Context): Intent = apply {
        var c: Context? = context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        if (c !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private const val TEXT = "text/plain"
    private const val ICON_DP = 24
}
