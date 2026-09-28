package io.github.ottershelf

import android.content.ComponentName
import android.content.Context
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the merged manifest starts before Application.onCreate in every process (androidx.startup's
 * initializers): only what every process needs; the rest starts on demand. And nothing of Google's
 * (ML Kit, Play services, datatransport, Firebase) is declared at all.
 */
@RunWith(AndroidJUnit4::class)
class StartupManifestTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun startupInitializers(): Set<String> {
        val provider = context.packageManager.getProviderInfo(
            ComponentName(context, "androidx.startup.InitializationProvider"),
            PackageManager.GET_META_DATA,
        )
        return provider.metaData.keySet()
    }

    @Test
    fun workManagerStartsOnDemand() {
        val initializers = startupInitializers()
        assertFalse(initializers.toString(), "androidx.work.WorkManagerInitializer" in initializers)
        // The app then configures it itself, at its first use.
        assertTrue(Configuration.Provider::class.java.isAssignableFrom(OttershelfApp::class.java))
    }

    @Test
    fun emojiCompatNeverStarts() {
        val initializers = startupInitializers()
        assertFalse(initializers.toString(), "androidx.emoji2.text.EmojiCompatInitializer" in initializers)
    }

    @Test
    fun theOtherInitializersStay() {
        val initializers = startupInitializers()
        assertTrue(initializers.toString(), "androidx.lifecycle.ProcessLifecycleInitializer" in initializers)
        assertTrue(initializers.toString(), "androidx.profileinstaller.ProfileInstallerInitializer" in initializers)
    }

    @Test
    fun noGoogleComponentIsDeclared() {
        // ML Kit brought Google's datatransport (telemetry upload jobs) and its own init provider; the
        // barcode and page readers now are zxing-cpp and Tesseract, and nothing of Google's is left.
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        val components: List<ComponentInfo> = listOfNotNull(info.activities, info.services, info.providers, info.receivers).flatMap { it.asList() }
        val names = components.map { it.name }
        assertTrue(names.toString(), "io.github.ottershelf.core.tracking.TimerActionReceiver" in names)
        val google = names.filter { name -> GOOGLE_PACKAGES.any { name.startsWith(it) } }
        assertTrue(google.toString(), google.isEmpty())
    }

    private companion object {
        val GOOGLE_PACKAGES = listOf("com.google.mlkit.", "com.google.android.gms.", "com.google.android.datatransport.", "com.google.firebase.")
    }
}
