package io.github.ottershelf.feature.book

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Links in a book's description (the book file's or a provider's HTML): web and mail only. */
@RunWith(AndroidJUnit4::class) // for android.net.Uri
class DescriptionLinkTest {

    /** The screen's context, recording what it's asked to open (or failing as told). */
    private class Screen : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        val started = mutableListOf<Intent>()
        var failure: RuntimeException? = null

        override fun startActivity(intent: Intent) {
            failure?.let { throw it }
            started += intent
        }
    }

    @Test
    fun webAndMailLinksOpenInAnAppThatTakesBrowserLinks() {
        val screen = Screen()
        assertTrue(openDescriptionLink(screen, "https://example.org/series"))
        assertTrue(openDescriptionLink(screen, " HTTP://Example.org/a "))
        assertTrue(openDescriptionLink(screen, "mailto:author@example.org"))
        assertEquals(listOf("https", "http", "mailto"), screen.started.map { it.data?.scheme })
        screen.started.forEach {
            assertEquals(Intent.ACTION_VIEW, it.action)
            assertTrue(it.hasCategory(Intent.CATEGORY_BROWSABLE))
        }
    }

    @Test
    fun anyOtherSchemeIsIgnored() {
        val screen = Screen()
        listOf(
            "file:///sdcard/Download/x",
            "content://com.example.provider/secret",
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "market://details?id=com.example",
            "tel:123",
            "/relative/path",
            "",
        ).forEach { assertFalse(it, openDescriptionLink(screen, it)) }
        assertTrue(screen.started.isEmpty())
    }

    /** A tapped link goes to the description's own opener, not Compose's default (which opens any scheme). */
    @Test
    fun theDescriptionsLinksGoThroughItsOpener() {
        val opened = mutableListOf<String>()
        val text = htmlText("""<p>More at <a href="file:///sdcard/x">the site</a>.</p>""", Color.Blue) { opened += it }
        val link = text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Url
        assertEquals("file:///sdcard/x", link.url)
        link.linkInteractionListener!!.onClick(link)
        assertEquals(listOf("file:///sdcard/x"), opened)
    }

    @Test
    fun noAppOrAGuardedOneDoesNotCrash() {
        val screen = Screen()
        screen.failure = ActivityNotFoundException()
        assertFalse(openDescriptionLink(screen, "https://example.org"))
        screen.failure = SecurityException("permission")
        assertFalse(openDescriptionLink(screen, "https://example.org"))
    }
}
