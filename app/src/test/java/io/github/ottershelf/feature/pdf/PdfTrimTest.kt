package io.github.ottershelf.feature.pdf

import android.content.ComponentCallbacks2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The PDF reader's page bitmaps go once the app is in the background, not on a screen lock ([TrimInBackground]). */
class PdfTrimTest {

    private fun clearsAt(level: Int): Boolean {
        var cleared = 0
        TrimInBackground { cleared++ }.onTrimMemory(level)
        return cleared == 1
    }

    @Test fun clearsInTheBackground() {
        assertTrue(clearsAt(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertTrue(clearsAt(60)) // TRIM_MEMORY_MODERATE (deprecated, older Android)
        assertTrue(clearsAt(80)) // TRIM_MEMORY_COMPLETE (deprecated, older Android)
    }

    @Test fun keepsThemWhileReading() {
        assertFalse(clearsAt(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)) // every screen lock
        assertFalse(clearsAt(5)) // TRIM_MEMORY_RUNNING_MODERATE
        assertFalse(clearsAt(10)) // TRIM_MEMORY_RUNNING_LOW
        assertFalse(clearsAt(15)) // TRIM_MEMORY_RUNNING_CRITICAL
    }
}
