package io.github.ottershelf.feature.reader.annotations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.feature.reader.ReaderContent
import io.github.ottershelf.feature.reader.ReaderUiState
import io.github.ottershelf.feature.reader.captureDark
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The reader's highlights, notes, bookmarks and search, dark. The page is a stand-in (no WebView). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class ReaderNotesScreenshotTest {

    @Test
    fun selectionPopup() = captureDark("reader/selection_popup") {
        WithFakeCovers {
            ReaderContent(
                state = ReaderUiState(title = "A Study in Scarlet", loading = false),
                bookmarked = true,
                overlay = {
                    SelectionPopupHost(
                        SelectionPopupState(
                            text = "You have been in Afghanistan",
                            cfi = "epubcfi(/6/14!/4/2,/1:0,/1:28)",
                            chapter = "Chapter 3",
                            rect = PageRect(40f, 330f, 380f, 382f),
                            annotationId = 12,
                            color = "#4ADE80",
                            style = STYLE_HIGHLIGHT,
                            note = "Watson's first real glimpse of Holmes's method.",
                            fromTap = true,
                        ),
                        SelectionPopupActions(),
                    )
                },
            ) { modifier -> FakePage(modifier) }
        }
    }

    /** A selection foliate gave no CFI: Copy and Share only. */
    @Test
    fun selectionPopupWithoutPlace() = captureDark("reader/selection_popup_copy_only") {
        WithFakeCovers {
            ReaderContent(
                state = ReaderUiState(title = "A Study in Scarlet", loading = false),
                bookmarked = false,
                overlay = {
                    SelectionPopupHost(
                        SelectionPopupState(
                            text = "You have been in Afghanistan",
                            cfi = null,
                            chapter = "Chapter 3",
                            rect = PageRect(40f, 330f, 380f, 382f),
                        ),
                        SelectionPopupActions(),
                    )
                },
            ) { modifier -> FakePage(modifier) }
        }
    }

    @Test
    fun highlights() = captureDark("reader/notes_highlights") {
        Sheet { ReaderNotesContent(ReaderNotesUiState(annotations = sampleAnnotations, bookmarks = sampleBookmarks, loaded = true), NotesTab.Highlights, {}, NotesSheetActions()) }
    }

    @Test
    fun bookmarks() = captureDark("reader/notes_bookmarks") {
        Sheet { ReaderNotesContent(ReaderNotesUiState(annotations = sampleAnnotations, bookmarks = sampleBookmarks, loaded = true), NotesTab.Bookmarks, {}, NotesSheetActions()) }
    }

    @Test
    fun search() = captureDark("reader/notes_search") {
        val hits = listOf(
            SearchHit("c2", "a little sallow rat-faced, dark-eyed fellow who was introduced to me as Mr. ", "Lestrade", ", and who came three or four times in a single week", "2. The Science of Deduction"),
            SearchHit("c3", "he and ", "Lestrade", " are the pick of a bad lot", "3. The Lauriston Garden Mystery"),
            SearchHit("c3", "Gregson, ", "Lestrade", ", and Co. will pocket all the credit", "3. The Lauriston Garden Mystery"),
        )
        Sheet {
            ReaderNotesContent(
                ReaderNotesUiState(
                    annotations = sampleAnnotations,
                    loaded = true,
                    search = SearchState(query = "Lestrade", hits = hits, done = true, progress = 1f, id = 1),
                ),
                NotesTab.Search, {}, NotesSheetActions(),
            )
        }
    }

    @Test
    fun noteDialog() = captureDark("reader/note_dialog") {
        val colors = OttershelfTheme.colors
        Box(Modifier.fillMaxSize().padding(24.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 180.dp),
                color = colors.popover,
                shape = RoundedCornerShape(OttershelfTheme.radii.xl2),
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text("Add note", style = MaterialTheme.typography.headlineSmall, color = colors.foreground)
                    Spacer(Modifier.height(16.dp))
                    NoteDialogBody("You have been in Afghanistan, I perceive.", "", {})
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {}) { Text("Cancel") }
                        TextButton(onClick = {}) { Text("Save") }
                    }
                }
            }
        }
    }

    @Composable
    private fun Sheet(content: @Composable () -> Unit) {
        WithFakeCovers {
            Surface(Modifier.fillMaxSize().padding(top = 96.dp), color = OttershelfTheme.colors.card, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
                Column(Modifier.padding(top = 28.dp)) { content() }
            }
        }
    }

    @Composable
    private fun FakePage(modifier: Modifier) {
        val fg = OttershelfTheme.colors.foreground
        Column(modifier.padding(horizontal = 26.dp, vertical = 28.dp)) {
            Text("1. Mr. Sherlock Holmes", style = MaterialTheme.typography.titleLarge, color = fg, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(LOREM, fontSize = 18.sp, lineHeight = 27.sp, color = fg, textAlign = TextAlign.Justify)
            Spacer(Modifier.height(8.dp))
            Text(
                buildAnnotatedString {
                    append("“How are you?” he said cordially, gripping my hand with a strength for which I should hardly have given him credit. “")
                    withStyle(SpanStyle(background = HighlightColor.GREEN.color.copy(alpha = 0.32f))) { append("You have been in Afghanistan, I perceive") }
                    append(".”")
                },
                fontSize = 18.sp, lineHeight = 27.sp, color = fg, textAlign = TextAlign.Justify,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                buildAnnotatedString {
                    append("I had neither kith nor kin in England, and was therefore ")
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = Color(0xFF38BDF8))) { append("as free as air") }
                    append(".")
                },
                fontSize = 18.sp, lineHeight = 27.sp, color = fg, textAlign = TextAlign.Justify,
            )
            repeat(3) {
                Spacer(Modifier.height(8.dp))
                Text(LOREM, fontSize = 18.sp, lineHeight = 27.sp, color = fg, textAlign = TextAlign.Justify)
            }
        }
    }

    private companion object {
        const val LOREM = "Under such circumstances, I naturally gravitated to London, that great cesspool into which all " +
            "the loungers and idlers of the Empire are irresistibly drained."

        val sampleAnnotations = listOf(
            Annotation(1, cfi = "epubcfi(/6/4!/4/2/1:0)", text = "In the year 1878 I took my degree of Doctor of Medicine of the University of London, and proceeded to Netley to go through the course prescribed for surgeons in the army.", color = "#FACC15", chapterTitle = "1. Mr. Sherlock Holmes", highlightedAt = "2026-09-12T20:14:00Z"),
            Annotation(2, cfi = "epubcfi(/6/4!/4/8/1:0)", text = "I had neither kith nor kin in England, and was therefore as free as air.", color = "#F472B6", style = STYLE_UNDERLINE, note = "Watson, the day before Baker Street.", chapterTitle = "1. Mr. Sherlock Holmes", highlightedAt = "2026-09-12T20:31:00Z"),
            Annotation(3, cfi = "epubcfi(/6/14!/4/2/1:0)", text = "You have been in Afghanistan, I perceive.", color = "#4ADE80", note = "The line everyone quotes. Still lands.", chapterTitle = "1. Mr. Sherlock Holmes", origin = "koreader", highlightedAt = "2026-09-20T07:02:00Z"),
            Annotation(-4, cfi = "epubcfi(/6/14!/4/12/1:0)", text = "It is a capital mistake to theorize before you have all the evidence. It biases the judgment.", color = "#38BDF8", chapterTitle = "3. The Lauriston Garden Mystery", createdAt = "2026-09-25T08:40:00Z"),
        )

        val sampleBookmarks = listOf(
            Bookmark(10, cfi = "epubcfi(/6/4!/4/2)", title = "1. Mr. Sherlock Holmes", createdAt = "2026-09-12T20:00:00Z"),
            Bookmark(11, cfi = "epubcfi(/6/14!/4/2)", title = "3. The Lauriston Garden Mystery", createdAt = "2026-09-20T07:00:00Z"),
            Bookmark(-12, cfi = "epubcfi(/6/18!/4/2)", title = "42%", createdAt = "2026-09-25T08:41:00Z"),
        )
    }
}
