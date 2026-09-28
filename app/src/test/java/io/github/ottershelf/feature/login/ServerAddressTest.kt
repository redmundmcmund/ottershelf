package io.github.ottershelf.feature.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
