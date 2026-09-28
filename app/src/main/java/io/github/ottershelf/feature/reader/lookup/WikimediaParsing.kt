package io.github.ottershelf.feature.reader.lookup

import androidx.compose.ui.text.AnnotatedString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Reads Wikimedia's REST answers (pure; `WikimediaParsingTest` runs on answers captured with curl):
 * Wiktionary's `page/definition/{term}` (definitions keyed by language code, `other` for the
 * languages without one; HTML in every definition and example), Wikipedia's `page/summary/{title}`
 * and `search/title`. Anything unexpected in the shape is skipped, never thrown.
 */
internal object WikimediaParsing {

    /** Definitions shown per part of speech; the rest are counted ([SenseBlock.more]). */
    const val MAX_SENSES = 4

    /** Parts of speech shown per word and language. */
    const val MAX_BLOCKS = 5

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** One entry of a definition answer: a language's part of speech with its definitions. */
    class RawEntry(val code: String, val language: String, val partOfSpeech: String, val senses: List<Sense>)

    fun definitions(body: String): List<RawEntry> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        val out = mutableListOf<RawEntry>()
        for ((code, value) in root) {
            val entries = value as? JsonArray ?: continue
            for (entry in entries) {
                val e = entry as? JsonObject ?: continue
                val language = e.string("language")?.trim().orEmpty().ifEmpty { code }
                val pos = e.string("partOfSpeech")?.trim().orEmpty()
                val senses = (e["definitions"] as? JsonArray).orEmpty().mapNotNull { sense(it as? JsonObject) }
                if (senses.isNotEmpty()) out += RawEntry(code, language, pos, senses)
            }
        }
        return out
    }

    private fun sense(d: JsonObject?): Sense? {
        val html = d?.string("definition") ?: return null
        val text = HtmlText.toAnnotated(html)
        if (text.isBlank()) return null
        var example: AnnotatedString? = null
        var translation: AnnotatedString? = null
        val parsed = (d["parsedExamples"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (parsed != null) {
            example = parsed.string("example")?.let(HtmlText::toAnnotated)?.takeIf { it.isNotBlank() }
            translation = parsed.string("translation")?.let(HtmlText::toAnnotated)?.takeIf { it.isNotBlank() }
        }
        if (example == null) {
            val first = (d["examples"] as? JsonArray)?.firstOrNull()
            val raw = (first as? JsonPrimitive)?.takeIf { it.isString }?.content ?: (first as? JsonObject)?.string("text")
            example = raw?.let(HtmlText::toAnnotated)?.takeIf { it.isNotBlank() }
        }
        return Sense(text, example, translation, formOf(html))
    }

    /**
     * The word a "form of" definition points at: Wiktionary marks them
     * (`<span class="form-of-definition-link">…<a title="walk">`), as in "simple past of walk" or
     * "plural of berry". Null for a definition that stands on its own.
     */
    fun formOf(html: String): String? {
        val marker = html.indexOf("form-of-definition-link")
        if (marker < 0) return null
        val link = html.indexOf("<a ", marker)
        if (link < 0) return null
        val end = html.indexOf('>', link)
        if (end < 0) return null
        val attrs = html.substring(link, end)
        val title = TITLE.find(attrs)?.groupValues?.get(1)?.let(HtmlText::decode)?.trim() ?: return null
        // Appendix:Glossary and the like are links to explanations, not words.
        return title.takeIf { it.isNotEmpty() && ':' !in it }
    }

    private val TITLE = Regex("""\btitle="([^"]*)"""")

    /**
     * [entries] of the page for [headword] as sections, one per language in the answer's order,
     * each part of speech once (Wiktionary splits a word by etymology: `bank` has two nouns).
     */
    fun sections(entries: List<RawEntry>, headword: String): List<LanguageSection> {
        val byLanguage = LinkedHashMap<String, Pair<String, LinkedHashMap<String, MutableList<Sense>>>>()
        for (e in entries) {
            val (_, blocks) = byLanguage.getOrPut(e.language) { e.code to LinkedHashMap() }
            blocks.getOrPut(e.partOfSpeech) { mutableListOf() } += e.senses
        }
        return byLanguage.map { (language, value) ->
            val (code, blocks) = value
            LanguageSection(
                code = code,
                language = language,
                blocks = blocks.entries.take(MAX_BLOCKS).map { (pos, senses) ->
                    SenseBlock(headword, pos, senses.take(MAX_SENSES), more = (senses.size - MAX_SENSES).coerceAtLeast(0))
                },
            )
        }
    }

    /**
     * The sections a reader of a [bookLang] book wants: that language's, then English (Translingual,
     * listed under `en` too, is left out). Empty when the page has neither.
     */
    fun preferred(sections: List<LanguageSection>, bookLang: String): List<LanguageSection> {
        fun english(s: LanguageSection) = s.code == "en" && s.language.equals("English", ignoreCase = true)
        val book = if (bookLang == "en") emptyList() else sections.filter { inLanguage(it, bookLang) }
        return book + sections.filter(::english)
    }

    /** Whether [section] is in the [bookLang] book's language ([LookupText.language]'s code). */
    private fun inLanguage(section: LanguageSection, bookLang: String): Boolean {
        if (section.code == bookLang) return true
        val same = SAME_LANGUAGE[bookLang] ?: return false
        return section.code in same.codes || same.names.any { it.equals(section.language, ignoreCase = true) }
    }

    private class Filed(val codes: Set<String>, val names: Set<String>)

    /**
     * Book languages en.wiktionary files under another code or name: Croatian, Serbian and Bosnian
     * are one language there, Serbo-Croatian (`sh`), and Norwegian is Norwegian Bokmål (`nb`) or
     * Norwegian Nynorsk (`nn`). The definition answer keys only some languages by code and puts the
     * rest under `other` with their name (Nynorsk is), so the name counts too.
     */
    private val SAME_LANGUAGE: Map<String, Filed> = run {
        val serboCroatian = Filed(setOf("sh", "hr", "sr", "bs"), setOf("Serbo-Croatian", "Croatian", "Serbian", "Bosnian"))
        mapOf(
            "sh" to serboCroatian,
            "hr" to serboCroatian,
            "sr" to serboCroatian,
            "bs" to serboCroatian,
            "no" to Filed(setOf("nb", "no"), setOf("Norwegian Bokmål", "Norwegian")),
            "nn" to Filed(setOf("nn"), setOf("Norwegian Nynorsk", "Norwegian")),
        )
    }

    // --- Wikipedia -------------------------------------------------------------------------------

    fun summary(body: String, lang: String): WikiSummary? {
        val o = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val title = o.string("title")?.trim()?.ifEmpty { null } ?: return null
        val type = o.string("type")
        val extract = o.string("extract_html")?.let(HtmlText::toAnnotated)?.takeIf { it.isNotBlank() }
            ?: AnnotatedString(o.string("extract")?.trim().orEmpty())
        val thumb = o["thumbnail"] as? JsonObject
        val thumbnail = thumb?.string("source")?.takeIf(LookupHttp::isWikimediaImage)
        val urls = o["content_urls"] as? JsonObject
        val page = ((urls?.get("mobile") as? JsonObject)?.string("page") ?: (urls?.get("desktop") as? JsonObject)?.string("page"))
            ?.takeIf { LookupHttp.isWikimediaUrl(it) }
            ?: Urls.wikipediaPage(lang, title).toString()
        return WikiSummary(
            title = title,
            description = o.string("description")?.trim()?.ifEmpty { null },
            extract = extract,
            thumbnail = thumbnail,
            thumbnailWidth = if (thumbnail != null) thumb?.int("width") ?: 0 else 0,
            thumbnailHeight = if (thumbnail != null) thumb?.int("height") ?: 0 else 0,
            url = page,
            disambiguation = type == "disambiguation",
            lang = o.string("lang")?.takeIf { it.isNotBlank() } ?: lang,
        )
    }

    /**
     * The page `search/title` found for [query] whose title is [query] apart from case (or that
     * redirects from it): the search also returns near misses (`Lestrade` finds `Inspector Lestrade`), and a
     * near miss would be the wrong article.
     */
    fun exactSearchHit(body: String, query: String): String? {
        val o = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val wanted = sameTitle(query)
        for (p in (o["pages"] as? JsonArray).orEmpty()) {
            val page = p as? JsonObject ?: continue
            val key = page.string("key") ?: continue
            val titles = listOfNotNull(page.string("title"), page.string("matched_title"), key)
            if (titles.any { sameTitle(it) == wanted }) return key
        }
        return null
    }

    private fun sameTitle(s: String) = s.replace('_', ' ').trim().lowercase()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

}

/** The Wikimedia URLs looked up and opened (the title as one path segment, so `/` and `?` are escaped). */
internal object Urls {
    private const val WIKTIONARY = "en.wiktionary.org"

    private fun title(term: String) = term.trim().replace(' ', '_')

    fun definition(term: String): HttpUrl = HttpUrl.Builder().scheme("https").host(WIKTIONARY)
        .addPathSegments("api/rest_v1/page/definition").addPathSegment(title(term)).build()

    fun wiktionaryPage(term: String, language: String?): HttpUrl = HttpUrl.Builder().scheme("https").host(WIKTIONARY)
        .addPathSegment("wiki").addPathSegment(title(term))
        .apply { if (!language.isNullOrBlank()) fragment(language.replace(' ', '_')) }
        .build()

    fun wiktionarySearch(term: String): HttpUrl = HttpUrl.Builder().scheme("https").host(WIKTIONARY)
        .addPathSegments("w/index.php").addQueryParameter("search", term.trim()).build()

    fun summary(lang: String, title: String): HttpUrl = HttpUrl.Builder().scheme("https").host(wikipedia(lang))
        .addPathSegments("api/rest_v1/page/summary").addPathSegment(title(title)).build()

    fun searchTitle(lang: String, query: String): HttpUrl = HttpUrl.Builder().scheme("https").host(wikipedia(lang))
        .addPathSegments("w/rest.php/v1/search/title").addQueryParameter("q", query.trim()).addQueryParameter("limit", "5").build()

    fun wikipediaPage(lang: String, title: String): HttpUrl = HttpUrl.Builder().scheme("https").host(wikipedia(lang))
        .addPathSegment("wiki").addPathSegment(title(title)).build()

    fun wikipediaSearch(lang: String, query: String): HttpUrl = HttpUrl.Builder().scheme("https").host(wikipedia(lang))
        .addPathSegments("w/index.php").addQueryParameter("search", query.trim()).build()

    private fun wikipedia(lang: String): String {
        val code = LookupText.wikipediaLanguage(lang)
        return "$code.wikipedia.org"
    }

    /** [url] parsed, only when it is https (anything else is refused by [LookupHttp] anyway). */
    fun parse(url: String): HttpUrl? = url.toHttpUrlOrNull()?.takeIf { it.isHttps }
}
