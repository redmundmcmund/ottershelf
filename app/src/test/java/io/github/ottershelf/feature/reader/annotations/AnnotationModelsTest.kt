package io.github.ottershelf.feature.reader.annotations

import io.github.ottershelf.core.network.ApiJson
import kotlinx.serialization.descriptors.elementNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationModelsTest {

    @Test
    fun cfisOrderByPositionNotByText() {
        val list = listOf(
            "epubcfi(/6/14!/4/10/1:0)",
            "epubcfi(/6/8!/4/2/1:5)",
            "epubcfi(/6/14!/4/2,/1:30,/1:40)",
            "epubcfi(/6/14[ch3]!/4/2,/1:3,/1:9)",
            null,
        )
        assertEquals(
            listOf("epubcfi(/6/8!/4/2/1:5)", "epubcfi(/6/14[ch3]!/4/2,/1:3,/1:9)", "epubcfi(/6/14!/4/2,/1:30,/1:40)", "epubcfi(/6/14!/4/10/1:0)", null),
            list.sortedWith(Cfi.order),
        )
    }

    @Test
    fun theSameSpanMatchesWhateverTheAssertions() {
        assertTrue(Cfi.sameRange("epubcfi(/6/4[c1]!/4/2,/1:0,/1:12)", "epubcfi(/6/4!/4/2,/1:0,/1:12)"))
        assertFalse(Cfi.sameRange("epubcfi(/6/4!/4/2,/1:0,/1:12)", "epubcfi(/6/4!/4/2,/1:0,/1:13)"))
        assertFalse(Cfi.sameRange("epubcfi(/6/4!/4/2,/1:0,/1:12)", "epubcfi(/6/6!/4/2,/1:0,/1:12)"))
    }

    @Test
    fun aBookmarkIsOnThePageWhenItStartsWithinIt() {
        val page = "epubcfi(/6/10!/4/2,/6/1:120,/14/1:48)"
        assertTrue(Cfi.contains(page, page))
        assertTrue(Cfi.contains(page, "epubcfi(/6/10!/4/2,/8/1:0,/9/1:3)"))
        assertTrue(Cfi.contains(page, "epubcfi(/6/10!/4/2/14/1:48)"))
        assertFalse(Cfi.contains(page, "epubcfi(/6/10!/4/2,/6/1:0,/6/1:40)"))
        assertFalse(Cfi.contains(page, "epubcfi(/6/12!/4/2,/8/1:0,/9/1:3)"))
        assertFalse(Cfi.contains(null, page))
    }

    @Test
    fun colourNamesBecomeTheAppsHex() {
        assertEquals("#FACC15", displayHex("yellow"))
        assertEquals("#F472B6", displayHex("PINK"))
        assertEquals("#4ADE80", displayHex("#4ade80"))
        assertEquals("#FF3300", displayHex("#FF3300"))
        assertEquals(DEFAULT_COLOR, displayHex("not a colour"))
        assertEquals(HighlightColor.CYAN, HighlightColor.of("cyan"))
    }

    /**
     * A queue a build before the package rename wrote (files/reader-notes/<account>/<bookId>.json)
     * still reads: each op's type is its @SerialName, never its class's package, so the rename
     * leaves it alone. A file that fails to decode reads as empty, which would drop the queue.
     */
    @Test
    fun aQueueWrittenBeforeThePackageRenameStillReads() {
        val written = """{"annotations":[],"bookmarks":[],"queue":[""" +
            """{"type":"createAnnotation","opId":"a","localId":-1,"draft":{"cfi":"epubcfi(/6/4!/4/2,/1:0,/1:5)","text":"It is","color":"#FACC15","style":"highlight"},"attempted":true,"createdAt":"2026-09-01T10:00:00Z"},""" +
            """{"type":"updateAnnotation","opId":"b","id":12,"note":"n"},""" +
            """{"type":"deleteAnnotation","opId":"c","id":13},""" +
            """{"type":"createBookmark","opId":"d","localId":-2,"cfi":"epubcfi(/6/4!/4)","title":"Chapter 1"},""" +
            """{"type":"deleteBookmark","opId":"e","id":14}""" +
            """],"ids":{"-3":15}}"""
        val file = ApiJson.decodeFromString(NotesFile.serializer(), written)
        assertEquals(
            listOf(
                NoteOp.CreateAnnotation("a", -1, AnnotationDraft("epubcfi(/6/4!/4/2,/1:0,/1:5)", "It is", "#FACC15", "highlight"), attempted = true, createdAt = "2026-09-01T10:00:00Z"),
                NoteOp.UpdateAnnotation("b", 12, note = "n"),
                NoteOp.DeleteAnnotation("c", 13),
                NoteOp.CreateBookmark("d", -2, "epubcfi(/6/4!/4)", "Chapter 1"),
                NoteOp.DeleteBookmark("e", 14),
            ),
            file.queue,
        )
        assertEquals(mapOf(-3L to 15L), file.ids)
    }

    @Test
    fun noQueuedOpIsNamedAfterItsClass() {
        val names = NoteOp.serializer().descriptor.getElementDescriptor(1).elementNames.toList()
        assertEquals(listOf("createAnnotation", "createBookmark", "deleteAnnotation", "deleteBookmark", "updateAnnotation"), names.sorted())
    }

    @Test
    fun theQueueIsAppliedToTheServersList() {
        val file = NotesFile(
            annotations = listOf(Annotation(1, cfi = "epubcfi(/6/4!/4/2/1:0)", text = "a", note = "old"), Annotation(2, cfi = "epubcfi(/6/2!/4/2/1:0)", text = "b")),
            bookmarks = listOf(Bookmark(3, cfi = "epubcfi(/6/2!/4)", title = "One")),
            queue = listOf(
                NoteOp.UpdateAnnotation("u", 1, note = ""),
                NoteOp.DeleteAnnotation("d", 2),
                NoteOp.CreateAnnotation("c", -9, AnnotationDraft("epubcfi(/6/8!/4/2/1:0)", "c", "#4ADE80", STYLE_UNDERLINE)),
                NoteOp.DeleteBookmark("x", 3),
                NoteOp.CreateBookmark("y", -10, "epubcfi(/6/8!/4)", "Two"),
            ),
        )
        val shown = file.visible()
        assertEquals(listOf(1L, -9L), shown.annotations.map { it.id })
        assertEquals(null, shown.annotations.first().note)
        assertEquals(listOf(-10L), shown.bookmarks.map { it.id })
        assertEquals(5, shown.pending)
    }

    @Test
    fun highlightsGroupByChapterInReadingOrder() {
        val list = listOf(
            Annotation(1, text = "a", chapterTitle = "One", color = "yellow"),
            Annotation(2, text = "b", chapterTitle = "One", color = "#4ADE80"),
            Annotation(3, text = "c", chapterTitle = null),
            Annotation(4, text = "d", chapterTitle = "Two", color = "#4ade80"),
        )
        assertEquals(listOf("One", null, "Two"), highlightGroups(list, null).map { it.chapter })
        assertEquals(listOf(listOf(2L), listOf(4L)), highlightGroups(list, "#4ADE80").map { g -> g.items.map { it.id } })
        assertEquals(listOf("#FACC15" to 2, "#4ADE80" to 2), highlightColorCounts(list))
    }

    @Test
    fun theSharedQuoteNamesTheBook() {
        assertEquals("“To be.”\n\n— Hamlet, W. Shakespeare", shareText(" To be. ", "Hamlet", listOf("W. Shakespeare")))
        assertEquals("“To be.”", shareText("To be.", "", emptyList()))
    }
}
