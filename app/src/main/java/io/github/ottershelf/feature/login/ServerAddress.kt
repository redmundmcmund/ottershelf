package io.github.ottershelf.feature.login

import io.github.ottershelf.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The server address as it is kept: `https://`, the host in lower case, a port only when it isn't
 * 443, then the path without a trailing slash. The debug build alone also takes `http://` for a
 * local test server ([DEBUG_HTTP_HOSTS]: the emulator's host machine and loopback, the hosts its
 * network security config lets through), kept as `http://host:port`. The account key (and with it the user's downloads and
 * whatever is queued offline) is built from the kept address, so it mustn't depend on how the
 * address was typed.
 */
internal object ServerAddress {

    /** The hosts the debug build may reach over plain http (src/debug/res/xml/network_security_config.xml). */
    val DEBUG_HTTP_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1")

    /**
     * [typed] in its kept form, or null if it can't be a server's address: not https (or, when
     * [debugHttp], http to one of [DEBUG_HTTP_HOSTS]), not a URL, or with a user, query or fragment
     * (the app's paths go after it).
     */
    fun canonical(typed: String, debugHttp: Boolean = BuildConfig.DEBUG): String? {
        val url = typed.trim().toHttpUrlOrNull() ?: return null
        val http = url.scheme == "http" && debugHttp && url.host in DEBUG_HTTP_HOSTS
        if ((url.scheme != "https" && !http) || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null
        ) return null
        val host = if (':' in url.host) "[${url.host}]" else url.host // IPv6 comes without brackets
        if (http) return "http://$host:${url.port}${url.encodedPath.trimEnd('/')}"
        val port = if (url.port == 443) "" else ":${url.port}"
        return "https://$host$port${url.encodedPath.trimEnd('/')}"
    }

    /** Whether [typed] asks for a connection that isn't encrypted (and isn't the debug build's local http). */
    fun insecure(typed: String, debugHttp: Boolean = BuildConfig.DEBUG): Boolean {
        if (typed.startsWith("https://", ignoreCase = true)) return false
        if (!debugHttp || !typed.startsWith("http://", ignoreCase = true)) return true
        return typed.toHttpUrlOrNull()?.host !in DEBUG_HTTP_HOSTS
    }

    /**
     * What to sign in at and keep for [typed]: [stored] as it is when it names the same server, so
     * an account signed in before addresses were tidied keeps its key however the user types it now;
     * otherwise the [canonical] form. Null if [typed] can't be a server's address.
     */
    fun resolve(typed: String, stored: String?, debugHttp: Boolean = BuildConfig.DEBUG): String? {
        val canonical = canonical(typed, debugHttp) ?: return null
        return stored?.takeIf { canonical(it, debugHttp) == canonical } ?: canonical
    }
}
