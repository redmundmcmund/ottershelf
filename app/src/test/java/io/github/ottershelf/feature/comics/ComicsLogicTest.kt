package io.github.ottershelf.feature.comics

import io.github.ottershelf.core.readerprefs.CbxReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The spread rules (the web's spread-layout.spec.ts cases), the CBZ page order, and small helpers. */
class ComicsLogicTest {

    @get:Rule val temp = TemporaryFolder()

    private fun pages(layout: SpreadLayout) = layout.spreads.map { it.pages }

    // --- spreads ---------------------------------------------------------------------------------

    @Test fun singlePagesWhenNotTwoPage() {
        val layout = SpreadLayout.create(pageCount = 4, twoPage = false, rtl = false)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3)), pages(layout))
        assertTrue(layout.spreads.all { it.single })
    }

    @Test fun coverAloneThenPairs() {
        val layout = SpreadLayout.create(pageCount = 6, twoPage = true, rtl = false)
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5)), pages(layout))
        val last = layout.spreads.last()
        assertFalse(last.single)
        assertTrue(last.hasBlank)
        assertEquals(5, last.left)
        assertNull(last.right)
    }

    @Test fun rightToLeftPutsTheFirstPageOnTheRight() {
        val spread = SpreadLayout.create(pageCount = 3, twoPage = true, rtl = true).spreads[1]
        assertEquals(listOf(1, 2), spread.pages)
        assertEquals(2, spread.left)
        assertEquals(1, spread.right)
    }

    @Test fun shiftedAddsASingleAfterTheCover() {
        val layout = SpreadLayout.create(pageCount = 6, twoPage = true, rtl = false, shifted = true)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4, 5)), pages(layout))
    }

    @Test fun widePagesStandAloneAndBreakPairs() {
        val ratios = mapOf(2 to 1.5f, 5 to 1.3f)
        val layout = SpreadLayout.create(pageCount = 7, twoPage = true, rtl = false, shifted = false, widePagesInSpreads = false, ratios = ratios)
        // 1 pairs with nothing (2 is wide), 2 alone, 3+4 pair, 5 alone, 6 with a blank.
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4), listOf(5), listOf(6)), pages(layout))
        assertTrue(layout.spreads[1].hasBlank)
        assertTrue(layout.spreads[2].single)
        assertTrue(layout.spreads[4].single)
    }

    @Test fun widePagesInSpreadsPairAnyway() {
        val layout = SpreadLayout.create(pageCount = 5, twoPage = true, rtl = false, shifted = false, widePagesInSpreads = true, ratios = mapOf(2 to 2f))
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), pages(layout))
    }

    @Test fun theLayoutKeyChangesOnlyWhenAPageTurnsOutWide() {
        // The small prefetch and the full decode report slightly different ratios for the same page.
        val prefetched = mapOf(0 to 0.6531f, 1 to 0.66f, 2 to 1.5f)
        val decoded = mapOf(0 to 0.6509f, 1 to 0.6612f, 2 to 1.49f)
        assertEquals(setOf(2), SpreadLayout.widePages(prefetched))
        assertEquals(SpreadLayout.widePages(prefetched), SpreadLayout.widePages(decoded))
        assertEquals(setOf(2, 3), SpreadLayout.widePages(decoded + (3 to 1.2f)))
        val fromSet = SpreadLayout.create(pageCount = 5, twoPage = true, rtl = false, shifted = false, widePagesInSpreads = false, wide = setOf(2))
        val fromRatios = SpreadLayout.create(pageCount = 5, twoPage = true, rtl = false, shifted = false, widePagesInSpreads = false, ratios = decoded)
        assertEquals(pages(fromRatios), pages(fromSet))
    }

    @Test fun pagesMapToTheirSpread() {
        val layout = SpreadLayout.create(pageCount = 6, twoPage = true, rtl = false)
        assertEquals(0, layout.spreadIndexForPage(0))
        assertEquals(1, layout.spreadIndexForPage(2))
        assertEquals(1, layout.anchorForPage(2))
        assertEquals(3, layout.spreadIndexForPage(99)) // clamped
        assertEquals(0, SpreadLayout.create(0, twoPage = true, rtl = false).spreadIndexForPage(3))
    }

    @Test fun spreadsOnlyInPagedTwoPageViewInLandscapeOrForced() {
        val two = CbxReaderSettings(viewMode = "two-page")
        assertTrue(twoPageEffective(two, landscape = true))
        assertFalse(twoPageEffective(two, landscape = false))
        assertTrue(twoPageEffective(two.copy(forceTwoPage = true), landscape = false))
        assertFalse(twoPageEffective(two.copy(scrollMode = "long-strip"), landscape = true))
        assertFalse(twoPageEffective(CbxReaderSettings(), landscape = true))
    }

    // --- tap zones ------------------------------------------------------------------------------

    @Test fun outerQuartersTurnThePageMirroredForRightToLeft() {
        val log = mutableListOf<String>()
        val turner = PageTurner().apply {
            next = { log += "next" }
            previous = { log += "previous" }
        }
        tapZone(0.1f, rtl = false, turner) { log += "bars" }
        tapZone(0.9f, rtl = false, turner) { log += "bars" }
        tapZone(0.5f, rtl = false, turner) { log += "bars" }
        tapZone(0.1f, rtl = true, turner) { log += "bars" }
        tapZone(0.9f, rtl = true, turner) { log += "bars" }
        assertEquals(listOf("previous", "next", "bars", "next", "previous"), log)
    }

    // --- CBZ page order -------------------------------------------------------------------------

    @Test fun naturalOrderLikeTheServer() {
        val names = listOf("page10.jpg", "Page2.jpg", "page1.jpg", "page01b.png", "page_3.jpg", "cover.jpg", "10/a.jpg", "2/a.jpg")
        assertEquals(
            listOf("2/a.jpg", "10/a.jpg", "cover.jpg", "page_3.jpg", "page1.jpg", "page01b.png", "Page2.jpg", "page10.jpg"),
            names.sortedWith(LocalCbz.NaturalOrder),
        )
    }

    @Test fun sharedPrefixesStillCompareByTheCollator() {
        val order = LocalCbz.NaturalOrder
        // Only case or accents apart after a long shared prefix: equal (the archive's order is kept).
        assertEquals(0, order.compare("Chapter 012/Page_007.jpg", "chapter 012/page_007.JPG"))
        assertEquals(0, order.compare("Vol 1/Épisode 3/p1.png", "Vol 1/episode 3/p1.png"))
        // The first difference after it decides, by the collator ('_' before letters, unlike ASCII).
        assertTrue(order.compare("Chapter 012/page_b.jpg", "Chapter 012/page_A2.jpg") > 0)
        assertTrue(order.compare("Chapter 012/page_x.jpg", "Chapter 012/pageA.jpg") < 0)
        assertEquals(0, order.compare("Chapter 012/p.jpg", "Chapter 012/p.jpg"))
    }

    @Test fun onlyVisibleImagesWithBytes() {
        val entries = listOf(
            LocalCbz.Entry("Issue 1/", true, ZipEntry.STORED, 0),
            LocalCbz.Entry("Issue 1/002.jpg", false, ZipEntry.DEFLATED, 10),
            LocalCbz.Entry("Issue 1/001.JPG", false, ZipEntry.STORED, 10),
            LocalCbz.Entry("__MACOSX/Issue 1/._001.jpg", false, ZipEntry.DEFLATED, 10),
            LocalCbz.Entry(".thumbs/001.jpg", false, ZipEntry.DEFLATED, 10),
            LocalCbz.Entry("ComicInfo.xml", false, ZipEntry.DEFLATED, 10),
            LocalCbz.Entry("Issue 1/003.webp", false, ZipEntry.DEFLATED, 0),
            LocalCbz.Entry("Issue 1/004.avif", false, ZipEntry.DEFLATED, 10),
        )
        assertEquals(listOf("Issue 1/001.JPG", "Issue 1/002.jpg", "Issue 1/004.avif"), LocalCbz.pages(entries))
    }

    @Test fun readsARealZip() {
        val file = temp.newFile("book.cbz")
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in listOf("p10.png", "p2.png", "p1.png", "notes.txt")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1, 2, 3, 4))
                zip.closeEntry()
            }
        }
        val pages = LocalCbz.pages(file)
        assertEquals(listOf("p1.png", "p2.png", "p10.png"), pages)
        val source = ComicSource.Local(file, pages)
        assertEquals(3, source.pageCount)
        assertEquals(LocalComicPage(file, "p10.png"), source.model(2))
        assertNull(source.model(3))
    }
}
