package io.github.ottershelf.devicetest

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.core.network.ApiJson
import io.github.ottershelf.feature.reader.Footnote
import io.github.ottershelf.feature.reader.ReaderPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Footnotes in the EPUB reader (reader.js openFootnote): a reference shows its note in the app's
 * sheet (`onFootnote`) and the page stays put, for both kinds books use: an unmarked superscript
 * "*" pointing at a notes file at the back (the older ebooks' way, e.g. Pratchett's), and an EPUB 3
 * `epub:type="noteref"` to an `aside epub:type="footnote"` in the same chapter. The note's own link
 * back to the text is left out. An ordinary cross-reference is still followed.
 */
@RunWith(AndroidJUnit4::class)
class FootnoteDeviceTest {

    private object NotesBook {
        private fun page(title: String, body: String) = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en"><head><title>$title</title></head>
<body>$body</body></html>
"""

        private val filler = (1..12).joinToString("\n") {
            "<p>The road ran on between the hedges, and the boy walked it with his thoughts somewhere else entirely, as boys do.</p>"
        }

        fun write(dir: File): File {
            val ch1 = page(
                "One",
                """<h1>One</h1>
<p id="start">He had been apprenticed to Death.<sup><a id="r1" href="notes.xhtml#n1">*</a></sup> It was not what he had expected.</p>
<p>The light was slow and slightly heavy<a id="r3" href="notes.xhtml#ft001"><sup>*1</sup></a>, and it lay in the valleys.</p>
<p>The house was large.<a id="r2" epub:type="noteref" href="#fn2">2</a> Very large indeed.</p>
<aside id="fn2" epub:type="footnote"><p>Larger on the inside, in fact, than the outside.</p></aside>
<p>For more on that, <a id="x1" href="ch2.xhtml">see the chapter about the house and its many rooms</a>.</p>
$filler""",
            )
            val ch2 = page("Two", "<h1 id=\"two\">Two</h1>\n$filler")
            val notes = page(
                "Notes",
                """<h1>Notes</h1>
<p id="n1"><a href="ch1.xhtml#r1">*</a> Which is to say, he had been taken on as a sort of junior.</p>
<div class="fn"><a href="ch1.xhtml#ft001" id="ft001"><sup>*1</sup></a> Practically anything can go faster than Disc light, which is lazy and tame.</div>""",
            )
            val items = listOf(
                FixtureEpub.Item("ch1", "ch1.xhtml", FixtureEpub.XHTML, ch1.toByteArray()),
                FixtureEpub.Item("ch2", "ch2.xhtml", FixtureEpub.XHTML, ch2.toByteArray()),
                FixtureEpub.Item("notes", "notes.xhtml", FixtureEpub.XHTML, notes.toByteArray()),
            )
            return FixtureEpub.pack(
                dir, "Notes Test", items,
                spine = listOf("ch1", "ch2", "notes"),
                toc = listOf(FixtureEpub.TocEntry("One", "ch1.xhtml"), FixtureEpub.TocEntry("Two", "ch2.xhtml"), FixtureEpub.TocEntry("Notes", "notes.xhtml")),
            )
        }
    }

    /** Clicks the link [id] in the chapter on screen, as a tap would reach foliate's handler. */
    private fun ReaderSession.click(id: String) {
        js("(() => { const v = document.querySelector('foliate-view'); for (const c of v.renderer.getContents()) { const a = c.doc.getElementById('$id'); if (a) { a.click(); return true } } return false })()")
    }

    private fun ReaderSession.footnotes(): List<Footnote> =
        probe.calls.filter { it.name == "onFootnote" }.mapNotNull { c -> c.payload?.let { ApiJson.decodeFromString(Footnote.serializer(), it) } }

    @Test
    fun aFootnoteOpensInTheSheetAndThePageStaysPut() {
        val book = NotesBook.write(Device.fixtureDir("reader-footnotes"))
        ReaderSession.open(book, ReaderPrefs(flow = ReaderPrefs.FLOW_PAGINATED)).use { s ->
            s.awaitOpened()
            s.awaitQuiet()
            val before = s.probe.relocates.last().fraction

            s.click("r1") // an unmarked "*" to the notes at the back
            Device.waitUntil("the first footnote (${s.probe.summary()})", 5_000) { s.footnotes().isNotEmpty() }
            val first = s.footnotes().single()
            Device.report("footnote r1: ${first.text} -> ${first.href}")
            assertEquals("Which is to say, he had been taken on as a sort of junior.", first.text)
            assertTrue(first.href.contains("notes.xhtml"))

            s.click("r2") // an EPUB 3 noteref to an aside in the same chapter
            Device.waitUntil("the second footnote", 5_000) { s.footnotes().size == 2 }
            assertEquals("Larger on the inside, in fact, than the outside.", s.footnotes()[1].text)

            // Mort's notes: the target is the marker link itself, inside a div holding the note.
            s.click("r3")
            Device.waitUntil("the third footnote", 5_000) { s.footnotes().size == 3 }
            assertEquals("Practically anything can go faster than Disc light, which is lazy and tame.", s.footnotes()[2].text)
            SystemClock.sleep(1_000)
            assertEquals("the page moved for a footnote", before, s.probe.relocates.last().fraction, 1e-6)

            s.click("x1") // a cross-reference: followed as before
            Device.waitUntil("the jump to chapter two", 5_000) { s.probe.relocates.last().fraction > before + 0.01 }
            assertEquals(3, s.footnotes().size)
            assertFalse(s.probe.calls.any { it.name == "onError" })
        }
    }
}
