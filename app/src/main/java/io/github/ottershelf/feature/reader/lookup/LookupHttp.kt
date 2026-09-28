package io.github.ottershelf.feature.reader.lookup

import android.content.Context
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.serviceLoaderEnabled
import kotlinx.coroutines.suspendCancellableCoroutine
import io.github.ottershelf.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The HTTP client for Wikimedia (Wiktionary, Wikipedia and their images), and nothing else.
 *
 * It is built from a bare `OkHttpClient.Builder()`: not the app's `Api.client`, and not a
 * `newBuilder()` of it, so none of its interceptors (the bearer token), its authenticator (the
 * refresh) or anything else of the account can ever reach these requests. No cookie jar, no cache.
 * [WikimediaGuard] runs before every request and again on every redirect hop: https only, to a
 * Wikimedia host only (the BookOrbit server, or a look-alike such as `wikipedia.org.evil.example`,
 * is refused before a byte is sent), no credential headers, and the descriptive User-Agent that
 * Wikimedia's API policy asks for. Short timeouts: a lookup fails fast and offers Retry.
 */
internal object LookupHttp {

    /** "Ottershelf/0.1.12 (https://github.com/redmundmcmund/ottershelf) okhttp/5.5.0": the app, its version and where to reach the project, as Wikimedia's User-Agent policy asks. */
    val userAgent: String = "Ottershelf/${BuildConfig.VERSION_NAME} (${BuildConfig.PROJECT_URL}) okhttp/${OkHttp.VERSION}"

    val client: OkHttpClient by lazy { newClient() }

    /** A new client; [allowHost] only differs in tests. */
    fun newClient(allowHost: (String) -> Boolean = ::isWikimediaHost): OkHttpClient {
        val guard = WikimediaGuard(userAgent, allowHost)
        return OkHttpClient.Builder()
            .addInterceptor(guard)
            .addNetworkInterceptor(guard)
            .followSslRedirects(false)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private val WIKIMEDIA_DOMAINS = listOf("wikipedia.org", "wiktionary.org", "wikimedia.org")

    /** `en.wiktionary.org`, `de.wikipedia.org`, `upload.wikimedia.org`...: the domain or a subdomain of it, compared on the parsed host. */
    fun isWikimediaHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return WIKIMEDIA_DOMAINS.any { h == it || h.endsWith(".$it") }
    }

    /** An https URL on a Wikimedia host, with no user info (so a link or image from an answer can be trusted). */
    fun isWikimediaUrl(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        return u.isHttps && u.username.isEmpty() && u.password.isEmpty() && isWikimediaHost(u.host)
    }

    /** Wikipedia's thumbnails come from upload.wikimedia.org and thumb.wikimedia.org. */
    fun isWikimediaImage(url: String): Boolean = isWikimediaUrl(url) && url.toHttpUrlOrNull()?.host?.endsWith(".wikimedia.org") == true
}

/** See [LookupHttp]. Both an application interceptor (before connecting) and a network one (each redirect hop). */
internal class WikimediaGuard(private val userAgent: String, private val allowHost: (String) -> Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (!url.isHttps) throw IOException("Look up only uses https")
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || !allowHost(url.host)) {
            throw IOException("Look up only talks to Wikimedia, not ${url.host}")
        }
        val clean = request.newBuilder()
            .removeHeader("Authorization")
            .removeHeader("Proxy-Authorization")
            .removeHeader("Cookie")
            .header("User-Agent", userAgent)
            .build()
        return chain.proceed(clean)
    }
}

/** [WikimediaRemote] over [LookupHttp.client] (or another client, in tests). */
internal class OkHttpWikimediaRemote(private val client: OkHttpClient = LookupHttp.client) : WikimediaRemote {
    override suspend fun get(url: HttpUrl): String? = suspendCancellableCoroutine { cont ->
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json").get().build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        when {
                            it.isSuccessful -> it.body.string()
                            // No such page, or a title the API won't take ("<", too long): nothing found.
                            it.code in NOTHING_FOUND -> null
                            // Anything else is a failure, not an answer: a 403 (Wikimedia turning the
                            // app away), a 429, a 5xx. It offers Retry and isn't remembered.
                            else -> throw IOException("HTTP ${it.code}")
                        }
                    }
                }
                if (!cont.isActive) return
                result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }
        })
    }

    private companion object {
        /** No such page (404, 410), or a title the API won't take (400, 414): the same answer every time. */
        val NOTHING_FOUND = setOf(400, 404, 410, 414)
    }
}

/**
 * Coil for Wikipedia's thumbnails: over [LookupHttp.client] (never the app's image loader, which
 * uses the account's client), without the service-loaded fetchers, a small memory cache and no
 * disk cache.
 */
internal object LookupImages {
    @Volatile private var loader: ImageLoader? = null

    fun loader(context: Context): ImageLoader =
        loader ?: synchronized(this) {
            loader ?: build(context.applicationContext).also { loader = it }
        }

    private fun build(context: Context): ImageLoader = ImageLoader.Builder(context)
        .serviceLoaderEnabled(false)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { LookupHttp.client })) }
        .memoryCache { MemoryCache.Builder().maxSizeBytes(8L * 1024 * 1024).build() }
        .diskCache(null as coil3.disk.DiskCache?)
        .crossfade(true)
        .build()
}
