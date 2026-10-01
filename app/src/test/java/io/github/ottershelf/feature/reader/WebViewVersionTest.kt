package io.github.ottershelf.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewVersionTest {
    @Test
    fun aWebViewBeforeChrome117IsOutdated() {
        assertTrue(WebViewVersion.isOutdated("91.0.4472.114"))
        assertTrue(WebViewVersion.isOutdated("116.0.5845.163"))
    }

    @Test
    fun chrome117AndLaterAreNot() {
        assertFalse(WebViewVersion.isOutdated("117.0.5938.60"))
        assertFalse(WebViewVersion.isOutdated("140.0.7339.51"))
    }

    @Test
    fun aVersionWithoutANumberIsNotCalledOutdated() {
        assertFalse(WebViewVersion.isOutdated(""))
        assertFalse(WebViewVersion.isOutdated("dev"))
    }
}
