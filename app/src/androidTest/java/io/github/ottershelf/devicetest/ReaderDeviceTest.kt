package io.github.ottershelf.devicetest

import android.os.SystemClock
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.feature.reader.ReaderPrefs
import io.github.ottershelf.feature.reader.Relocate
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * The foliate reader on the phone's own WebView, which Robolectric can't
 * run: a device-only WebView problem once opened every book blank. The real reader page, bridge
 * methods, request handler and chrome ([ReaderSession]) with a made-up EPUB ([FixtureEpub]).
 *
 * Gestures are real touches injected into the host over the lock screen; every other move is what
 * the app itself calls in the page. Screenshots go to `device-tests/reader/`.
 */
@RunWith(AndroidJUnit4::class)
class ReaderDeviceTest {

    companion object {
        private lateinit var book: File

        @JvmStatic
        @BeforeClass
        fun makeBook() {
            book = FixtureEpub.write(Device.fixtureDir("reader"))
            Device.log("WebView: ${WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" }}")
        }

        @JvmStatic
        @AfterClass
        fun cleanUp() {
            Device.fixtureRoot.deleteRecursively()
            Device.context.deleteSharedPreferences("device-tests-session")
        }

        private val SCROLLED = ReaderPrefs(flow = ReaderPrefs.FLOW_SCROLLED)
        private val PAGINATED = ReaderPrefs(flow = ReaderPrefs.FLOW_PAGINATED)

        /** The page's layout, as the reader shows it now (a function body for [ReaderSession.probe]). */
        private const val LAYOUT = """
            const view = document.querySelector('foliate-view');
            const r = view ? view.renderer : null;
            const c = r && r.getContents ? r.getContents()[0] : null;
            const doc = c ? c.doc : null;
            const box = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { x: b.x, y: b.y, w: b.width, h: b.height }; };
            const frame = doc && doc.defaultView ? doc.defaultView.frameElement : null;
            const f = frame ? frame.getBoundingClientRect() : { left: 0, top: 0 };
            const ps = doc ? Array.from(doc.querySelectorAll('p[id]')) : [];
            const onScreen = ps.filter((p) => {
              const b = p.getBoundingClientRect();
              const top = f.top + b.top, left = f.left + b.left;
              return b.height > 0 && b.width > 0 && top < innerHeight && top + b.height > 1 && left < innerWidth && left + b.width > 1;
            }).map((p) => p.id);
            return {
              innerWidth: innerWidth, innerHeight: innerHeight,
              docHeight: document.documentElement.getBoundingClientRect().height,
              app: box(document.getElementById('app')), view: box(view), renderer: box(r), frame: box(frame),
              flow: r ? r.getAttribute('flow') : null,
              maxInlineSize: r ? r.getAttribute('max-inline-size') : null,
              index: c ? c.index : null,
              text: doc && doc.body ? doc.body.innerText.length : 0,
              paragraphs: ps.length,
              onScreen: onScreen,
              start: r ? r.start : null, end: r ? r.end : null, size: r ? r.size : null, viewSize: r ? r.viewSize : null,
            };
        """

        /**
         * The Views in the paginator's container (its shadow root is closed, so found from the
         * current chapter's iframe upwards): each one's visibility, place and whether it is the
         * current one.
         */
        private const val VIEWS = """
            const r = document.querySelector('foliate-view').renderer;
            const doc = r.getContents()[0].doc;
            const frame = doc.defaultView.frameElement;
            let container = frame;
            while (container && container.id !== 'container') container = container.parentNode;
            const views = container ? Array.from(container.children).map((k) => {
              const b = k.getBoundingClientRect();
              return { visibility: getComputedStyle(k).visibility, y: Math.round(b.y), h: Math.round(b.height), current: k.contains(frame) };
            }) : null;
            return { index: r.getContents()[0].index, frameY: Math.round(frame.getBoundingClientRect().y), views: views };
        """

        /** The computed styles of the first body paragraph on screen, its chapter's h1 and body. */
        private const val STYLES = """
            const r = document.querySelector('foliate-view').renderer;
            const doc = r.getContents()[0].doc;
            const frame = doc.defaultView.frameElement.getBoundingClientRect();
            const ps = Array.from(doc.querySelectorAll('p[id]'));
            const p = ps.find((e) => { const b = e.getBoundingClientRect(); return frame.left + b.left >= 0 && frame.left + b.left < innerWidth && frame.top + b.top + b.height > 0 && frame.top + b.top < innerHeight; }) || ps[0];
            const s = doc.defaultView.getComputedStyle(p);
            const h = doc.defaultView.getComputedStyle(doc.querySelector('h1'));
            const body = doc.defaultView.getComputedStyle(doc.body);
            return {
              id: p.id, width: p.getBoundingClientRect().width,
              letterSpacing: s.letterSpacing, wordSpacing: s.wordSpacing, textIndent: s.textIndent,
              marginTop: s.marginTop, marginBottom: s.marginBottom, lineHeight: s.lineHeight, fontSize: s.fontSize,
              fontFamily: s.fontFamily, color: s.color, textAlign: s.textAlign,
              bodyBackground: body.backgroundColor, bodyColor: body.color,
              h1LetterSpacing: h.letterSpacing, h1FontSize: h.fontSize,
              maxInlineSize: r.getAttribute('max-inline-size'),
            };
        """
    }

    // --- 1. paginated --------------------------------------------------------------------------

    @Test
    fun paginatedBookOpensLaidOutAndASwipeTurnsThePage() {
        ReaderSession.open(book, PAGINATED).use { s ->
            s.awaitOpened()
            s.awaitQuiet()
            val layout = s.probe(LAYOUT)
            Device.log("paginated layout: $layout")
            assertLaidOut(s, layout, "paginated")

            val shot = Device.screenshot("reader/paginated")
            val page = Pixels.page(shot, s.webOnScreen())
            Device.log("paginated page ${page.hex()} ink ${page.ink}")
            assertTrue("the page on screen is blank (ink ${page.ink})", page.ink > 0.01)

            // Opening moved nothing: nothing turned, nothing to save or send.
            assertNotMoved(s, "after opening")
            SystemClock.sleep(3_000)
            assertNotMoved(s, "3 s after opening")

            // The book's own styling is left alone while the settings keep it (null: the book's).
            val styles = s.probe(STYLES)
            assertEquals("normal", styles.str("letterSpacing"))
            assertEquals("21.6px", styles.str("textIndent")) // the book's 1.2em at 18px

            // A real swipe to the left turns to the next page, and that is the user's move.
            val pageBefore = s.probe.relocates.last().page
            val count = s.probe.relocates.size
            s.swipeToNextPage()
            Device.waitUntil("a turned relocate after the swipe (${s.probe.summary()})", 5_000) { s.probe.turned().isNotEmpty() }
            s.awaitQuiet()
            val after = s.probe.relocates.last()
            assertTrue("the swipe didn't turn the page: ${s.probe.relocates.drop(count)}", (after.page ?: -1) > (pageBefore ?: -1) || after.fraction > s.probe.relocates[count - 1].fraction)
            Device.waitUntil("the move to count", 2_000) { s.probe.moved }
            Device.screenshot("reader/paginated_turned")
        }
    }

    // --- 2. scrolled ---------------------------------------------------------------------------

    @Test
    fun scrolledBookOpensAndARealFlingScrollsAndCountsAsTheUsersMove() {
        ReaderSession.open(book, SCROLLED).use { s ->
            s.awaitOpened()
            s.awaitQuiet()
            val layout = s.probe(LAYOUT)
            Device.log("scrolled layout: $layout")
            assertLaidOut(s, layout, "scrolled")
            assertTrue("the chapter isn't longer than the screen: $layout", layout.num("viewSize") > layout.num("size") * 3)

            val shot = Device.screenshot("reader/scrolled")
            val page = Pixels.page(shot, s.webOnScreen())
            assertTrue("the scrolled page on screen is blank (ink ${page.ink})", page.ink > 0.01)
            assertNotMoved(s, "after opening")
            SystemClock.sleep(3_000)
            assertNotMoved(s, "3 s after opening")

            val startBefore = layout.num("start")
            val fractionBefore = s.probe.relocates.last().fraction
            val cfiBefore = s.probe.locations.lastOrNull()?.cfi
            s.fling()
            Device.waitUntil("a turned relocate after the fling (${s.probe.summary()})", 5_000) { s.probe.turned().isNotEmpty() }
            s.awaitQuiet()
            val after = s.probe(LAYOUT)
            Device.log("after the fling: $after; ${s.probe.summary()}")
            assertTrue("the fling didn't scroll: start $startBefore -> ${after.num("start")}", after.num("start") > startBefore + 200)
            assertTrue("the location didn't follow the scroll", s.probe.relocates.last().fraction > fractionBefore)
            val cfiAfter = s.probe.locations.last().cfi
            assertNotNull(cfiAfter)
            assertTrue("the full location didn't change ($cfiBefore)", cfiAfter != cfiBefore)
            Device.waitUntil("the move to count", 2_000) { s.probe.moved }
            Device.screenshot("reader/scrolled_flung")
        }
    }

    // --- 3. restore and flow switch -------------------------------------------------------------

    @Test
    fun restoringAPlaceAndSwitchingFlowKeepItWithoutCountingAsTheUsersMove() {
        // The user's first visit: the user scrolls into the first chapter and leaves.
        val saved: Relocate
        val top: String
        ReaderSession.open(book, SCROLLED).use { s ->
            s.awaitOpened()
            s.awaitQuiet()
            repeat(2) {
                s.fling()
                SystemClock.sleep(1_500)
            }
            s.awaitQuiet(2_000)
            saved = s.probe.locations.last()
            top = s.probe(LAYOUT).list("onScreen").first()
            assertTrue(s.probe.moved)
            Device.log("saved $saved at $top")
        }
        assertNotNull(saved.cfi)

        // The user opens it again: the saved place, and nothing counts until the user moves.
        ReaderSession.open(book, SCROLLED, cfi = saved.cfi, percentage = saved.fraction * 100).use { s ->
            s.awaitOpened()
            s.awaitQuiet(2_000)
            val restored = s.probe(LAYOUT)
            Device.log("restored: $restored; ${s.probe.summary()}")
            assertNear("restored in the scrolled flow", top, restored.list("onScreen").first())
            assertTrue("restored at ${s.probe.relocates.last().fraction}, saved ${saved.fraction}", abs(s.probe.relocates.last().fraction - saved.fraction) < 0.003)
            // Restoring puts the saved range's first line at the top, so the new range starts at that
            // line (a few characters from the saved one), in the same paragraph.
            val restoredCfi = s.probe.locations.last().cfi
            Device.log("saved CFI ${saved.cfi}, restored CFI $restoredCfi")
            assertNear("the restored location's paragraph", cfiParagraph(saved.cfi!!), cfiParagraph(restoredCfi!!))
            assertNotMoved(s, "after restoring")
            Device.screenshot("reader/scrolled_restored")

            // Pages, then scroll again: the place stays, and neither is the user's move.
            s.settings(PAGINATED)
            s.awaitQuiet(2_000)
            val paged = s.probe(LAYOUT)
            Device.log("switched to pages: $paged; ${s.probe.summary()}")
            assertEquals("paginated", paged.str("flow"))
            assertTrue("$top isn't on the page after switching to pages: ${paged.list("onScreen")}", paged.list("onScreen").any { near(top, it) })
            assertNotMoved(s, "after switching to pages")
            Device.screenshot("reader/switched_to_pages")

            s.settings(SCROLLED)
            s.awaitQuiet(2_000)
            val back = s.probe(LAYOUT)
            Device.log("switched back to scroll: $back; ${s.probe.summary()}")
            assertEquals("scrolled", back.str("flow"))
            assertNear("back in the scrolled flow", top, back.list("onScreen").first())
            assertNotMoved(s, "after switching back to scroll")

            SystemClock.sleep(3_000)
            assertNotMoved(s, "3 s later")

            // The user's own fling is the first move.
            s.fling()
            Device.waitUntil("a turned relocate after the user's fling (${s.probe.summary()})", 5_000) { s.probe.turned().isNotEmpty() }
            Device.waitUntil("the move to count", 2_000) { s.probe.moved }
        }
    }

    // --- 4. typography --------------------------------------------------------------------------

    @Test
    fun typographyAndCustomColoursReachTheComputedStyles() {
        val prefs = ReaderPrefs(
            theme = "custom", customBg = "#1f3b2d", customFg = "#f0e6c8",
            font = ReaderPrefs.FONT_SERIF, fontSize = 20, lineHeight = 1.8, justify = false,
            letterSpacing = 0.05, wordSpacing = 0.2, paragraphSpacing = 1.0, textIndent = 2.0, maxInlineSize = 400,
        )
        ReaderSession.open(book, prefs).use { s ->
            s.awaitOpened()
            s.awaitQuiet()
            assertLaidOut(s, s.probe(LAYOUT), "paginated")
            val st = s.probe(STYLES)
            Device.log("styles: $st")
            assertEquals("1px", st.str("letterSpacing"))
            assertEquals("4px", st.str("wordSpacing"))
            assertEquals("40px", st.str("textIndent"))
            assertEquals("0px", st.str("marginTop"))
            assertEquals("20px", st.str("marginBottom"))
            assertEquals("20px", st.str("fontSize"))
            assertEquals(36.0, px(st.str("lineHeight"), 20.0), 0.5)
            assertTrue(st.str("fontFamily"), st.str("fontFamily").contains("Georgia"))
            assertEquals("rgb(240, 230, 200)", st.str("color"))
            assertEquals("start", st.str("textAlign"))
            assertEquals("rgb(31, 59, 45)", st.str("bodyBackground"))
            // The book's heading keeps its own letter spacing (the spacing rules are for paragraphs).
            assertEquals("0.64px", st.str("h1LetterSpacing"))
            assertEquals("400px", st.str("maxInlineSize"))
            assertTrue("a paragraph is wider than the 400px column: ${st.num("width")}", st.num("width") <= 400.5)

            val shot = Device.screenshot("reader/styled")
            val page = Pixels.page(shot, s.webOnScreen())
            Device.log("styled page ${page.hex()} ink ${page.ink}")
            assertTrue("the page colour on screen is ${page.hex()}, not #1f3b2d", Pixels.distance(page.background, Pixels.rgb("#1f3b2d")) <= 12)
            assertTrue("the styled page is blank", page.ink > 0.01)

            // A change while reading reaches the chapter on screen (readerSettings, as the sheet sends it).
            s.settings(prefs.copy(letterSpacing = 0.1))
            Device.waitUntil("the new letter spacing", 5_000) { s.probe(STYLES).str("letterSpacing") == "2px" }
            assertNotMoved(s, "after the settings changed")
        }
    }

    // --- 5. the end of the book ----------------------------------------------------------------

    /**
     * Near the end of the chapter before the last (the slider, `readerGoToFraction`), then taps on
     * the right of the page (reader.js' page-turn zone) on into the last chapter and up to its last
     * page, as the user reads to the end (a contents pick of the last chapter is
     * [aContentsPickInPagesShowsTheChaptersText]).
     */
    private fun ReaderSession.tapToTheLastPage() {
        js("readerGoToFraction(0.975)")
        awaitQuiet()
        SystemClock.sleep(2_000) // idle: the last chapter is prepared
        var last = -1.0
        for (i in 0 until 16) {
            tapRight()
            SystemClock.sleep(700)
            awaitQuiet(800)
            val f = probe.relocates.last().fraction
            if (f == last) break
            last = f
        }
    }

    /** The page shows text: a paragraph on screen, and ink in the screenshot [name]. */
    private fun assertTextOnScreen(s: ReaderSession, name: String, what: String) {
        val layout = s.probe(LAYOUT)
        val page = Pixels.page(Device.screenshot(name), s.webOnScreen())
        Device.log("$what: on screen ${layout.list("onScreen")}, ink ${page.ink}, page ${layout.num("start")}..${layout.num("end")} of ${layout.num("viewSize")}")
        assertTrue("$what: a blank page (no paragraph on screen: $layout; ink ${page.ink})", layout.list("onScreen").isNotEmpty() && page.ink > 0.005)
    }

    /**
     * The last page reports the end of the book (reader.js `bookEnd`, where the next-in-series card
     * shows) and the page before it doesn't, so the card never covers the ending's last lines.
     */
    @Test
    fun theLastPageOfAPaginatedBookReachesTheNextInSeriesThreshold() {
        ReaderSession.open(book, PAGINATED).use { s ->
            s.awaitOpened()
            s.awaitQuiet()
            s.tapToTheLastPage()
            val end = s.probe.relocates.last()
            val before = s.probe.relocates.lastOrNull { it.fraction < end.fraction }
            Device.log("paginated end: $end; the page before: $before")
            assertTextOnScreen(s, "reader/end_paginated", "the last page")
            assertTrue("the last page (fraction ${end.fraction}, page ${end.page}/${end.pages}) doesn't report the end of the book: no next-in-series card", end.bookEnd)
            assertNotNull("no relocate for the page before the last", before)
            assertFalse("the page before the last already reports the end of the book: $before", before!!.bookEnd)
        }
    }

    /** A swipe on past the last page (the user's thumb doesn't know it's the last) leaves the last page up. */
    @Test
    fun aSwipePastTheLastPageKeepsTheLastPageOnScreen() {
        ReaderSession.open(book, PAGINATED).use { s ->
            s.awaitOpened()
            s.awaitQuiet()
            s.tapToTheLastPage()
            assertTextOnScreen(s, "reader/last_page", "the last page")
            s.swipeToNextPage()
            SystemClock.sleep(1_000)
            s.awaitQuiet()
            Device.log("after a swipe past the end: ${s.probe.relocates.last()}; console ${s.probe.consoleErrors}")
            assertTextOnScreen(s, "reader/swiped_past_the_end", "after a swipe past the last page")
        }
    }

    /**
     * A pick in the contents (as the TOC sheet sends it: `readerGoTo`) shows that chapter's text,
     * to a chapter in the middle and to the last one, after the paginator has had time to lay out
     * the next chapter ahead (its hidden prepared View). The views in the paginator's container
     * are logged (their visibility and place), for the report.
     */
    @Test
    fun aContentsPickInPagesShowsTheChaptersText() {
        ReaderSession.open(book, PAGINATED).use { s ->
            s.awaitOpened()
            s.awaitQuiet()
            SystemClock.sleep(2_000) // idle: the next chapter is prepared
            Device.log("views before: ${s.probe(VIEWS)}")
            val toc = s.probe.opened!!.toc
            val failures = mutableListOf<String>()
            for ((name, entry) in listOf("middle" to toc[2], "last" to toc.last())) {
                s.js("readerGoTo(${ApiJson.encodeToString(String.serializer(), entry.href!!)})")
                SystemClock.sleep(300)
                val soon = s.probe(VIEWS)
                s.awaitQuiet()
                SystemClock.sleep(2_000) // and idle again
                val later = s.probe(VIEWS)
                val layout = s.probe(LAYOUT)
                val page = Pixels.page(Device.screenshot("reader/toc_$name"), s.webOnScreen())
                Device.report("reader: contents pick of the $name chapter (${entry.label}): views just after $soon; 2 s later $later; on screen ${layout.list("onScreen")}, ink ${"%.3f".format(page.ink)}")
                // The chapter's iframe sits at the page margin (28px) unless something pushed it down.
                if (soon.num("frameY") > 100) failures += "$name chapter (${entry.label}): 0.3 s after the pick its page is pushed off screen, views $soon"
                if (layout.list("onScreen").isEmpty() || page.ink < 0.005) failures += "$name chapter (${entry.label}): blank page 2 s later, views $later"
            }
            assertEquals("a contents pick showed a blank page", emptyList<String>(), failures)
        }
    }

    /** A tap back on a chapter's first page shows the previous chapter's last page at once. */
    @Test
    fun aTurnBackIntoThePreviousChapterShowsItsLastPage() {
        ReaderSession.open(book, PAGINATED).use { s ->
            s.awaitOpened()
            s.awaitQuiet()
            s.js("readerGoTo(${ApiJson.encodeToString(String.serializer(), s.probe.opened!!.toc[2].href!!)})")
            s.awaitQuiet()
            SystemClock.sleep(2_000) // settled, and the next chapter prepared
            assertTextOnScreen(s, "reader/chapter_start", "the chapter's first page")
            s.tapLeft()
            SystemClock.sleep(300)
            val soon = s.probe(VIEWS)
            val shot = Device.screenshot("reader/turned_back")
            val page = Pixels.page(shot, s.webOnScreen())
            s.awaitQuiet()
            SystemClock.sleep(1_500)
            val later = s.probe(VIEWS)
            Device.report("reader: a tap back into the previous chapter: views 0.3 s after $soon (ink ${"%.3f".format(page.ink)}); 1.5 s after it settled $later")
            assertEquals("not in the previous chapter", 1.0, later.num("index"), 0.0)
            assertTrue("0.3 s after the turn back the page is pushed off screen (ink ${page.ink}): $soon", soon.num("frameY") <= 100 && page.ink > 0.005)
            assertTextOnScreen(s, "reader/turned_back_settled", "the previous chapter's last page")
        }
    }

    /** A swipe back on the first page of the book leaves the first page up. */
    @Test
    fun aSwipeBackOnTheFirstPageKeepsTheFirstPageOnScreen() {
        ReaderSession.open(book, PAGINATED).use { s ->
            s.awaitOpened()
            s.awaitQuiet()
            s.swipeToPreviousPage()
            SystemClock.sleep(1_000)
            s.awaitQuiet()
            Device.log("after a swipe back on the first page: ${s.probe.relocates.last()}; console ${s.probe.consoleErrors}")
            assertTextOnScreen(s, "reader/swiped_back_at_the_start", "after a swipe back on the first page")
        }
    }

    /**
     * Scrolled to the very bottom of the last section, the reader reports the end of the book
     * (`bookEnd`, where the next-in-series card shows), although foliate's fraction there (the top
     * of the view, and the saved percentage, left as it is) stays short of 1. The top of that
     * section, a screen or more above, doesn't.
     */
    @Test
    fun theBottomOfAScrolledBookReachesTheNextInSeriesThreshold() {
        ReaderSession.open(book, SCROLLED).use { s ->
            s.awaitOpened()
            s.awaitQuiet()
            s.goToLastChapter()
            val top = s.probe.relocates.last()
            var layout = s.probe(LAYOUT)
            Device.log("top of the last section: $top; layout $layout")
            if (layout.num("viewSize") - layout.num("end") > 4) {
                assertFalse("the top of the last section already reports the end of the book: $top", top.bookEnd)
            }
            for (i in 0 until 8) {
                if (layout.num("viewSize") - layout.num("end") <= 4) break
                s.fling()
                SystemClock.sleep(1_200)
                s.awaitQuiet(800)
                layout = s.probe(LAYOUT)
            }
            val end = s.probe.relocates.last()
            Device.log("scrolled end: $end; layout $layout")
            Device.screenshot("reader/end_scrolled")
            assertTrue("didn't reach the bottom of the last chapter: $layout", layout.num("viewSize") - layout.num("end") <= 4)
            assertEquals("not in the last section", (FixtureEpub.chapters.size - 1).toDouble(), layout.num("index"), 0.0)
            assertTrue(
                "the bottom of the book (fraction ${end.fraction}, screen ${layout.num("size")} of a ${layout.num("viewSize")} px last section) doesn't report the end of the book: no next-in-series card",
                end.bookEnd,
            )
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun assertLaidOut(s: ReaderSession, layout: JsonObject, flow: String) {
        val web = s.webOnScreen()
        assertTrue("the WebView has no size: $web", web.width() > 100 && web.height() > 100)
        assertTrue("the page has no height: $layout", layout.num("innerHeight") > 100 && layout.num("docHeight") > 100)
        assertTrue("foliate-view has no height: $layout", layout.obj("view").num("h") > 100)
        assertTrue("the renderer has no height: $layout", layout.obj("renderer").num("h") > 100)
        assertEquals(flow, layout.str("flow"))
        assertTrue("no text laid out: $layout", layout.num("text") > 1_000 && layout.num("paragraphs") > 3)
        assertTrue("no paragraph on screen: $layout", layout.list("onScreen").isNotEmpty())
        val r = s.probe.relocates.lastOrNull()
        assertNotNull("no relocate arrived", r)
        r!!
        assertTrue("fraction ${r.fraction}", r.fraction in 0.0..1.0)
        assertNotNull("no page count", r.pages)
        assertTrue("no reading time: $r", (r.timeSection ?: 0.0) > 0.0 && (r.timeTotal ?: 0.0) > 0.0 && (r.timeBook ?: 0.0) > 0.0)
        val loc = s.probe.locations.lastOrNull()
        assertNotNull("no full location arrived", loc)
        assertTrue("location without a CFI: $loc", loc!!.cfi?.startsWith("epubcfi(") == true)
        assertTrue("reader.js console errors: ${s.probe.consoleErrors}", s.probe.consoleErrors.none { "Error" in it && "favicon" !in it })
    }

    private fun assertNotMoved(s: ReaderSession, whenText: String) {
        assertTrue("a relocate counted as the user's move $whenText: ${s.probe.turned()}", s.probe.turned().isEmpty())
        assertFalse("ReadingVisit.moved is true $whenText", s.probe.moved)
        assertTrue("a session would be sent $whenText", s.probe.sessions.isEmpty())
    }

    /** Paragraph ids are `<chapter>-p<n>`: the same chapter, at most one paragraph apart. */
    private fun near(a: String, b: String): Boolean {
        val (ca, na) = a.split("-p").let { it[0] to it[1].toInt() }
        val (cb, nb) = b.split("-p").let { it[0] to it[1].toInt() }
        return ca == cb && abs(na - nb) <= 1
    }

    /** The paragraph a range CFI starts in: `ch1-p6` in `epubcfi(/6/2!/4/2[ch1-top],/14[ch1-p6]/1:400,...)`. */
    private fun cfiParagraph(cfi: String): String =
        Regex("\\[([a-z0-9]+-p\\d+)]").find(cfi)?.groupValues?.get(1) ?: error("no paragraph in $cfi")

    private fun assertNear(what: String, expected: String, actual: String) {
        assertTrue("$what: $actual at the top, $expected expected", near(expected, actual))
        if (expected != actual) Device.log("$what: $actual at the top, $expected before (one paragraph apart)")
    }

    private fun ReaderSession.fling() {
        host.requireOnTop()
        val r = webOnScreen()
        val x = r.centerX()
        Device.ui.swipe(x, r.top + (r.height() * 0.72).toInt(), x, r.top + (r.height() * 0.32).toInt(), 12)
    }

    private fun ReaderSession.swipeToNextPage() {
        host.requireOnTop()
        val r = webOnScreen()
        val y = r.top + r.height() / 2
        Device.ui.swipe(r.left + (r.width() * 0.8).toInt(), y, r.left + (r.width() * 0.2).toInt(), y, 10)
    }

    private fun ReaderSession.swipeToPreviousPage() {
        host.requireOnTop()
        val r = webOnScreen()
        val y = r.top + r.height() / 2
        Device.ui.swipe(r.left + (r.width() * 0.2).toInt(), y, r.left + (r.width() * 0.8).toInt(), y, 10)
    }

    /** A tap in the page-turn zone on the left (reader.js turns to the previous page). */
    private fun ReaderSession.tapLeft() {
        host.requireOnTop()
        val r = webOnScreen()
        Device.ui.click(r.left + (r.width() * 0.15).toInt(), r.top + r.height() / 2)
    }

    /** A tap in the page-turn zone on the right (reader.js turns to the next page). */
    private fun ReaderSession.tapRight() {
        host.requireOnTop()
        val r = webOnScreen()
        Device.ui.click(r.left + (r.width() * 0.85).toInt(), r.top + r.height() / 2)
    }

    /** The last chapter from the contents, as the user's pick in the TOC sheet does (`readerGoTo`). */
    private fun ReaderSession.goToLastChapter() {
        val href = probe.opened!!.toc.last().href!!
        js("readerGoTo(${ApiJson.encodeToString(String.serializer(), href)})")
        Device.waitUntil("the last chapter", 10_000) { probe(LAYOUT).num("index") == (FixtureEpub.chapters.size - 1).toDouble() }
        awaitQuiet()
    }

    private fun JsonObject.str(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull ?: error("no $key in $this")
    private fun JsonObject.num(key: String): Double = (get(key) as? JsonPrimitive)?.doubleOrNull ?: error("no number $key in $this")
    private fun JsonObject.obj(key: String): JsonObject = get(key)?.jsonObject ?: error("no $key in $this")
    private fun JsonObject.list(key: String): List<String> = (get(key) as? JsonArray)?.map { it.jsonPrimitive.content } ?: error("no $key in $this")

    /** A computed line height in px (Chromium resolves a number to px; "normal" isn't expected). */
    private fun px(value: String, fontSize: Double): Double = value.removeSuffix("px").toDoubleOrNull()?.let { if (it < 5) it * fontSize else it } ?: error(value)
}
