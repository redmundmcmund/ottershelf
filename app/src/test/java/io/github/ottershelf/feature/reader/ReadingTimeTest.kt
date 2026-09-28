package io.github.ottershelf.feature.reader

import io.github.ottershelf.core.network.ApiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The footer's time left, its modes, and the pace kept per book. Plain JVM. */
class ReadingTimeTest {

    @Test
    fun theUsersPaceGivesTheBooksTimeLeft() {
        // A quarter in, reading 20% of the book an hour: 75% left is 3 h 45 min.
        val left = ReadingTime.timeLeft(0.25, sectionMinutes = 30.0, totalMinutes = 450.0, bookMinutes = 600.0, percentPerHour = 20.0)!!
        assertTrue(left.fromPace)
        assertEquals(13_500L, left.bookSeconds)
        // The chapter's 30 of foliate's 600 minutes is 5% of the book: 15 min at the user's pace.
        assertEquals(900L, left.chapterSeconds)
    }

    @Test
    fun theChapterNeverOutlastsTheBook() {
        // foliate's sizes and the fraction disagree a little at the very end.
        val left = ReadingTime.timeLeft(0.99, sectionMinutes = 60.0, totalMinutes = 60.0, bookMinutes = 600.0, percentPerHour = 10.0)!!
        assertEquals(360L, left.bookSeconds)
        assertEquals(360L, left.chapterSeconds)
    }

    @Test
    fun withoutTheUsersPaceFoliatesEstimate() {
        val left = ReadingTime.timeLeft(0.4, sectionMinutes = 12.5, totalMinutes = 250.0, bookMinutes = 420.0, percentPerHour = null)!!
        assertFalse(left.fromPace)
        assertEquals(750L, left.chapterSeconds)
        assertEquals(15_000L, left.bookSeconds)
        // A pace of zero (no progress in the user's sessions) is no pace.
        assertFalse(ReadingTime.timeLeft(0.4, 12.5, 250.0, 420.0, percentPerHour = 0.0)!!.fromPace)
    }

    @Test
    fun whatCantBeToldIsNull() {
        assertNull(ReadingTime.timeLeft(0.3, null, null, null, null))
        assertNull(ReadingTime.timeLeft(0.3, Double.NaN, -1.0, 100.0, null))
        // The user's pace but no sizes from foliate: the book's time, not the chapter's.
        val left = ReadingTime.timeLeft(0.5, null, null, null, percentPerHour = 25.0)!!
        assertEquals(7_200L, left.bookSeconds)
        assertNull(left.chapterSeconds)
        // Finished: nothing left.
        assertEquals(0L, ReadingTime.timeLeft(1.0, 0.0, 0.0, 600.0, 25.0)!!.bookSeconds)
    }

    @Test
    fun theFooterCyclesPageChapterBook() {
        assertEquals(FooterMode.CHAPTER, FooterMode.PAGE.next())
        assertEquals(FooterMode.BOOK, FooterMode.CHAPTER.next())
        assertEquals(FooterMode.PAGE, FooterMode.BOOK.next())
        // The web's footerDisplayMode values: 0 pages, 1 time left in the book, 2 the chapter's.
        assertEquals(listOf(0, 1, 2), listOf(FooterMode.PAGE, FooterMode.BOOK, FooterMode.CHAPTER).map { it.id })
        assertEquals(FooterMode.PAGE, FooterMode.of(7))
        assertEquals(FooterMode.CHAPTER, FooterMode.of(2))
    }

    @Test
    fun theFootersChoiceIsKeptWithTheSettings() {
        val stored = ApiJson.encodeToString(ReaderPrefs.serializer(), ReaderPrefs(footerDisplayMode = FooterMode.BOOK.id))
        assertTrue(stored.contains("\"footerDisplayMode\":1"))
        assertEquals(FooterMode.BOOK, FooterMode.of(ApiJson.decodeFromString(ReaderPrefs.serializer(), stored).footerDisplayMode))
    }

    @Test
    fun relocatesCarryFoliatesTimes() {
        val r = ApiJson.decodeFromString(
            Relocate.serializer(),
            """{"fraction":0.5,"page":3,"pages":20,"turned":true,"timeSection":4.2,"timeTotal":180.5,"timeBook":361,"chapterEnd":true}""",
        )
        assertEquals(4.2, r.timeSection!!, 1e-9)
        assertEquals(180.5, r.timeTotal!!, 1e-9)
        assertEquals(361.0, r.timeBook!!, 1e-9)
        assertTrue(r.chapterEnd)
        // The full location has none of them.
        val full = ApiJson.decodeFromString(Relocate.serializer(), """{"cfi":"epubcfi(/6/4!/4/2/1:0)","fraction":0.5}""")
        assertNull(full.timeSection)
        assertFalse(full.chapterEnd)
    }

    @Test
    fun thePaceMapKeepsTheLatestHundredBooks() {
        var map: Map<String, Double> = emptyMap()
        for (id in 1L..105L) map = PaceCache.put(map, id, id.toDouble())
        assertEquals(PaceCache.MAX, map.size)
        assertNull(map["1"])
        assertEquals(105.0, map["105"]!!, 0.0)
        // Updating one makes it the newest; forgetting one (no trusted pace any more) removes it.
        map = PaceCache.put(map, 6L, 12.5)
        assertEquals("6", map.keys.last())
        map = PaceCache.put(map, 6L, null)
        assertNull(map["6"])
        assertNull(PaceCache.put(emptyMap(), 3L, Double.NaN)["3"])
    }
}
