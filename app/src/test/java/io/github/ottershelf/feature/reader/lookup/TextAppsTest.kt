package io.github.ottershelf.feature.reader.lookup

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Which apps get a button, and what each is handed (Robolectric's package manager). */
@RunWith(AndroidJUnit4::class)
class TextAppsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun handler(pkg: String, cls: String, label: String) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = cls
            exported = true
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        }
        nonLocalizedLabel = label
    }

    private val processText = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")

    @Test
    fun oneButtonPerHandlerAndTranslateOnlyForAppsWithoutOne() {
        val pm = shadowOf(context.packageManager)
        pm.addResolveInfoForIntent(processText, handler("com.google.android.apps.translate", "com.google.ProcessText", "Translate"))
        pm.addResolveInfoForIntent(processText, handler("com.deepl.mobiletranslator", "com.deepl.Process", "DeepL"))
        // The app itself is never offered.
        pm.addResolveInfoForIntent(processText, handler(context.packageName, "io.github.ottershelf.Self", "Self"))
        // Google Translate has a PROCESS_TEXT button already; the other translator only does TRANSLATE.
        pm.addResolveInfoForIntent(Intent(Intent.ACTION_TRANSLATE), handler("com.google.android.apps.translate", "com.google.Translate", "Translate"))
        pm.addResolveInfoForIntent(Intent(Intent.ACTION_TRANSLATE), handler("org.example.offline", "org.example.Translate", "Offline Translator"))

        val apps = TextApps.query(context)
        assertEquals(listOf("DeepL", "Translate", "Offline Translator"), apps.map { it.label })
        assertEquals(listOf(TextApp.Kind.ProcessText, TextApp.Kind.ProcessText, TextApp.Kind.Translate), apps.map { it.kind })
        assertTrue(apps.none { it.packageName == context.packageName })
    }

    @Test
    fun nothingInstalledIsNoButtons() {
        assertTrue(TextApps.query(context).isEmpty())
    }

    @Test
    fun processTextIsHandedTheTextReadOnly() {
        val app = TextApp("com.deepl.mobiletranslator", "com.deepl.Process", "DeepL")
        val intent = TextApps.intent(app, "Lauriston")
        assertEquals(Intent.ACTION_PROCESS_TEXT, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("Lauriston", intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false))
        assertEquals("com.deepl.mobiletranslator", intent.component?.packageName)
        assertEquals("com.deepl.Process", intent.component?.className)
    }

    @Test
    fun translateIsHandedTheTextAsMatched() {
        val typed = TextApps.intent(TextApp("a", "a.T", "A", kind = TextApp.Kind.Translate, typed = true), "skein")
        assertEquals(Intent.ACTION_TRANSLATE, typed.action)
        assertEquals("text/plain", typed.type)
        assertEquals("skein", typed.getStringExtra(Intent.EXTRA_TEXT))
        val plain = TextApps.intent(TextApp("b", "b.T", "B", kind = TextApp.Kind.Translate, typed = false), "skein")
        assertNull(plain.type)
        assertEquals("b.T", plain.component?.className)
    }

    @Test
    fun onlyWikimediaLinksAreOpened() {
        val app = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
        TextApps.openLink(context, "https://evil.example/")
        assertNull(app.nextStartedActivity)
        TextApps.openLink(context, "https://en.wiktionary.org/wiki/walk#English")
        assertEquals("https://en.wiktionary.org/wiki/walk#English", app.nextStartedActivity?.data?.toString())
    }
}
