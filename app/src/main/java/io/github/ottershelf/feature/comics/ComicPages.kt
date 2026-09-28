package io.github.ottershelf.feature.comics

import android.content.Context
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Options
import coil3.request.crossfade
import io.github.ottershelf.core.download.OfflineFiles
import io.github.ottershelf.core.network.Api
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okio.Buffer
import okio.ForwardingSource
import okio.Path.Companion.toOkioPath
import okio.Source
import okio.buffer
import okio.source
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.text.Collator
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Where a comic's pages come from: the server, which serves every page of a CBZ, CBR or CB7 as an
 * image (`cbz/files/:fileId/pages/:n`, extracting the archive itself), or the downloaded CBZ
 * (offline copies of comics are always CBZ, see core.format.BookFormats.canKeepOffline).
 */
interface ComicSource {
    val pageCount: Int

    /** What Coil loads for page [index] (0-based). */
    fun model(index: Int): Any?

    /** What the memory-cache key of every page of this comic starts with ([ComicImages.forget]), or null. */
    val cacheKeyPrefix: String? get() = null

    /** `GET cbz/files/:fileId/pages/:n` with the account's token (the reader's [ComicImages] loader). */
    class Server(private val api: Api, private val fileId: Long, override val pageCount: Int) : ComicSource {
        private val base = runCatching { api.serverUrl("/api/v1/cbz/files/$fileId/pages/") }.getOrNull()
        override fun model(index: Int): Any? = base?.let { "$it$index" }

        /** A page's key is its URL. */
        override val cacheKeyPrefix: String? get() = base
    }

    /** The pages of a downloaded CBZ, in the server's order ([LocalCbz.pages]), so page numbers match. */
    class Local(val file: File, private val entries: List<String>) : ComicSource {
        override val pageCount: Int get() = entries.size
        override fun model(index: Int): Any? = entries.getOrNull(index)?.let { LocalComicPage(file, it) }
        override val cacheKeyPrefix: String get() = LocalPageFetcher.keyPrefix(file)
    }
}

/** One image inside a downloaded CBZ, for Coil ([LocalPageFetcher]). */
data class LocalComicPage(val file: File, val entry: String)

/**
 * The page list of a CBZ exactly as the server builds it (cbz.service.ts `getCbzIndex`): the entries
 * it counts as pages ([OfflineFiles.isCbzPage]: files only, no hidden path parts, the server's image
 * extensions, stored or deflated with some bytes; a download is kept only with one), in natural
 * order (`localeCompare(numeric, sensitivity: base)`: digit runs by value, case and accents
 * ignored). The same order means the same page numbers online and offline, so progress means one
 * thing.
 */
object LocalCbz {
    /** Reads the zip's directory: call off the main thread. Throws if it isn't a readable zip. */
    fun pages(file: File): List<String> = ZipFile(file).use { zip ->
        pages(zip.entries().asSequence().map { Entry(it.name, it.isDirectory, it.method, it.compressedSize) }.toList())
    }

    data class Entry(val name: String, val directory: Boolean, val method: Int, val compressedSize: Long)

    fun pages(entries: List<Entry>): List<String> = entries
        .filter { e -> OfflineFiles.isCbzPage(e.name, e.directory, e.method, e.compressedSize) }
        .map { it.name }
        .sortedWith(NaturalOrder)

    /**
     * JavaScript's `a.localeCompare(b, undefined, {numeric: true, sensitivity: 'base'})`: runs of
     * digits compare by value, the text between them by the root collation at primary strength (case
     * and accents ignored). Equal names keep the archive's order (the sort is stable, as V8's is).
     */
    object NaturalOrder : Comparator<String> {
        private val collator: Collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.PRIMARY }

        override fun compare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                if (a[i].isDigit() && b[j].isDigit()) {
                    val ei = digitsEnd(a, i)
                    val ej = digitsEnd(b, j)
                    val c = compareNumbers(a.substring(i, ei), b.substring(j, ej))
                    if (c != 0) return c
                    i = ei
                    j = ej
                } else {
                    // Names share long prefixes ("Chapter 012/page_0"): the same character collates
                    // equal at any strength, so it needs no collator call (and no Strings).
                    if (a[i] == b[j]) {
                        i++
                        j++
                        continue
                    }
                    val c = synchronized(collator) { collator.compare(a[i].toString(), b[j].toString()) }
                    if (c != 0) return c
                    i++
                    j++
                }
            }
            // One ran out: the shorter comes first.
            return (a.length - i).compareTo(b.length - j)
        }

        private fun digitsEnd(s: String, from: Int): Int {
            var k = from
            while (k < s.length && s[k].isDigit()) k++
            return k
        }

        private fun compareNumbers(p: String, q: String): Int {
            val a = p.trimStart('0')
            val b = q.trimStart('0')
            if (a.length != b.length) return a.length.compareTo(b.length)
            return a.compareTo(b)
        }
    }
}

/**
 * Reads a [LocalComicPage] out of its zip (Coil runs fetchers on its IO dispatcher). The entry is
 * copied once into the loader's disk cache and read from there as a file, like the server's pages:
 * decoding streams it instead of holding the whole page (tens of MB for a large PNG) in memory, and
 * Telephoto tiles a zoomed page from that file (it subsamples only from the disk cache or a file).
 * Without the cache, or while another request is writing this page, it streams from the zip.
 * Either way a page yields no more than its entry declares, and one declaring more than
 * [OfflineFiles.MAX_CBZ_PAGE_BYTES] is refused ([pageSource]): a zip bomb fails the page (Retry)
 * instead of writing gigabytes into the cache.
 */
class LocalPageFetcher(
    private val data: LocalComicPage,
    private val options: Options,
    private val diskCache: DiskCache?,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val cache = diskCache ?: return streamed()
        val key = key(data)
        if (options.diskCachePolicy.readEnabled) cache.openSnapshot(key)?.let { return cached(cache, key, it) }
        if (options.diskCachePolicy.writeEnabled) {
            val editor = cache.openEditor(key) ?: return streamed()
            val snapshot = try {
                ZipFile(data.file).use { zip ->
                    val entry = zip.getEntry(data.entry) ?: throw FileNotFoundException(data.entry)
                    cache.fileSystem.write(editor.data) { pageSource(zip, entry).use { writeAll(it) } }
                }
                editor.commitAndOpenSnapshot()
            } catch (e: Exception) {
                runCatching { editor.abort() }
                throw e
            }
            if (snapshot != null) return cached(cache, key, snapshot)
        }
        return streamed()
    }

    private fun cached(cache: DiskCache, key: String, snapshot: DiskCache.Snapshot) = SourceFetchResult(
        source = ImageSource(snapshot.data, cache.fileSystem, key, snapshot),
        mimeType = null,
        dataSource = DataSource.DISK,
    )

    /** The entry straight from the zip, which closes with the source. */
    private fun streamed(): FetchResult {
        val zip = ZipFile(data.file)
        try {
            val entry = zip.getEntry(data.entry) ?: throw FileNotFoundException(data.entry)
            val source = object : ForwardingSource(pageSource(zip, entry)) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        zip.close()
                    }
                }
            }
            return SourceFetchResult(
                source = ImageSource(source = source.buffer(), fileSystem = options.fileSystem),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        } catch (e: Throwable) {
            zip.close()
            throw e
        }
    }

    class Factory : Fetcher.Factory<LocalComicPage> {
        override fun create(data: LocalComicPage, options: Options, imageLoader: ImageLoader): Fetcher =
            LocalPageFetcher(data, options, imageLoader.diskCache)
    }

    /** Memory-cache key: the copy (replaced on a new download, so its time is part of it) and the entry. */
    class Key : Keyer<LocalComicPage> {
        override fun key(data: LocalComicPage, options: Options): String = key(data)
    }

    companion object {
        /** The page's cache key (memory and disk): the copy, with its time, and the entry. */
        fun key(data: LocalComicPage): String = "${keyPrefix(data.file)}${data.file.lastModified()}!${data.entry}"

        /** What the key of every page of the copy [file] starts with, whatever its time. */
        fun keyPrefix(file: File): String = "comic:${file.path}:"

        /**
         * [entry]'s bytes, failing past the size its zip directory declares (java.util.zip doesn't
         * check it); an entry declaring more than [OfflineFiles.MAX_CBZ_PAGE_BYTES] isn't read at all.
         */
        internal fun pageSource(zip: ZipFile, entry: ZipEntry): Source {
            val declared = entry.size
            if (declared > OfflineFiles.MAX_CBZ_PAGE_BYTES) throw IOException("Page too large: ${entry.name}")
            val limit = if (declared >= 0) declared else OfflineFiles.MAX_CBZ_PAGE_BYTES
            return LimitedSource(zip.getInputStream(entry).source(), limit)
        }
    }

    /** [delegate], failing once more than [limit] bytes came out of it. */
    private class LimitedSource(delegate: Source, private val limit: Long) : ForwardingSource(delegate) {
        private var total = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = super.read(sink, byteCount)
            if (n > 0) {
                total += n
                if (total > limit) throw IOException("The page inflates past its size")
            }
            return n
        }
    }
}

/**
 * The comics reader's own image loader, process-wide: pages over the account's authenticated client
 * (the server's page URLs are immutable), in their own disk cache (`cacheDir/comics`) so a long
 * comic doesn't push the covers out of theirs, plus the local CBZ fetcher. No crossfade: a page
 * turn shows the page at once. Its requests queue on a dispatcher of their own
 * ([PAGE_REQUESTS_PER_HOST]), so pages never hold up the API calls (progress, settings) or the covers.
 */
object ComicImages {
    /**
     * A few comics' worth. Besides the server's pages it holds a copy of every page read from a
     * downloaded CBZ ([LocalPageFetcher]), a second copy of that comic on the phone. The copies stay
     * when the reader closes: reading the comic again would otherwise write every page again.
     */
    private const val DISK_CACHE_BYTES = 256L * 1024 * 1024
    private const val PAGE_REQUESTS_PER_HOST = 3

    @Volatile private var loader: ImageLoader? = null

    fun loader(context: Context, client: () -> OkHttpClient): ImageLoader =
        loader ?: synchronized(this) {
            loader ?: build(context.applicationContext, client).also { loader = it }
        }

    /**
     * Drops the decoded pages whose keys start with [prefix] (one comic's, [ComicSource.cacheKeyPrefix])
     * from [cache]: full-screen bitmaps of about 10 MB each, which nothing shows once that comic's
     * reader is gone (reopened, its pages decode again from the disk cache). Other comics' pages stay,
     * such as the next one's after Read next. A page still drawn keeps its bitmap (Coil has no pool).
     */
    fun forget(cache: MemoryCache?, prefix: String) {
        if (cache == null || prefix.isEmpty()) return
        // `keys` is a copy, so removing while going through it is safe.
        cache.keys.filter { it.key.startsWith(prefix) }.forEach(cache::remove)
    }

    private fun build(context: Context, client: () -> OkHttpClient): ImageLoader = ImageLoader.Builder(context)
        .components {
            // Asked once, when the first page is fetched: the same client (auth, connections) with its own queue.
            add(OkHttpNetworkFetcherFactory(callFactory = { client().newBuilder().dispatcher(Dispatcher().apply { maxRequestsPerHost = PAGE_REQUESTS_PER_HOST }).build() }))
            add(LocalPageFetcher.Factory())
            add(LocalPageFetcher.Key())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("comics").toOkioPath())
                .maxSizeBytes(DISK_CACHE_BYTES)
                .build()
        }
        .crossfade(false)
        .build()
}
