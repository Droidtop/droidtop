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

    fun downloadTo(url: String, file: File, expectedSha256: String? = null, onProgress: (Long, Long) -> Unit = { _, _ -> }, isCancelled: () -> Boolean = { false }, headers: Map<String, String> = emptyMap(), timeouts: Timeouts = BigFile.timeouts) {
        val part = File(file.parentFile, file.name + ".part")
        part.delete()
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = timeouts.connectMs; c.readTimeout = timeouts.readMs; c.setRequestProperty("User-Agent", USER_AGENT); headers.forEach(c::setRequestProperty)
            try {
                val status = c.responseCode
                if (status !in 200..299) throw HttpException(status, c.headerFields.entries.mapNotNull { (name, values) -> name?.takeIf { it.startsWith("X-RateLimit-", true) }?.lowercase()?.let { it to values.firstOrNull() } }.toMap(), "HTTP $status from $url")
                val total = c.contentLengthLong; val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
                c.inputStream.use { input -> part.outputStream().buffered().use { out -> val b = ByteArray(64 * 1024); while (true) { if (isCancelled()) throw IOException("download cancelled"); val n = input.read(b); if (n < 0) break; out.write(b, 0, n); digest.update(b, 0, n); count += n; onProgress(count, total) } } }
                if (expectedSha256 != null && digest.digest().joinToString("") { "%02x".format(it) }.equals(expectedSha256, true).not()) throw IOException("SHA-256 mismatch")
            } finally { c.disconnect() }
            file.parentFile?.mkdirs()
            if (!part.renameTo(file)) throw IOException("could not move downloaded file into place")
        } catch (t: Throwable) { part.delete(); throw t }
    }
}
