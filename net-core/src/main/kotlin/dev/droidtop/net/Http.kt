package dev.droidtop.net

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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

    const val USER_AGENT = "droidtop/${BuildConfig.VERSION_NAME}"

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeouts: Timeouts = Api.timeouts, maxBytes: Long = 8L * 1024 * 1024, token: String? = null): Response {
        val c = if (token != null) GitHubAuth.open(url, token, timeouts.connectMs, timeouts.readMs) else URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeouts.connectMs
        c.readTimeout = timeouts.readMs
        c.setRequestProperty("User-Agent", USER_AGENT)
        headers.forEach(c::setRequestProperty)
        try {
            val status = c.responseCode
            val hs = c.headerFields.entries.mapNotNull { (name, values) ->
                name?.takeIf { it.startsWith("X-RateLimit-", ignoreCase = true) }?.lowercase()?.let { it to values.firstOrNull() }
            }.toMap()
            if (status !in 200..299) throw HttpException(status, hs, "HTTP $status from $url")
            val length = c.contentLengthLong
            if (length > maxBytes) throw IOException("HTTP response exceeded $maxBytes bytes")
            val bytes = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; if (out.size().toLong() + n > maxBytes) throw IOException("HTTP response exceeded $maxBytes bytes"); out.write(buf, 0, n) }
                out.toByteArray()
            }
            return Response(status, bytes, hs)
        } finally { c.disconnect() }
    }

    fun getJson(url: String, headers: Map<String, String> = emptyMap(), timeouts: Timeouts = Api.timeouts, maxBytes: Long = 8L * 1024 * 1024) = JSONObject(get(url, headers, timeouts, maxBytes).text())

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

    /** A whole file from the start, through [ResumableDownload] (the one downloader); [file] appears only when complete and, if given, matching [expectedSha256]. */
    fun downloadTo(url: String, file: File, expectedSha256: String? = null, onProgress: (Long, Long) -> Unit = { _, _ -> }, isCancelled: () -> Boolean = { false }, headers: Map<String, String> = emptyMap(), timeouts: Timeouts = BigFile.timeouts) {
        val handle = ResumableDownload.Handle()
        try {
            ResumableDownload.fetch(
                url, file, headers, timeouts, resume = false, handle = handle, retries = 0,
                onProgress = { if (isCancelled()) handle.cancel(); onProgress(it.bytes, it.total) },
            )
            if (expectedSha256 != null) {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> val b = ByteArray(64 * 1024); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }
                if (!digest.digest().joinToString("") { "%02x".format(it) }.equals(expectedSha256, true)) { file.delete(); throw IOException("SHA-256 mismatch") }
            }
        } catch (t: Throwable) {
            ResumableDownload.discard(file)
            throw t
        }
    }
}
