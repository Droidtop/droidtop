package dev.droidtop.net.engine

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps a request's credentials on the site they were made for when the download engine follows redirects
 * (docs/SPEC.md 12a "One download engine", Droidtop/tracker#417).
 *
 * aria2 sends every `header` option it was given to every address it is redirected to, so a download that carries
 * an `Authorization` or `Cookie` header (a sign-in site's session, a private release asset) would hand it to
 * whatever host the first answer pointed at. droidtop's earlier downloader followed redirects by hand and dropped
 * those headers, and `Referer` and `Origin` with them, when a redirect left the site (another host, or a step down
 * from https). This does the same walk before the engine is given the address: [resolve] asks each address with
 * `HEAD` (a `GET` of one byte where a server will not answer `HEAD`; neither body is read), follows its redirects, and
 * returns the address that does not redirect with the headers that may go to it. A request with none of those headers
 * is not walked at all, since there is nothing to keep back. Blocking: call from an IO dispatcher.
 */
object RedirectGuard {
    /** The address to give the engine, and the headers that may go to it. */
    class Resolved(val url: String, val headers: Map<String, String>)

    /** What one address answered: its status and its `Location`. */
    class Answer(val status: Int, val location: String?)

    private val SENSITIVE = setOf("authorization", "proxy-authorization", "cookie")

    /** Headers that say where a request came from; kept on the same site, dropped when a redirect leaves it. */
    private val CONTEXTUAL = setOf("referer", "origin")

    private const val MAX_REDIRECTS = 5

    /** Whether any of [headers] needs the walk. */
    fun needsWalk(headers: Map<String, String>): Boolean = headers.keys.any { it.lowercase() in SENSITIVE || it.lowercase() in CONTEXTUAL }

    /** [headers] as they may go from [from] to [to]: all of them on the same site, without the identifying ones off it. */
    fun carry(headers: Map<String, String>, from: URL, to: URL): Map<String, String> {
        val sameSite = to.host.equals(from.host, ignoreCase = true) && !(from.protocol == "https" && to.protocol != "https")
        return if (sameSite) headers else headers.filterKeys { it.lowercase() !in SENSITIVE && it.lowercase() !in CONTEXTUAL }
    }

    /**
     * Walks [url]'s redirects with [ask] (the network, or a test's own answers) and returns where the engine may go.
     * A status that is neither a success nor a redirect ends the walk at that address: the engine will meet the same
     * answer and report it.
     */
    fun resolve(url: String, headers: Map<String, String>, ask: (url: String, headers: Map<String, String>, method: String) -> Answer = { u, h, m -> network(u, h, m) }): Resolved {
        if (!needsWalk(headers)) return Resolved(url, headers)
        var current = url
        var sent = headers
        repeat(MAX_REDIRECTS + 1) {
            var answer = ask(current, sent, "HEAD")
            if (!isSuccess(answer) && !isRedirect(answer)) answer = ask(current, sent, "GET")
            if (!isRedirect(answer)) return Resolved(current, sent)
            val here = URL(current)
            val next = URL(here, answer.location!!)
            sent = carry(sent, here, next)
            current = next.toString()
        }
        throw IOException("more than $MAX_REDIRECTS redirects from $url")
    }

    private fun isSuccess(answer: Answer) = answer.status in 200..299

    private fun isRedirect(answer: Answer) = answer.status in 300..399 && answer.status != 304 && answer.location != null

    private fun network(url: String, headers: Map<String, String>, method: String): Answer {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.requestMethod = method
            headers.forEach { (name, value) -> c.setRequestProperty(name, value) }
            // A GET asks for its first byte only; no body is read either way.
            if (method == "GET") c.setRequestProperty("Range", "bytes=0-0")
            return Answer(c.responseCode, c.getHeaderField("Location"))
        } finally {
            c.disconnect()
        }
    }
}
