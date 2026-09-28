package io.github.ottershelf.feature.reader.lookup

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import io.github.ottershelf.feature.reader.annotations.PageRect
import io.github.ottershelf.feature.reader.annotations.STYLE_HIGHLIGHT
import io.github.ottershelf.feature.reader.annotations.SelectionPopupActions
import io.github.ottershelf.feature.reader.annotations.SelectionPopupHost
import io.github.ottershelf.feature.reader.annotations.SelectionPopupState
import io.github.ottershelf.feature.reader.lookup.WikimediaParsingTest.Companion.fixture
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.captureLightAndDark
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.theme.OttershelfTheme
import okhttp3.HttpUrl
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Look up sheet over the reader, light and dark, from Wikimedia's real answers (fixtures). The
 * sheet is drawn in place (ModalBottomSheet opens a window a capture can't see): the page, the
 * scrim, the sheet with its handle at the height the real one takes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class LookupScreenshotTest {

    @Test
    fun dictionary() = captureLightAndDark("reader/lookup_dictionary") {
        Sheet(
            LookupUiState(
                text = "chat",
                term = "chat",
                showDictionary = true,
                tab = LookupTab.Dictionary,
                dictionary = LookupPart.Ready(dictionary("chat", "fr")),
                wikipedia = LookupPart.Loading,
                apps = apps(),
                wikipediaLang = "fr",
            ),
        )
    }

    /** "walked" is the past of walk: walk's own senses follow under their headword. */
    @Test
    fun dictionaryFormOf() = captureLightAndDark("reader/lookup_dictionary_form") {
        Sheet(
            LookupUiState(
                text = "walked,",
                term = "walked",
                showDictionary = true,
                tab = LookupTab.Dictionary,
                dictionary = LookupPart.Ready(dictionary("walked", "en")),
                apps = apps().take(1),
            ),
        )
    }

    @Test
    fun wikipedia() = captureLightAndDark("reader/lookup_wikipedia") {
        val summary = WikimediaParsing.summary(fixture("sum_serendipity.json"), "en")!!.copy(thumbnail = fakeCover(2))
        Sheet(
            LookupUiState(
                text = "Serendipity",
                term = "Serendipity",
                showDictionary = true,
                tab = LookupTab.Wikipedia,
                dictionary = LookupPart.Ready(dictionary("serendipity", "en")),
                wikipedia = LookupPart.Ready(summary),
                apps = apps(),
            ),
        )
    }

    @Test
    fun notFound() = captureLightAndDark("reader/lookup_not_found") {
        Sheet(
            LookupUiState(
                text = "Lestrade’s",
                term = "Lestrade",
                showDictionary = true,
                tab = LookupTab.Dictionary,
                dictionary = LookupPart.NotFound,
                wikipedia = LookupPart.NotFound,
                apps = apps(),
            ),
        )
    }

    @Test
    fun offline() = captureLightAndDark("reader/lookup_offline") {
        Sheet(
            LookupUiState(
                text = "Lauriston",
                term = "Lauriston",
                showDictionary = true,
                tab = LookupTab.Dictionary,
                dictionary = LookupPart.Failed(offline = true),
                wikipedia = LookupPart.Failed(offline = true),
                apps = apps(),
            ),
        )
    }

    @Test
    fun loading() = captureLightAndDark("reader/lookup_loading") {
        Sheet(LookupUiState(text = "skein", term = "skein", showDictionary = true, tab = LookupTab.Dictionary))
    }

    /** More than five words: Wikipedia and the apps only. */
    @Test
    fun longPhrase() = captureLightAndDark("reader/lookup_phrase") {
        val summary = WikimediaParsing.summary(fixture("sum_jekyll.json"), "en")!!.copy(thumbnail = fakeCover(4))
        Sheet(
            LookupUiState(
                text = "Strange Case of Dr Jekyll and Mr Hyde",
                term = "Strange Case of Dr Jekyll and Mr Hyde",
                showDictionary = false,
                tab = LookupTab.Wikipedia,
                dictionary = LookupPart.Skipped,
                wikipedia = LookupPart.Ready(summary),
                apps = apps(),
            ),
        )
    }

    /** A whole passage: nothing is sent; the apps take it. No apps installed would hide the row. */
    @Test
    fun passage() = captureLightAndDark("reader/lookup_passage") {
        val text = "It is a capital mistake to theorize before you have all the evidence. It biases the judgment."
        Sheet(
            LookupUiState(
                text = text,
                term = LookupText.clean(text),
                showDictionary = false,
                tab = LookupTab.Wikipedia,
                dictionary = LookupPart.Skipped,
                wikipedia = LookupPart.Skipped,
                apps = apps(),
            ),
        )
    }

    // --- the popup's Look up button ----------------------------------------------------------------

    /** A tapped highlight: with Delete as well, the popup widens by one button. */
    @Test
    fun popupOnAHighlight() = captureLightAndDark("reader/lookup_popup_highlight") {
        Popup(
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
        )
    }

    /**
     * The same highlight on a page too narrow for one row of styles and five buttons (320dp: a
     * larger Display size, split screen): the buttons get their own row, Delete whole at its end.
     */
    @Test
    fun popupOnAHighlightOnANarrowPage() = captureLightAndDark("reader/lookup_popup_highlight_narrow", widthDp = 320, heightDp = 693) {
        Popup(
            SelectionPopupState(
                text = "You have been in Afghanistan",
                cfi = "epubcfi(/6/14!/4/2,/1:0,/1:28)",
                chapter = "Chapter 3",
                rect = PageRect(26f, 330f, 294f, 409f),
                annotationId = 12,
                color = "#4ADE80",
                style = STYLE_HIGHLIGHT,
                note = "Watson's first real glimpse of Holmes's method.",
                fromTap = true,
            ),
        )
    }

    /** A new selection: as wide as before. */
    @Test
    fun popupOnASelection() = captureLightAndDark("reader/lookup_popup_selection") {
        Popup(SelectionPopupState(text = "Medicine", cfi = "epubcfi(/6/14!/4/2,/1:52,/1:60)", chapter = "Chapter 3", rect = PageRect(118f, 331f, 205f, 355f)))
    }

    /** No place in the book (no CFI): Look up, Copy and Share. */
    @Test
    fun popupWithoutPlace() = captureLightAndDark("reader/lookup_popup_copy_only") {
        Popup(SelectionPopupState(text = "Medicine", cfi = null, chapter = "Chapter 3", rect = PageRect(118f, 331f, 205f, 355f)))
    }

    @Composable
    private fun Popup(state: SelectionPopupState) {
        WithFakeCovers {
            Box(Modifier.fillMaxSize()) {
                FakePage()
                SelectionPopupHost(state, SelectionPopupActions(onLookUp = {}))
            }
        }
    }

    // --- frame -----------------------------------------------------------------------------------

    @Composable
    private fun Sheet(state: LookupUiState) {
        WithFakeCovers {
            Box(Modifier.fillMaxSize()) {
                FakePage()
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
                Surface(
                    Modifier.fillMaxSize().padding(top = 202.dp),
                    color = OttershelfTheme.colors.card,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                ) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier.size(width = 32.dp, height = 4.dp)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
                            )
                        }
                        LookupContent(state, LookupActions(), Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    @Composable
    private fun FakePage() {
        val fg = OttershelfTheme.colors.foreground
        Column(Modifier.fillMaxSize().background(OttershelfTheme.colors.background).padding(horizontal = 26.dp, vertical = 40.dp)) {
            Text("1. Mr. Sherlock Holmes", style = MaterialTheme.typography.titleLarge, color = fg, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                "In the year 1878 I took my degree of Doctor of Medicine of the University of London, and proceeded to Netley to go through the course prescribed for surgeons in the army.",
                fontSize = 18.sp, lineHeight = 27.sp, color = fg, textAlign = TextAlign.Justify,
            )
        }
    }

    // --- data ------------------------------------------------------------------------------------

    private fun dictionary(word: String, lang: String): DictionaryResult = runBlocking {
        LookupRepository(FixtureRemote).dictionary(word, lang)!!
    }

    private object FixtureRemote : WikimediaRemote {
        private val answers = mapOf(
            "serendipity" to "def_serendipity.json",
            "chat" to "def_chat.json",
            "walked" to "def_walked.json",
            "walk" to "def_walk.json",
        ).map { (word, file) -> Urls.definition(word).toString() to file }.toMap()

        override suspend fun get(url: HttpUrl): String? = answers[url.toString()]?.let(::fixture)
    }

    private fun apps(): List<TextApp> = listOf(
        TextApp("com.google.android.apps.translate", "TranslateActivity", "Translate", icon(0xFF4285F4.toInt(), 0xFFFFFFFF.toInt())),
        TextApp("com.deepl.mobiletranslator", "ProcessTextActivity", "DeepL", icon(0xFF0F2B46.toInt(), 0xFF2BB5E0.toInt())),
        TextApp("org.example.dictionary", "Define", "Dictionary", icon = null),
        TextApp("org.example.translator", "Translate", "Offline Translator", icon = null, kind = TextApp.Kind.Translate),
    )

    /** A made-up launcher icon: a rounded square with a dot. */
    private fun icon(background: Int, mark: Int): ImageBitmap {
        val b = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = background
        c.drawRoundRect(RectF(0f, 0f, 72f, 72f), 18f, 18f, p)
        p.color = mark
        c.drawCircle(36f, 36f, 16f, p)
        return b.asImageBitmap()
    }
}
