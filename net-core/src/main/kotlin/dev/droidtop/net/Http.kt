package dev.droidtop.net

import dev.droidtop.net.sitecheck.SiteGates
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Shared bounded HTTP and single-file download transport. Call from an IO dispatcher. */
object Http {
    data class Timeouts(val connectMs: Int, val readMs: Int)
    object Api { val timeouts = Timeouts(15_000, 30_000) }
    object Media { val timeouts = Timeouts(8_000, 20_000) }
    object BigFile { val timeouts = Timeouts(20_000, 300_000) }
    data class Response(val status: Int, val body: ByteArray, val headers: Map<String, String?>) {
        fun text(): String = body.toString(Charsets.UTF_8)
        fun header(name: String): String? = headers[name.lowercase()]
    }
    class HttpException(val status: Int, val responseHeaders: Map<String, String?>, message: String) : IOException(message) {
        val rateLimitHeaders get() = responseHeaders.filterKeys { it.startsWith("x-ratelimit-") }
    }

    /** A response longer than the caller reads; nothing of it is returned. */
    class TooLarge(val limit: Long) : IOException("HTTP response exceeded $limit bytes")

    const val USER_AGENT = "droidtop/${BuildConfig.VERSION_NAME}"

    /**
     * A long read someone is watching: [onProgress] gets the bytes so far and the length announced (-1 when it was not),
     * [isCancelled] is asked between reads (a cancelled read throws [IOException]), and the body must be whole: fewer
     * bytes than announced is an [IOException], not a silently short answer.
     */
    class Watch(val onProgress: (read: Long, total: Long) -> Unit = { _, _ -> }, val isCancelled: () -> Boolean = { false })

    /** How much of a refusal's body [request] keeps, for the caller's error line. */
    const val ERROR_BODY_MAX_BYTES = 64L * 1024

    /**
     * One request answered with whatever status the server gave: a 2xx body read to [maxBytes] ([TooLarge] past it),
     * any other status's body read to [ERROR_BODY_MAX_BYTES]. With a [token], [GitHubAuth.open] follows the redirects
     * and decides per hop whether the token goes on; without one, HttpURLConnection follows them unless [followRedirects]
     * is false (a request that carries a credential in a header must not be sent on to wherever a redirect points).
     * With a [watch] the 2xx body is read under it.
     */
    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeouts: Timeouts = Api.timeouts,
        maxBytes: Long = 8L * 1024 * 1024,
        token: String? = null,
        followRedirects: Boolean = true,
        watch: Watch? = null,
    ): Response {
        // A site that checks the browser is dealt with in one place for every request (Droidtop/tracker#751): the
        // site's clearance goes on the request, and a check in the answer is passed and the request made once more.
        val gate = SiteGates.current ?: return requestOnce(url, method, headers, body, timeouts, maxBytes, token, followRedirects, watch)
        return gate.run(url, headers) { withClearance -> requestOnce(url, method, withClearance, body, timeouts, maxBytes, token, followRedirects, watch) }
    }

    private fun requestOnce(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeouts: Timeouts,
        maxBytes: Long,
        token: String?,
        followRedirects: Boolean,
        watch: Watch?,
    ): Response {
        val sent = mapOf("User-Agent" to USER_AGENT) + headers
        val c = if (token != null) {
            require(method == "GET" && body == null) { "a token is sent on GET requests only" }
            // GitHubAuth.open connects before it returns, so every header goes in with the request.
            GitHubAuth.open(url, token, timeouts.connectMs, timeouts.readMs, sent)
        } else {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeouts.connectMs
                readTimeout = timeouts.readMs
                instanceFollowRedirects = followRedirects
                requestMethod = method
                sent.forEach(::setRequestProperty)
                if (body != null) {
                    doOutput = true
                    setFixedLengthStreamingMode(body.size)
                    outputStream.use { it.write(body) }
                }
            }
        }
        try {
            val status = c.responseCode
            // The rate-limit headers, and Cloudflare's own word that the answer is a browser check.
            val hs = c.headerFields.entries.mapNotNull { (name, values) ->
                name?.takeIf { it.startsWith("X-RateLimit-", ignoreCase = true) || it.equals("cf-mitigated", ignoreCase = true) }
                    ?.lowercase()?.let { it to values.firstOrNull() }
            }.toMap()
            if (status !in 200..299) {
                val error = runCatching { c.errorStream?.use { readTo(it, ERROR_BODY_MAX_BYTES, truncate = true) } }.getOrNull()
                return Response(status, error ?: ByteArray(0), hs)
            }
            if (c.contentLengthLong > maxBytes) throw TooLarge(maxBytes)
            val total = c.contentLengthLong
            val whole = c.inputStream.use { if (watch == null) readTo(it, maxBytes, truncate = false) else readWatched(it, maxBytes, total, watch) }
            return Response(status, whole, hs)
        } finally { c.disconnect() }
    }

    private fun readWatched(input: java.io.InputStream, limit: Long, total: Long, watch: Watch): ByteArray {
        val out = java.io.ByteArrayOutputStream(if (total in 1..limit) total.toInt() else 64 * 1024)
        val buf = ByteArray(64 * 1024)
        watch.onProgress(0, total)
        while (true) {
            if (watch.isCancelled()) throw IOException("cancelled")
            val n = input.read(buf)
            if (n < 0) break
            if (out.size().toLong() + n > limit) throw TooLarge(limit)
            out.write(buf, 0, n)
            watch.onProgress(out.size().toLong(), total)
        }
        if (total >= 0 && out.size().toLong() != total) throw IOException("the download was interrupted (${out.size()} of $total bytes)")
        return out.toByteArray()
    }

    /**
     * The status [url] answers with and the first [count] bytes of its body, for a look at what a link serves
     * without fetching it: a `Range` for those bytes is asked, and a server that ignores it and answers the whole
     * file costs only the bytes read here (the connection is dropped, not drained).
     */
    fun peek(url: String, count: Int, headers: Map<String, String> = emptyMap(), timeouts: Timeouts = Api.timeouts): Pair<Int, ByteArray> {
        val sent = mapOf("User-Agent" to USER_AGENT, "Range" to "bytes=0-${count - 1}") + headers
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeouts.connectMs
            readTimeout = timeouts.readMs
            sent.forEach(::setRequestProperty)
        }
        try {
            val status = c.responseCode
            if (status !in 200..299) return status to ByteArray(0)
            return status to c.inputStream.use { input ->
                val head = ByteArray(count)
                var n = 0
                while (n < count) {
                    val read = input.read(head, n, count - n)
                    if (read < 0) break
                    n += read
                }
                head.copyOf(n)
            }
        } finally { c.disconnect() }
    }

    /** A 2xx response, or [HttpException] carrying the status and the `X-RateLimit-*` headers. */
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeouts: Timeouts = Api.timeouts, maxBytes: Long = 8L * 1024 * 1024, token: String? = null): Response {
        val response = request(url, headers = headers, timeouts = timeouts, maxBytes = maxBytes, token = token)
        if (response.status !in 200..299) throw HttpException(response.status, response.headers, "HTTP ${response.status} from $url")
        return response
    }

    fun getJson(url: String, headers: Map<String, String> = emptyMap(), timeouts: Timeouts = Api.timeouts, maxBytes: Long = 8L * 1024 * 1024) = JSONObject(get(url, headers, timeouts, maxBytes).text())

    private fun readTo(input: java.io.InputStream, limit: Long, truncate: Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size().toLong() + n > limit) {
                if (!truncate) throw TooLarge(limit)
                out.write(buf, 0, (limit - out.size()).toInt())
                break
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Opens one request for code droidtop does not trust (a plugin's, docs/plugin-api.md 3 D2) and returns the connection
     * with its response ready, for the caller to read and disconnect. Redirects are followed here, never by
     * HttpURLConnection: every URL, the first one included, is passed to [allow] before anything connects to it, and
     * [allow] throws to stop the request. At most [maxRedirects] hops; a redirect turns POST and the rest into GET, as
     * browsers do, except 307 and 308.
     */
    fun openGuarded(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeouts: Timeouts,
        userAgent: String = USER_AGENT,
        maxRedirects: Int = 5,
        allow: (String) -> Unit,
    ): HttpURLConnection {
        var current = url
        var verb = method
        var payload = body
        repeat(maxRedirects + 1) {
            allow(current)
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = timeouts.connectMs
            c.readTimeout = timeouts.readMs
            c.requestMethod = verb
            c.setRequestProperty("User-Agent", userAgent)
            headers.forEach(c::setRequestProperty)
            val sending = payload
            if (sending != null && verb != "GET" && verb != "HEAD") {
                c.doOutput = true
                c.setFixedLengthStreamingMode(sending.size)
                c.outputStream.use { it.write(sending) }
            }
            val status = c.responseCode
            val location = c.getHeaderField("Location")
            if (status !in 300..399 || status == 304 || location == null) return c
            c.disconnect()
            current = URL(URL(current), location).toString()
            if (status != 307 && status != 308) {
                verb = if (verb == "HEAD") "HEAD" else "GET"
                payload = null
            }
        }
        throw IOException("more than $maxRedirects redirects from $url")
    }
}
