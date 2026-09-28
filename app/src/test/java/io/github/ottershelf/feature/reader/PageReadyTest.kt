package io.github.ottershelf.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bridge's `onReady` ([PageReady]): the book opens once per page, and only in the page on
 * screen, whatever a book's chapter loads (a copy of reader.js there says it's ready too). Plain JVM.
 */
class PageReadyTest {

    private class Page : ReaderPage {
        override fun js(code: String) {}
        override fun evaluate(code: String, onResult: (String?) -> Unit) {}
    }

    private val first = Page()
    private val second = Page()
    private var onScreen: ReaderPage? = first
    private var opened = 0
    private val mainQueue = ArrayList<() -> Unit>()

    private fun ready(page: ReaderPage) = PageReady(page, onScreen = { onScreen }, post = { mainQueue += it }, open = { opened++ })

    private fun runMain() {
        val blocks = mainQueue.toList()
        mainQueue.clear()
        blocks.forEach { it() }
    }

    @Test
    fun aPageIsHeardOnce() {
        val bridge = ready(first)
        bridge.onReady() // the page's reader.js
        bridge.onReady() // a copy of it loaded in a chapter
        runMain()
        bridge.onReady() // and again, once the book is open
        runMain()
        assertEquals(1, opened)
    }

    @Test
    fun onlyThePageOnScreenIsHeard() {
        val old = ready(first)
        onScreen = second // a Retry, or the renderer gone: a new page is on screen
        old.onReady()
        runMain()
        assertEquals("the old page opened the book", 0, opened)
        ready(second).onReady()
        runMain()
        assertEquals(1, opened)
    }

    @Test
    fun aPageReplacedWhileItsReadyWaitsIsntHeard() {
        val old = ready(first)
        old.onReady()
        onScreen = second // replaced before main ran the ready it posted
        runMain()
        assertEquals(0, opened)
    }
}
