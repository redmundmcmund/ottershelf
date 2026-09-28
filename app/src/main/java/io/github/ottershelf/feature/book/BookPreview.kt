package io.github.ottershelf.feature.book

import io.github.ottershelf.core.model.BookCard

/**
 * The title and authors a book page shows until it loads, for a book opened from somewhere without
 * a grid card for it (Currently Reading, Book requests), as the Nexus app passed EXTRA_TITLE. No
 * cover: the page keeps its plain cover box rather than flashing the generated one.
 */
data class TitleHint(val title: String?, val authors: List<String> = emptyList())

/**
 * The cards of books just opened from a grid, so the book page shows the title, authors and the
 * (already cached) thumbnail at once while the full page loads: what the Nexus app passed in the
 * intent (EXTRA_TITLE). Books opened from elsewhere leave a [TitleHint]. Memory only, a few dozen
 * at most, per account.
 */
object BookPreview {
    private const val MAX = 32

    private val cards = lru<BookCard>()
    private val hints = lru<TitleHint>()

    @Synchronized
    fun put(account: String, card: BookCard) {
        cards["$account/${card.id}"] = card
    }

    @Synchronized
    fun get(account: String, bookId: Long): BookCard? = cards["$account/$bookId"]

    @Synchronized
    fun putHint(account: String, bookId: Long, hint: TitleHint) {
        hints["$account/$bookId"] = hint
    }

    @Synchronized
    fun hint(account: String, bookId: Long): TitleHint? = hints["$account/$bookId"]

    private fun <T> lru() = object : LinkedHashMap<String, T>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) = size > MAX
    }
}
