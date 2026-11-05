package io.github.ottershelf.feature.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The typed server address, tidied so the account key doesn't depend on how it was typed. */
class ServerAddressTest {

    @Test
    fun spellingsOfOneServerGiveOneAddress() {
        for (typed in listOf(
            "https://books.example.net",
            "https://Books.Example.NET/",
            "HTTPS://books.example.net//",
            "https://books.example.net:443",
            "  https://books.example.net  ",
        )) assertEquals(typed, "https://books.example.net", ServerAddress.canonical(typed))

        assertEquals("https://books.example.net:8443/shelf", ServerAddress.canonical("https://Books.example.net:8443/shelf/"))
        assertEquals("https://[::1]:8443", ServerAddress.canonical("https://[::1]:8443/"))
    }

    @Test
    fun whatCantBeAServersAddressIsRefused() {
        for (typed in listOf(
            "http://books.example.net",
            "https://reader:secret@books.example.net",
            "https://books.example.net/?x=1",
            "https://books.example.net/?",
            "https://books.example.net/#top",
            "https://books example.net",
            "https://",
        )) assertNull(typed, ServerAddress.canonical(typed))
    }

    @Test
    fun theStoredSpellingIsKeptForTheSameServer() {
        // Stored before addresses were tidied: the user's downloads and queued progress are under its key.
        val stored = "https://Books.Example.net"
        assertEquals(stored, ServerAddress.resolve("https://books.example.net/", stored))
        assertEquals(stored, ServerAddress.resolve(stored, stored))
        // An address already stored in its tidy form maps to itself.
        val tidy = "https://library.example.org"
        assertEquals(tidy, ServerAddress.resolve(tidy, tidy))

        assertEquals("https://other.example.net", ServerAddress.resolve("https://Other.example.net/", stored))
        assertEquals("https://books.example.net", ServerAddress.resolve("https://Books.example.net", null))
        assertNull(ServerAddress.resolve("https://books.example.net/?", stored))
    }

    @Test
    fun theDebugBuildTakesHttpOnlyForTheEmulatorsHostAndLoopback() {
        assertEquals("http://10.0.2.2:3399", ServerAddress.canonical("http://10.0.2.2:3399/", debugHttp = true))
        assertEquals("http://localhost:80", ServerAddress.canonical("HTTP://LocalHost", debugHttp = true))
        assertEquals("http://127.0.0.1:3399/shelf", ServerAddress.canonical("http://127.0.0.1:3399/shelf/", debugHttp = true))
        assertEquals("http://10.0.2.2:3399", ServerAddress.resolve("http://10.0.2.2:3399", stored = null, debugHttp = true))
        for (typed in listOf("http://10.0.2.2:3399", "http://localhost:3399", "http://127.0.0.1")) {
            assertFalse(typed, ServerAddress.insecure(typed, debugHttp = true))
        }
        // Any other host stays https only, in the debug build too.
        for (typed in listOf("http://books.example.net", "http://10.0.2.3:3399", "http://192.168.1.10:3399", "http://localhost.example.net")) {
            assertNull(typed, ServerAddress.canonical(typed, debugHttp = true))
            assertTrue(typed, ServerAddress.insecure(typed, debugHttp = true))
        }
        assertFalse(ServerAddress.insecure("https://books.example.net", debugHttp = true))
    }

    @Test
    fun otherBuildsStayHttpsOnly() {
        for (typed in listOf("http://10.0.2.2:3399", "http://localhost:3399", "http://127.0.0.1", "http://books.example.net")) {
            assertNull(typed, ServerAddress.canonical(typed, debugHttp = false))
            assertNull(typed, ServerAddress.resolve(typed, stored = null, debugHttp = false))
            assertTrue(typed, ServerAddress.insecure(typed, debugHttp = false))
        }
        assertEquals("https://books.example.net", ServerAddress.canonical("https://books.example.net/", debugHttp = false))
        assertFalse(ServerAddress.insecure("https://books.example.net", debugHttp = false))
    }
}
