package io.github.ottershelf.feature.reader.lookup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A GET to Wikimedia. */
interface WikimediaRemote {
    /**
     * The body of a 200; null when there is nothing to find: no such page (404, 410) or a title the
     * API won't take (400, 414). Throws an IOException for anything else: offline, a timeout, a 403
     * (Wikimedia turning the app away), a 429 or a 5xx, so the part offers Retry and "no entry" is
     * never remembered for a word that has one.
     */
    suspend fun get(url: HttpUrl): String?
}

/**
 * Looks words and phrases up (the rules are tested in `LookupRepositoryTest`), remembering every
 * answer in memory for as long as it lives (the reader's [LookupViewModel]: one reading session).
 *
 * **Dictionary** (en.wiktionary.org, every language's entries with English definitions): the term
 * as selected (punctuation and quotes around it already stripped), else lower case, else simple
 * English inflections of a single word ([LookupText.inflections]). A page counts when it has the
 * book's language or English; a capitalised word that is only a proper noun there (`Running`, a
 * surname) also asks for the lower case word, and shows both. A page with neither language is kept
 * as a last resort (a French word in an English book). A definition that is only a form of another
 * word ("simple past of walk") brings that word's senses too, as the web reader does. When one of
 * the words asked for fails (a timeout), what the others found may not be the answer: unless one
 * of them is a page in the book's language or English, that fails too (Retry); if it is, it is
 * shown but not remembered, so the next lookup asks again for the whole answer.
 *
 * **Wikipedia**: the page summary in the book language's Wikipedia when it has one, then English;
 * a missing title is searched for, and taken only when the hit has the same title but for case.
 *
 * Call it off the main thread: Wikimedia's answers are parsed where it runs, and the page for a
 * common word (`set`, `run`) is large.
 */
internal class LookupRepository(private val remote: WikimediaRemote) {

    private class Cached<T>(val value: T?)

    private val dictionaryCache = ConcurrentHashMap<String, Cached<DictionaryResult>>()
    private val wikipediaCache = ConcurrentHashMap<String, Cached<WikiSummary>>()
    private val pages = ConcurrentHashMap<String, Cached<Page>>()

    /** The dictionary answer for [term] ([LookupText.clean]ed) in a [bookLang] book; null: not found. */
    suspend fun dictionary(term: String, bookLang: String): DictionaryResult? {
        if (term.isBlank()) return null
        val key = "$bookLang|$term"
        dictionaryCache[key]?.let { return it.value }
        val found = findDefinitions(term, bookLang)
        if (found.complete) dictionaryCache[key] = Cached(found.result)
        return found.result
    }

    /** The Wikipedia summary for [term]; null: no article. */
    suspend fun wikipedia(term: String, bookLang: String): WikiSummary? {
        if (term.isBlank()) return null
        val key = "$bookLang|$term"
        wikipediaCache[key]?.let { return it.value }
        return findArticle(term, bookLang).also { wikipediaCache[key] = Cached(it) }
    }

    // --- dictionary ------------------------------------------------------------------------------

    private class Page(val word: String, val sections: List<LanguageSection>)

    private enum class Strength { None, Other, ProperOnly, Strong }

    /** An answer (null: not found); [complete] when no request it depended on failed, so it can be remembered. */
    private class Found(val result: DictionaryResult?, val complete: Boolean)

    private suspend fun findDefinitions(term: String, bookLang: String): Found {
        val lower = term.lowercase()
        val stages = buildList {
            add(listOfNotNull(term, lower.takeIf { it != term }))
            if (' ' !in term) add(LookupText.inflections(lower).take(MAX_INFLECTIONS))
        }
        var fallback: Page? = null
        var properNouns: Page? = null
        var failure: IOException? = null
        for (stage in stages) {
            if (stage.isEmpty()) continue
            val results = coroutineScope { stage.map { word -> async { attempt { page(word) } } }.awaitAll() }
            for (r in results) {
                val page = r.getOrElse { e ->
                    if (failure == null) failure = e as? IOException ?: IOException(e)
                    null
                } ?: continue
                when (strength(page, bookLang, term, lower)) {
                    // An earlier word that failed might have been the answer, or its proper noun.
                    Strength.Strong -> return build(page, bookLang, properNouns, complete = failure == null)
                    Strength.ProperOnly -> {
                        if (properNouns == null) properNouns = page
                        if (fallback == null) fallback = page
                    }
                    Strength.Other -> if (fallback == null) fallback = page
                    Strength.None -> {}
                }
            }
            // Offline: every request of this stage failed, and the next would too.
            if (results.all { it.isFailure } && fallback == null) throw failure!!
        }
        // A word asked for failed: the proper noun or other language found instead may not be the
        // answer (`chat` timed out, `Chat` is a surname), so say it failed and offer Retry.
        failure?.let { throw it }
        fallback?.let { return build(it, bookLang, null, complete = true) }
        return Found(null, complete = true)
    }

    private fun strength(page: Page, bookLang: String, term: String, lower: String): Strength {
        val preferred = WikimediaParsing.preferred(page.sections, bookLang)
        if (preferred.isEmpty()) return if (page.sections.isEmpty()) Strength.None else Strength.Other
        val onlyProper = preferred.all { s -> s.blocks.all { it.partOfSpeech.equals("Proper noun", ignoreCase = true) } }
        return if (onlyProper && page.word == term && lower != term) Strength.ProperOnly else Strength.Strong
    }

    private suspend fun build(page: Page, bookLang: String, properNouns: Page?, complete: Boolean): Found {
        val preferred = WikimediaParsing.preferred(page.sections, bookLang)
        var sections = preferred.ifEmpty { page.sections.take(MAX_OTHER_LANGUAGES) }
        if (properNouns != null && properNouns !== page) {
            sections = append(sections, WikimediaParsing.preferred(properNouns.sections, bookLang))
        }
        val (withForms, formsComplete) = withFormsOf(page.word, sections)
        val result = DictionaryResult(page.word, withForms, Urls.wiktionaryPage(page.word, withForms.firstOrNull()?.language).toString())
        return Found(result, complete && formsComplete)
    }

    /** [sections] with each extra section's blocks added to the section of its language (or after them). */
    private fun append(sections: List<LanguageSection>, extra: List<LanguageSection>): List<LanguageSection> {
        val out = sections.toMutableList()
        for (e in extra) {
            val i = out.indexOfFirst { it.language == e.language }
            if (i >= 0) out[i] = out[i].copy(blocks = out[i].blocks + e.blocks) else out += e
        }
        return out
    }

    /**
     * The words that [sections]' parts of speech are only forms of (their first definition says so:
     * "plural of berry"), looked up and added under their own headword. At most [MAX_FORMS_OF];
     * one that fails or has no page is left out. Second: false when one failed (so the answer is
     * shown without it, but not remembered).
     */
    private suspend fun withFormsOf(word: String, sections: List<LanguageSection>): Pair<List<LanguageSection>, Boolean> {
        val wanted = sections.flatMap { s -> s.blocks.mapNotNull { b -> b.senses.firstOrNull()?.formOf?.let { s.language to it } } }
            .filter { (_, lemma) -> !lemma.equals(word, ignoreCase = true) }
            .distinctBy { (_, lemma) -> lemma.lowercase() }
            .take(MAX_FORMS_OF)
        if (wanted.isEmpty()) return sections to true
        val found = coroutineScope { wanted.map { (_, lemma) -> async { attempt { page(lemma) } } }.awaitAll() }
        val extra = wanted.zip(found).mapNotNull { (want, result) ->
            val (language, _) = want
            result.getOrNull()?.sections?.firstOrNull { it.language == language }?.let { it.copy(blocks = it.blocks.take(MAX_FORM_BLOCKS)) }
        }
        return append(sections, extra) to found.none { it.isFailure }
    }

    private suspend fun page(word: String): Page? {
        pages[word]?.let { return it.value }
        val body = remote.get(Urls.definition(word))
        val page = body?.let { WikimediaParsing.sections(WikimediaParsing.definitions(it), word) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { Page(word, it) }
        pages[word] = Cached(page)
        return page
    }

    // --- Wikipedia -------------------------------------------------------------------------------

    private suspend fun findArticle(term: String, bookLang: String): WikiSummary? {
        val wiki = LookupText.wikipediaLanguage(bookLang)
        for (lang in listOf(wiki, "en").distinct()) {
            summary(lang, term)?.let { return it }
            val hit = remote.get(Urls.searchTitle(lang, term))?.let { WikimediaParsing.exactSearchHit(it, term) } ?: continue
            if (hit.replace('_', ' ') != term) summary(lang, hit)?.let { return it }
        }
        return null
    }

    private suspend fun summary(lang: String, title: String): WikiSummary? =
        remote.get(Urls.summary(lang, title))?.let { WikimediaParsing.summary(it, lang) }

    /** [block]'s result; a cancellation is passed on, never caught. */
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private companion object {
        const val MAX_INFLECTIONS = 6
        const val MAX_OTHER_LANGUAGES = 2
        const val MAX_FORMS_OF = 2
        const val MAX_FORM_BLOCKS = 3
    }
}
