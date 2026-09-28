package io.github.ottershelf.feature.bookedit

import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.github.ottershelf.AppContainer
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.core.sync.withDetails
import io.github.ottershelf.feature.book.BookPreview
import io.github.ottershelf.feature.book.TitleHint

/**
 * Announces a book edited on this phone, so everything already showing it follows at once (no
 * restart, no pull):
 * - `ReadingChanges.editedBooks` (the book page, the library grids and lists, author and series
 *   pages, the Dashboard's shelves and Currently Reading), which also makes the Dashboard ask again;
 * - [BookPreview]'s card and title hint for the book (the page shows them while it loads);
 * - a downloaded copy's page, title, authors and, after a cover change, its cover
 *   (`Downloads.updateKept`, so Downloaded and the offline page follow, after a restart too);
 * - after a cover change, the image caches ([CoverCache]).
 *
 * The book's `updatedAt` (changed by a cover change, though not by a save of the authors alone)
 * versions its thumbnail and cover URLs, so an edited card asks for the new cover by itself;
 * [CoverCache] is for the URLs that carry no version.
 */
internal class BookEditPublisher(private val container: AppContainer, private val imageLoader: () -> ImageLoader?) {

    /** [book] as the server has it now; [before]: as it was when the screen loaded it. */
    fun publish(book: BookDetail, before: BookDetail?, coverChanged: Boolean) {
        if (coverChanged) {
            val api = container.api
            runCatching {
                val unversioned = api.unversionedThumbnailUrl(book.id)
                val versions = listOfNotNull(before?.updatedAt, book.updatedAt).distinct()
                    .flatMap { listOf(api.thumbnailUrl(book.id, it), api.coverUrl(book.id, it)) }
                imageLoader()?.let { CoverCache.forget(it, CoverCache.bookPrefix(unversioned), unversioned, versions) }
            }
        }
        container.readingChanges.bookEdited(book)
        runCatching {
            val account = container.session.accountKey()
            BookPreview.get(account, book.id)?.let { BookPreview.put(account, it.withDetails(book)) }
            BookPreview.hint(account, book.id)?.let { BookPreview.putHint(account, book.id, TitleHint(book.title, book.authors.map { a -> a.name })) }
        }
        val downloads = container.downloads
        container.appScope.launch(Dispatchers.IO) { runCatching { downloads.updateKept(book, artwork = coverChanged) } }
    }
}

/**
 * Forgets a book's cover images in the app's image loader after its cover changed. Versioned URLs
 * (`?t=` the book's `updatedAt`) move on by themselves; but the series cards ask for thumbnails with
 * no version, and the Dashboard's Currently Reading caches them by day (`<url>#<day>`,
 * feature.home's DailyCover), so without this they would show the old cover until the caches let
 * it go. Old versions are forgotten too, so a screen still holding an old card fetches the new
 * image (the server serves the current cover whatever the version says).
 */
object CoverCache {
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Every URL of this book's images starts with this: `.../api/v1/books/<id>/`. */
    fun bookPrefix(unversionedThumbnail: String): String = unversionedThumbnail.substringBeforeLast('/') + "/"

    fun forget(loader: ImageLoader, prefix: String, unversioned: String, versioned: List<String>, now: Long = System.currentTimeMillis()) {
        loader.memoryCache?.let { cache ->
            // A copy of the keys: removing while going through them.
            cache.keys.toList().filter { isForBook(it.key, prefix) }.forEach(cache::remove)
        }
        loader.diskCache?.let { cache -> diskKeys(unversioned, versioned, now).forEach { runCatching { cache.remove(it) } } }
    }

    /** Whether a memory cache key is one of this book's images: its URLs, or the book page's `tint:` copies of them. */
    fun isForBook(key: String, prefix: String): Boolean = key.startsWith(prefix) || key.startsWith("tint:$prefix")

    /** The disk cache's keys to drop: the unversioned thumbnail, its day keys (today and yesterday), the old versions. */
    fun diskKeys(unversioned: String, versioned: List<String>, now: Long): List<String> {
        val day = now / DAY_MS
        return (listOf(unversioned, "$unversioned#$day", "$unversioned#${day - 1}") + versioned).distinct()
    }
}
