package dev.droidtop.net

import dev.droidtop.shared.net.GitHubAuth as SharedGitHubAuth
import java.net.HttpURLConnection

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
    // One mechanism (Droidtop/tracker#194): lib-net's :net decides, and Enginehost uses the same code. These calls keep
    // droidtop's call sites as they were.
    private val shared = SharedGitHubAuth.DEFAULT

    val TOKEN_HOSTS: Set<String> = SharedGitHubAuth.TOKEN_HOSTS

    /** The `Authorization` header for [url], or null when [token] must not go there. */
    fun authorizationFor(url: String, token: String?): String? = shared.authorizationFor(url, token)

    /** Whether [url] is a GitHub API release asset, which wants `Accept: application/octet-stream`. */
    fun isApiAssetUrl(url: String): Boolean = shared.isApiAssetUrl(url)

    /** Opens [url], following redirects by hand and deciding the token for every hop. */
    fun open(
        url: String,
        token: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        headers: Map<String, String> = emptyMap(),
        everyHop: Boolean = false,
    ): HttpURLConnection = shared.open(url, token, connectTimeoutMs, readTimeoutMs, headers, everyHop)

    /** The final address of [url] after its redirects, and the headers a download of it may carry there. */
    fun downloadRequestFor(url: String, token: String?): Pair<String, Map<String, String>> =
        shared.downloadRequestFor(url, token)
}
