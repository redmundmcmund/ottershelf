package io.github.ottershelf.devicetest

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.feature.reader.ReaderPrefs
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A book that tries every way into the reader page it could have ([HostileEpub]): its own scripts
 * inline, in an event handler, in a javascript: link, in an SVG (inline and as a file), in a
 * srcdoc frame; the reader's own page and script loaded from a chapter; its own files framed or
 * loaded as a script straight from the page's `/api/` route; and refreshes to both. Each one, if
 * it ran, would call the bridge with a `crafted:` message (or `onReady`, the reader's own script).
 * On the phone's WebView, with the app's CSP, request handler and reader.js: the book opens and
 * reads as any other, the app hears exactly what it hears from any book (one onReady, one onOpened,
 * relocates, locations), a link out of the book opens nothing, a chapter whose CSS animates its
 * layout on every frame sends four relocates a second at most, at its top and part-way down, and
 * the last one is where the page stopped, the book's files come with the
 * headers that keep them from running as pages, and nothing outside the assets and the book's
 * routes is answered.
 */
@RunWith(AndroidJUnit4::class)
class ReaderHostileBookDeviceTest {

    companion object {
        private lateinit var book: File

        @JvmStatic
        @BeforeClass
        fun makeBook() {
            book = HostileEpub.write(Device.fixtureDir("reader-hostile"))
        }

        @JvmStatic
        @AfterClass
        fun cleanUp() {
            Device.fixtureRoot.deleteRecursively()
            Device.context.deleteSharedPreferences("device-tests-session")
        }

        private val PAGINATED = ReaderPrefs(flow = ReaderPrefs.FLOW_PAGINATED)
        private val SCROLLED = ReaderPrefs(flow = ReaderPrefs.FLOW_SCROLLED)

        /** What the reader page tells the app about any book. */
        private val NORMAL_CALLS = setOf("onReady", "onOpened", "onRelocate", "onLocation")

        /** Taps (clicks) the chapter's two links, as the user's finger would: foliate's link handling runs. */
        private const val CLICK_LINKS = """
            const r = document.querySelector('foliate-view').renderer;
            const doc = r.getContents()[0].doc;
            const js = doc.getElementById('js-link');
            const web = doc.getElementById('web-link');
            if (js) js.click();
            if (web) web.click();
            return { js: !!js, web: !!web };
        """

        /** The page as it stands: views, text laid out in the chapter on screen, windows opened. */
        private const val STATE = """
            const views = document.querySelectorAll('foliate-view');
            const r = views.length ? views[0].renderer : null;
            const c = r && r.getContents ? r.getContents()[0] : null;
            const doc = c ? c.doc : null;
            return {
              views: views.length,
              index: c ? c.index : null,
              text: doc && doc.body ? doc.body.innerText.length : 0,
              opened: (window.__opened || []).length,
            };
        """
    }

    @Test
    fun aBooksOwnScriptsAndFramesNeverReachTheApp() {
        ReaderSession.open(book, PAGINATED).use { s ->
            s.awaitOpened()
            s.awaitQuiet()
            // A window the page opens is recorded instead (foliate hands a link out of the book to window.open).
            s.js("window.__opened = []; window.open = (u) => { window.__opened.push(String(u)); return null; }; true")

            val clicked = s.probe(CLICK_LINKS)
            Device.log("links clicked: $clicked")
            assertTrue("the chapter's links weren't found: $clicked", clicked.str("js") == "true" && clicked.str("web") == "true")
            SystemClock.sleep(1_000)

            // Every chapter in turn (the refreshes are in the later ones).
            val toc = s.probe.opened!!.toc
            for (entry in toc) {
                goTo(s, entry.href!!)
                SystemClock.sleep(2_500) // a refresh fires after a second, the next chapter is prepared
                // The animated chapter never goes quiet (its pages change on every frame, and up to
                // four relocates a second say so): it is measured below instead.
                if (!entry.href!!.endsWith("animated.xhtml")) s.awaitQuiet()
            }

            // The animated chapter scrolled, where its height changes the chapter's on every frame.
            s.settings(SCROLLED)
            goTo(s, toc[1].href!!)
            SystemClock.sleep(1_500)
            val before = s.probe.relocates.size
            SystemClock.sleep(3_000)
            val flood = s.probe.relocates.size - before
            Device.log("relocates in 3 s on the animated chapter: $flood")
            assertTrue("the animated chapter sent $flood relocates in 3 s", flood <= 14)

            // Part-way down it (a volume-key step): the text on screen is kept in place as the
            // animated block above it grows and shrinks, so every relocate differs. At most one
            // every 250 ms reaches the app, and once the animation stops the last one is where the
            // page stopped.
            s.js("readerNext()")
            SystemClock.sleep(1_500)
            val from = s.probe.relocates.size
            SystemClock.sleep(3_000)
            val moving = s.probe.relocates.drop(from)
            val fractions = moving.map { it.fraction }.distinct().size
            Device.log("relocates in 3 s part-way down the animated chapter: ${moving.size}, $fractions fractions")
            assertTrue("part-way down the animated chapter: ${moving.size} relocates in 3 s", moving.size in 4..13)
            assertTrue("the relocates part-way down didn't differ ($fractions fractions)", fractions >= 2)
            s.js("document.querySelector('foliate-view').renderer.getContents()[0].doc.querySelector('.grow').style.animationPlayState = 'paused'; true")
            SystemClock.sleep(1_000)
            val shown = s.probe("return { fraction: document.querySelector('foliate-view').lastLocation.fraction };").num("fraction")
            assertEquals("the last relocate isn't where the page stopped", shown, s.probe.relocates.last().fraction, 0.0)
            s.settings(PAGINATED)
            goTo(s, toc.first().href!!)
            SystemClock.sleep(1_500)
            s.awaitQuiet()

            // The book's files come as data that can't run as a page, and nothing else is answered.
            val headers = s.probe(
                """
                const x = new XMLHttpRequest();
                x.open('GET', '/api/v1/epub/${ReaderSession.BOOK_ID}/file/OEBPS/page.xhtml?fileId=${ReaderSession.FILE_ID}', false);
                x.send();
                const y = new XMLHttpRequest();
                y.open('GET', '/favicon.ico', false);
                y.send();
                return { status: x.status, csp: x.getResponseHeader('Content-Security-Policy'), nosniff: x.getResponseHeader('X-Content-Type-Options'), other: y.status };
                """,
            )
            Device.log("headers: $headers")
            assertEquals(200.0, headers.num("status"), 0.0)
            assertEquals("sandbox; default-src 'none'", headers.str("csp"))
            assertEquals("nosniff", headers.str("nosniff"))
            assertEquals("a path outside the assets and the API was answered", 403.0, headers.num("other"), 0.0)

            val state = s.probe(STATE)
            val calls = s.probe.calls.toList()
            Device.log("state: $state; calls: ${calls.groupingBy { it.name }.eachCount()}; console: ${s.probe.consoleErrors.take(12)}")
            Device.report("reader: a hostile book: ${calls.groupingBy { it.name }.eachCount()}, animated chapter $flood relocates in 3 s at its top, ${moving.size} part-way down, state $state")
            Device.screenshot("reader/hostile_book")

            assertEquals("the page said it was ready more than once (a copy of reader.js in a chapter?)", 1, s.probe.ready)
            assertEquals("the book opened more than once", 1, calls.count { it.name == "onOpened" })
            assertEquals("calls the reader page never makes: ${calls.filter { it.name !in NORMAL_CALLS }}", emptyList<ReaderProbe.Call>(), calls.filter { it.name !in NORMAL_CALLS })
            assertTrue("the book reached the app: ${calls.filter { "crafted" in it.payload.orEmpty() }}", calls.none { "crafted" in it.payload.orEmpty() })
            assertEquals("views piled up: $state", 1.0, state.num("views"), 0.0)
            assertEquals("a window was opened: $state", 0.0, state.num("opened"), 0.0)
            // The reader still reads: back on the first chapter, laid out, with text on screen.
            assertEquals(0.0, state.num("index"), 0.0)
            assertTrue("no text laid out: $state", state.num("text") > 1_000)
            val page = Pixels.page(Device.screenshot("reader/hostile_book_first"), s.webOnScreen())
            assertTrue("a blank page (ink ${page.ink})", page.ink > 0.01)
        }
    }

    private fun goTo(s: ReaderSession, href: String) {
        s.js("readerGoTo(${ApiJson.encodeToString(String.serializer(), href)})")
    }

    private fun JsonObject.str(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull ?: error("no $key in $this")
    private fun JsonObject.num(key: String): Double = (get(key) as? JsonPrimitive)?.doubleOrNull ?: error("no number $key in $this")
}

/**
 * The hostile book: a first chapter with every in-page attempt and two links, a chapter whose CSS
 * animates its layout on every frame, two chapters that refresh themselves (to the reader's page,
 * and to the book's own file on the `/api/` route) and a plain last one. Every attempt calls the
 * bridge (`window.Android`, in every frame of the app's WebView; the page's otherwise) with a
 * `crafted:` message, so any that runs shows up in [ReaderProbe.calls]. tools/reader-js-test's
 * hostile.epub is the same book, for Chromium.
 */
private object HostileEpub {
    private const val HOST = "https://appassets.androidplatform.net"
    private const val CALL = "(window.Android || top.Android).onError"
    private val FILE = "$HOST/api/v1/epub/${ReaderSession.BOOK_ID}/file/OEBPS"
    private val QUERY = "?fileId=${ReaderSession.FILE_ID}"

    fun write(dir: File): File {
        val items = listOf(
            FixtureEpub.Item("attempts", "attempts.xhtml", FixtureEpub.XHTML, chapter("Attempts", attempts()).toByteArray()),
            FixtureEpub.Item("animated", "animated.xhtml", FixtureEpub.XHTML, chapter("Animated", "<div class=\"grow\"></div>", head = ANIMATION).toByteArray()),
            FixtureEpub.Item("refresh", "refresh.xhtml", FixtureEpub.XHTML, chapter("A Refresh", "", head = refresh("$HOST/assets/reader/index.html")).toByteArray()),
            FixtureEpub.Item("refresh-api", "refresh-api.xhtml", FixtureEpub.XHTML, chapter("Another Refresh", "", head = refresh("$FILE/page.xhtml$QUERY")).toByteArray()),
            FixtureEpub.Item("end", "end.xhtml", FixtureEpub.XHTML, chapter("The End", "").toByteArray()),
            // Not in the spine: the book's own page, script and drawing, reached by the attempts.
            FixtureEpub.Item("page", "page.xhtml", FixtureEpub.XHTML, PAGE.toByteArray()),
            FixtureEpub.Item("script", "book.js", "text/javascript", "$CALL('crafted:api-script');\n".toByteArray()),
            FixtureEpub.Item("drawing", "drawing.svg", "image/svg+xml", SVG.toByteArray()),
        )
        val chapters = listOf("attempts" to "Attempts", "animated" to "Animated", "refresh" to "A Refresh", "refresh-api" to "Another Refresh", "end" to "The End")
        return FixtureEpub.pack(
            dir, "A Hostile Book", items,
            spine = chapters.map { it.first },
            toc = chapters.map { (id, title) -> FixtureEpub.TocEntry(title, "$id.xhtml") },
        )
    }

    private fun attempts(): String = """
<script>$CALL('crafted:inline');</script>
<p><img src="missing.png" alt="" onerror="$CALL('crafted:onerror')"/></p>
<p><a id="js-link" href="javascript:$CALL('crafted:js-link')">A javascript: link.</a>
<a id="web-link" href="https://example.com/">A link out of the book.</a></p>
<svg xmlns="http://www.w3.org/2000/svg" width="12" height="12"><script>$CALL('crafted:svg-inline');</script><rect width="12" height="12"/></svg>
<iframe title="srcdoc" width="12" height="12" srcdoc="&lt;script&gt;$CALL('crafted:srcdoc');&lt;/script&gt;"></iframe>
<iframe title="drawing" width="24" height="24" src="drawing.svg"></iframe>
<object data="drawing.svg" type="image/svg+xml" width="24" height="24"></object>
<script type="module" src="$HOST/assets/reader/reader.js"></script>
<iframe title="reader" width="12" height="12" src="$HOST/assets/reader/index.html"></iframe>
<iframe title="page" width="12" height="12" src="$FILE/page.xhtml$QUERY"></iframe>
<script src="$FILE/book.js$QUERY"></script>
"""

    private fun refresh(url: String) = """<meta http-equiv="refresh" content="1;url=$url"/>"""

    private const val ANIMATION = "<style>@keyframes grow { from { height: 20px } to { height: 1400px } } " +
        ".grow { animation: grow 0.8s linear infinite alternate; background: #ccd; }</style>"

    private fun chapter(title: String, body: String, head: String = ""): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en"><head><title>$title</title>""")
        append(head)
        append("</head>\n<body>\n<h1>$title</h1>\n")
        append(body)
        for (p in 1..30) append("<p>Paragraph $p of $title: the reader lays this chapter out as it would any other, whatever the book tries around it.</p>\n")
        append("</body></html>\n")
    }

    private const val PAGE = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Page</title></head>
<body><p>The book's own page.</p><script>$CALL('crafted:api-page');</script></body></html>
"""

    private const val SVG = """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"><rect width="24" height="24" fill="#888"/><script>$CALL('crafted:svg-file');</script></svg>
"""
}
