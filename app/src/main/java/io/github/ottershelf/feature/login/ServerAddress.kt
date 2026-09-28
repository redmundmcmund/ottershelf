package io.github.ottershelf.feature.login

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The server address as it is kept: `https://`, the host in lower case, a port only when it isn't
 * 443, then the path without a trailing slash. The account key (and with it the user's downloads and
 * whatever is queued offline) is built from the kept address, so it mustn't depend on how the
 * address was typed.
 */
internal object ServerAddress {

    /**
     * [typed] in its kept form, or null if it can't be a server's address: not https, not a URL, or
     * with a user, query or fragment (the app's paths go after it).
     */
    fun canonical(typed: String): String? {
        val url = typed.trim().toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null
        ) return null
        val host = if (':' in url.host) "[${url.host}]" else url.host // IPv6 comes without brackets
        val port = if (url.port == 443) "" else ":${url.port}"
        return "https://$host$port${url.encodedPath.trimEnd('/')}"
    }

    /**
     * What to sign in at and keep for [typed]: [stored] as it is when it names the same server, so
     * an account signed in before addresses were tidied keeps its key however the user types it now;
     * otherwise the [canonical] form. Null if [typed] can't be a server's address.
     */
    fun resolve(typed: String, stored: String?): String? {
        val canonical = canonical(typed) ?: return null
        return stored?.takeIf { canonical(it) == canonical } ?: canonical
    }
}
