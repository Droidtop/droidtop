package dev.droidtop.pluginhost

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * The user's own GitHub token as it is used for plugin sources (docs/SPEC.md
 * 12a "GitHub token"): one place decides which requests may carry it, and
 * one place opens a connection with it. A token is attached ONLY to https
 * requests for [TOKEN_HOSTS]; a redirect is followed by hand so the header
 * is decided again for every hop (a private release asset answers with a
 * redirect to a pre-signed objects.githubusercontent.com URL, which must
 * not be given the token: that host rejects requests that carry both a
 * signature and an Authorization header). With no token, behaviour is the
 * unauthenticated request it always was.
 */
object GitHubAuth {
    /** The hosts a token may be sent to. Every other host, including the signed-URL redirect target, gets none. */
    val TOKEN_HOSTS: Set<String> = setOf("api.github.com", "github.com", "raw.githubusercontent.com")

    private const val MAX_REDIRECTS = 5
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    /** The `Authorization` header value for [url], or null when [token] is blank or [url] is not an https request for a [TOKEN_HOSTS] host. */
    fun authorizationFor(url: String, token: String?): String? {
        val clean = token?.trim().orEmpty()
        if (clean.isEmpty()) return null
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        val host = uri.host?.lowercase() ?: return null
        return if (host in TOKEN_HOSTS) "Bearer $clean" else null
    }

    /** Whether [url] is a private-release-asset API URL, which needs `Accept: application/octet-stream` to return the file instead of its JSON description. */
    fun isApiAssetUrl(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        return uri.host.equals("api.github.com", ignoreCase = true) && uri.path.orEmpty().contains("/releases/assets/")
    }

    /**
     * Opens [url] and returns a connection whose response code is a final
     * one (redirects followed by hand, https only, at most [MAX_REDIRECTS]
     * hops). The caller reads the body and calls `disconnect()`.
     */
    fun open(url: String, token: String?, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            authorizationFor(current, token)?.let { header ->
                connection.setRequestProperty("Authorization", header)
                if (isApiAssetUrl(current)) connection.setRequestProperty("Accept", "application/octet-stream")
            }
            val code = connection.responseCode
            if (code !in REDIRECTS) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            val next = location?.let { runCatching { URL(URL(current), it).toString() }.getOrNull() }
                ?: error("HTTP $code from $current without a usable Location")
            require(next.startsWith("https://", ignoreCase = true)) { "a redirect to a non-https address was refused" }
            current = next
        }
        error("too many redirects from $url")
    }
}
