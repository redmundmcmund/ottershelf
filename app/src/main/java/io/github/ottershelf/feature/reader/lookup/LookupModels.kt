package io.github.ottershelf.feature.reader.lookup

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.AnnotatedString

/** A dictionary answer: the Wiktionary page found and its definitions, book language first. */
@Immutable
data class DictionaryResult(
    /** The page that answered (`serendipity` for a selected `Serendipity,`). */
    val word: String,
    val sections: List<LanguageSection>,
    /** The page on en.wiktionary.org, at the first section's language. */
    val url: String,
)

/** One language's part of the page (`French`, then `English` in a French book). */
@Immutable
data class LanguageSection(
    /** The response's key: a Wiktionary language code, or `other` for languages without one. */
    val code: String,
    val language: String,
    val blocks: List<SenseBlock>,
)

/**
 * One part of speech of one word. [headword] is the page it came from: the looked-up word, or a
 * word its definition points at (`walked` is the past of `walk`, whose own senses follow).
 */
@Immutable
data class SenseBlock(
    val headword: String,
    val partOfSpeech: String,
    val senses: List<Sense>,
    /** Definitions left out (they're on the Wiktionary page). */
    val more: Int = 0,
)

@Immutable
data class Sense(
    val text: AnnotatedString,
    val example: AnnotatedString? = null,
    /** The example in English, for a word of another language. */
    val translation: AnnotatedString? = null,
    /** The word this definition is a form of (`walk` for "simple past of walk"), if it is one. */
    val formOf: String? = null,
)

/** Wikipedia's page summary (`page/summary/{title}`). */
@Immutable
data class WikiSummary(
    val title: String,
    val description: String? = null,
    val extract: AnnotatedString,
    /** An https image on a Wikimedia host, or null. */
    val thumbnail: String? = null,
    val thumbnailWidth: Int = 0,
    val thumbnailHeight: Int = 0,
    val url: String,
    /** The page lists the articles sharing this name. */
    val disambiguation: Boolean = false,
    /** The Wikipedia it came from (`de`). */
    val lang: String = "en",
)

/** One part of the sheet (the dictionary, or Wikipedia) as it loads. */
@Immutable
sealed interface LookupPart<out T> {
    data object Loading : LookupPart<Nothing>
    data class Ready<T>(val value: T) : LookupPart<T>
    data object NotFound : LookupPart<Nothing>
    /** The request failed: [offline] when the phone has no connection (Retry). */
    data class Failed(val offline: Boolean) : LookupPart<Nothing>
    /** Too many words to look up (nothing was sent). */
    data object Skipped : LookupPart<Nothing>
}

enum class LookupTab { Dictionary, Wikipedia }

/**
 * An app that takes text: a `PROCESS_TEXT` handler (Google Translate, DeepL, a dictionary app) or
 * an `ACTION_TRANSLATE` one. Found through the package manager (the manifest's `<queries>`).
 */
@Immutable
data class TextApp(
    val packageName: String,
    val className: String,
    val label: String,
    val icon: ImageBitmap? = null,
    val kind: Kind = Kind.ProcessText,
    /** The translate handler matched with a `text/plain` type (so it is launched with one). */
    val typed: Boolean = true,
) {
    enum class Kind { ProcessText, Translate }

    val key: String get() = "$packageName/$className/$kind"
}

/** The Look up sheet. Null in [LookupViewModel.state] while it is closed. */
@Immutable
data class LookupUiState(
    /** The selection, on one line: shown at the top and handed to the apps. */
    val text: String,
    /** What was looked up: [text] without the punctuation around it. */
    val term: String,
    /** Five words or fewer: the Dictionary tab is offered (and chosen first). */
    val showDictionary: Boolean,
    val tab: LookupTab,
    val dictionary: LookupPart<DictionaryResult> = LookupPart.Loading,
    val wikipedia: LookupPart<WikiSummary> = LookupPart.Loading,
    val apps: List<TextApp> = emptyList(),
    /** The Wikipedia asked first (the book's language when it has one), for "Search Wikipedia". */
    val wikipediaLang: String = "en",
)
