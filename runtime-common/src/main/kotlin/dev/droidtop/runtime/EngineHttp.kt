package dev.droidtop.runtime

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/**
 * The HTTP/1.1 the engine's API sockets speak (docs/SPEC.md 3d, "The Docker Engine API"; Droidtop/tracker#555 D14):
 * enough of it for Docker's and Podman's clients, which send one request at a time on a kept-alive connection, a body
 * by Content-Length or chunks, and read a response by Content-Length. Every limit is a bound on what a container can
 * make droidtop read: a request line and headers of at most [MAX_HEAD_BYTES], a body of at most [MAX_BODY_BYTES].
 */
object EngineHttp {
    const val MAX_HEAD_BYTES = 64 * 1024
    const val MAX_BODY_BYTES = 1024 * 1024

    data class Request(
        val method: String,
        /** The path without its query, `%`-decoded. */
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        /** The client wants the connection closed after this request. */
        val closes: Boolean get() = headers["connection"]?.equals("close", ignoreCase = true) == true

        override fun equals(other: Any?): Boolean =
            other is Request && method == other.method && path == other.path && query == other.query &&
                headers == other.headers && body.contentEquals(other.body)

        override fun hashCode(): Int = listOf(method, path, query, headers, body.contentHashCode()).hashCode()
    }

    /** A request that could not be read: [status] and why, answered before the connection closes. */
    class BadRequest(val status: Int, message: String) : Exception(message)

    data class Response(val status: Int, val contentType: String, val body: ByteArray, val extraHeaders: Map<String, String> = emptyMap()) {
        override fun equals(other: Any?): Boolean =
            other is Response && status == other.status && contentType == other.contentType && body.contentEquals(other.body) &&
                extraHeaders == other.extraHeaders

        override fun hashCode(): Int = listOf(status, contentType, body.contentHashCode(), extraHeaders).hashCode()

        companion object {
            fun json(status: Int, text: String, headers: Map<String, String> = emptyMap()) =
                Response(status, "application/json", text.toByteArray(Charsets.UTF_8), headers)

            fun text(status: Int, text: String, headers: Map<String, String> = emptyMap()) =
                Response(status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8), headers)
        }
    }

    /** The next request on [input], or null when the client closed the connection between requests. */
    fun read(input: InputStream): Request? {
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw BadRequest(400, "not an HTTP/1 request line")
        val (method, target) = parts[0] to parts[1]
        val headers = lines.drop(1).filter { it.isNotEmpty() }.associate { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) throw BadRequest(400, "not a header: $line")
            line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
            headers["content-length"] != null -> {
                val length = headers.getValue("content-length").toLongOrNull()?.takeIf { it >= 0 } ?: throw BadRequest(400, "bad Content-Length")
                if (length > MAX_BODY_BYTES) throw BadRequest(413, "a request body over $MAX_BODY_BYTES bytes")
                readExactly(input, length.toInt())
            }
            else -> ByteArray(0)
        }
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { pair ->
            decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
        }
        return Request(method, decode(path), query, headers, body)
    }

    /**
     * Writes [response] whole, with the headers Docker's clients read ([apiHeaders]) added. A 101 is Docker's
     * connection hijack (exec and attach): no length, the body is the raw stream, and the connection closes after it.
     */
    fun write(output: OutputStream, response: Response, apiHeaders: Map<String, String>) {
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            if (response.status == 101) {
                append("Connection: Upgrade\r\n")
                append("Upgrade: tcp\r\n")
            } else {
                append("Content-Length: ").append(response.body.size).append("\r\n")
            }
            (apiHeaders + response.extraHeaders).forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(response.body)
        output.flush()
    }

    fun reason(status: Int): String = when (status) {
        101 -> "UPGRADED"
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        304 -> "Not Modified"
        400 -> "Bad Request"
        404 -> "Not Found"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        else -> "Status"
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /** The request line and headers up to the blank line, or null on a clean end before any byte. */
    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf(13, 10, 13, 10)
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (out.size() == 0) return null
                throw BadRequest(400, "the connection ended inside a request")
            }
            out.write(b)
            if (out.size() > MAX_HEAD_BYTES) throw BadRequest(413, "request headers over $MAX_HEAD_BYTES bytes")
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b == 13) 1 else 0
            if (matched == 4) return out.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var at = 0
        while (at < length) {
            val n = input.read(buffer, at, length - at)
            if (n < 0) throw BadRequest(400, "the body ended early")
            at += n
        }
        return buffer
    }

    private fun readLine(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw BadRequest(400, "the body ended early")
            if (b == 10) return out.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
            out.write(b)
            if (out.size() > 1024) throw BadRequest(400, "a chunk line too long")
        }
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input).substringBefore(';').trim().toIntOrNull(16) ?: throw BadRequest(400, "bad chunk size")
            if (size == 0) {
                while (readLine(input).isNotEmpty()) Unit
                return out.toByteArray()
            }
            if (out.size() + size > MAX_BODY_BYTES) throw BadRequest(413, "a request body over $MAX_BODY_BYTES bytes")
            out.write(readExactly(input, size))
            readLine(input)
        }
    }
}
