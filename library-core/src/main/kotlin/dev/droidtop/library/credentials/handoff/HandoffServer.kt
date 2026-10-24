package dev.droidtop.library.credentials.handoff

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.random.Random

/** What a [HandoffServer] serves: the answer to each request, and whether to keep listening at all. */
interface HandoffEndpoint {
    /** Asked about once a second; false ends the listener, which then closes its socket. */
    fun isLive(): Boolean

    /** The answer to one request from [peer] (its address); pure of sockets. */
    fun handle(method: String, target: String, body: String, peer: String): HandoffReply

    /** The most body bytes a request for [target] may carry; the server refuses a larger one with a 413 before reading it. */
    fun maxBody(target: String): Int = HandoffServer.MAX_BODY

    /**
     * Asked from the head alone, before any of the body is read: null when [target] is an ordinary request (its body is
     * read into [handle], capped by [maxBody]); otherwise whether the endpoint takes a streamed body ([HandoffUpload]).
     * [length] is the request's Content-Length, null when it sent none. This is the one way past the body cap: the body
     * is never held, it is handed on as a stream of exactly [length] bytes.
     */
    fun receive(method: String, target: String, length: Long?, peer: String): HandoffUpload? = null
}

/** The answer to a request whose body is streamed. */
sealed interface HandoffUpload {
    /** Nothing is read: this is the answer, and the connection is closed. */
    class Refuse(val reply: HandoffReply) : HandoffUpload

    /** Read the body as it arrives: [accept] gets a stream that ends after exactly the Content-Length bytes (sooner if the peer stops), and returns the answer. */
    class Accept(val accept: (InputStream) -> HandoffReply) : HandoffUpload
}

/** A file sent as the body of a reply, streamed, never held. [length] is -1 when unknown (the body then ends with the connection). The server closes [stream]. */
class HandoffDownload(val name: String, val length: Long, val stream: InputStream)

/** A listener that can be started and stopped, so what owns one can be tested with a fake. */
interface HandoffListener {
    /** Binds and starts serving; returns the port. Off the main thread. */
    fun start(): Int

    fun stop()
}

class HandoffReply(
    val status: Int,
    val body: String,
    val contentType: String = "text/html; charset=utf-8",
    /** When set, the reply's body is this file, as an attachment, and [body] is not used. */
    val download: HandoffDownload? = null,
)

/**
 * The listening half of a local-network handoff (docs/SPEC.md 7h, "Supplying a key from a phone", and "Type from
 * another device"). Binds [bindHost] (the device's LAN address; every interface when null) on a random port only
 * between [start] and [stop] (or the [endpoint] ending), serves one connection at a time, and answers only peers on
 * the local network. Requests are bounded: head 8 KB, body 4 KB unless the [endpoint] allows a target more
 * ([HandoffEndpoint.maxBody]), 5 seconds of silence on a read. A target the endpoint streams ([HandoffEndpoint.receive]) has
 * its body handed on as it arrives, never held; a reply may be a file streamed out ([HandoffDownload]). All decisions about tokens,
 * devices and codes are the [endpoint]'s; this class only moves bytes. Run [start] off the main thread.
 */
class HandoffServer(private val endpoint: HandoffEndpoint, private val bindHost: String? = null) : HandoffListener {
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var thread: Thread? = null

    @Synchronized
    override fun start(): Int {
        socket?.let { return it.localPort }
        val bound = bind()
        bound.soTimeout = 1_000
        socket = bound
        thread = Thread({ serve(bound) }, "droidtop-handoff").apply { isDaemon = true; start() }
        return bound.localPort
    }

    @Synchronized
    override fun stop() {
        socket?.runCatching { close() }
        socket = null
    }

    /** A random port in the dynamic range, so a restarted listener does not come back on the one just closed. */
    private fun bind(): ServerSocket {
        val address = bindHost?.let { InetAddress.getByName(it) }
        repeat(BIND_TRIES) {
            val port = Random.nextInt(FIRST_PORT, LAST_PORT + 1)
            try {
                return ServerSocket(port, 4, address)
            } catch (e: java.io.IOException) {
                // taken: try another
            }
        }
        return ServerSocket(0, 4, address)
    }

    private fun serve(bound: ServerSocket) {
        try {
            while (!bound.isClosed && endpoint.isLive()) {
                val client = try {
                    bound.accept()
                } catch (e: SocketTimeoutException) {
                    continue
                }
                client.use { handle(it) }
            }
        } catch (e: java.io.IOException) {
            // closed by stop()
        } finally {
            bound.runCatching { close() }
        }
    }

    private fun handle(client: Socket) {
        if (!isLocalPeer(client.inetAddress)) return
        client.soTimeout = 5_000
        try {
            val out = client.getOutputStream()
            val answer = try {
                val input = client.getInputStream()
                val head = readHead(input) ?: return reply(out, HandoffReply(400, ""))
                val lines = head.split("\r\n")
                val parts = lines.first().split(' ')
                if (parts.size < 2) return reply(out, HandoffReply(400, ""))
                val declared = lines.drop(1).firstNotNullOfOrNull {
                    if (it.startsWith("content-length:", ignoreCase = true)) it.substringAfter(':').trim().toLongOrNull() else null
                }
                val peer = client.inetAddress.hostAddress.orEmpty()
                val streamed = endpoint.receive(parts[0], parts[1], declared, peer)
                if (streamed is HandoffUpload.Refuse) return reply(out, streamed.reply)
                if (streamed is HandoffUpload.Accept) {
                    // declared is known to be set and valid here: the endpoint refuses otherwise.
                    streamed.accept(BoundedInput(input, declared ?: 0L))
                } else {
                    val length = declared ?: 0L
                    if (length < 0 || length > endpoint.maxBody(parts[1])) return reply(out, HandoffReply(413, ""))
                    val body = String(readBody(input, length.toInt()), Charsets.UTF_8)
                    endpoint.handle(parts[0], parts[1], body, peer)
                }
            } catch (e: java.io.IOException) {
                return
            } catch (e: RuntimeException) {
                // A failure in what the endpoint does must not end the listener.
                HandoffReply(500, "")
            }
            reply(out, answer)
        } catch (e: java.io.IOException) {
            // A peer that went away before reading its answer must not end the listener either.
        }
    }

    private fun reply(out: java.io.OutputStream, reply: HandoffReply) {
        val download = reply.download
        if (download != null) return replyFile(out, reply, download)
        val bytes = reply.body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder("HTTP/1.1 ${reply.status} ${reason(reply.status)}\r\n")
        head.append("Content-Type: ${reply.contentType}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
        SECURITY_HEADERS.forEach { (k, v) -> head.append("$k: $v\r\n") }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    /** A file as the body: an attachment under its own name, streamed in chunks; the stream is closed whatever happens. */
    private fun replyFile(out: java.io.OutputStream, reply: HandoffReply, download: HandoffDownload) {
        download.stream.use { stream ->
            val head = StringBuilder("HTTP/1.1 ${reply.status} ${reason(reply.status)}\r\n")
            head.append("Content-Type: ${reply.contentType}\r\n")
            if (download.length >= 0) head.append("Content-Length: ${download.length}\r\n")
            head.append("Content-Disposition: ${contentDisposition(download.name)}\r\nConnection: close\r\n")
            SECURITY_HEADERS.forEach { (k, v) -> head.append("$k: $v\r\n") }
            head.append("\r\n")
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            val buffer = ByteArray(CHUNK)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.flush()
        }
    }

    /** At most the bytes still [left] of [source], then end of stream; the connection's socket timeout still applies to every read. */
    private class BoundedInput(private val source: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = source.read()
            if (b >= 0) left--
            return b
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left <= 0) return -1
            val n = source.read(buffer, offset, minOf(length.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        410 -> "Gone"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        else -> "Error"
    }

    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        while (buffer.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            matched = if (b == TERMINATOR[matched].toInt()) matched + 1 else if (b == TERMINATOR[0].toInt()) 1 else 0
            if (matched == TERMINATOR.size) return String(buffer.toByteArray(), Charsets.ISO_8859_1).removeSuffix("\r\n\r\n")
        }
        return null
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return body.copyOf(read)
    }

    companion object {
        private const val MAX_HEAD = 8 * 1024
        const val MAX_BODY = 4 * 1024
        private const val FIRST_PORT = 49152
        private const val LAST_PORT = 65535
        private const val BIND_TRIES = 20
        private const val CHUNK = 64 * 1024
        private val TERMINATOR = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())

        /** Headers every reply carries: nothing cached, nothing embedded, nothing leaked in a Referer. */
        val SECURITY_HEADERS = listOf(
            "Cache-Control" to "no-store",
            "Content-Security-Policy" to "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'",
            "Referrer-Policy" to "no-referrer",
            "X-Content-Type-Options" to "nosniff",
        )

        /** Loopback, site-local (192.168/10/172.16) and link-local peers; nothing routed from the internet. */
        fun isLocalPeer(address: InetAddress?): Boolean =
            address != null && (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress)

        /** The address a phone on the same network can reach: the first site-local IPv4 of an interface that is up. */
        fun lanAddress(): String? = pickLanAddress(
            runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                    .flatMap { it.inetAddresses.toList() }
            }.getOrDefault(emptyList()),
        )

        fun pickLanAddress(candidates: List<InetAddress>): String? =
            candidates.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress

        fun url(host: String, port: Int, token: String): String = "http://$host:$port/r/$token"

        /**
         * A Content-Disposition for an attachment named [name]: an ASCII fallback (anything else, and quotes and
         * backslashes, become an underscore, so nothing can end the header or add one) and the real name in the
         * RFC 5987 form.
         */
        fun contentDisposition(name: String): String {
            val ascii = name.map { if (it.code in 0x20..0x7e && it != '"' && it != '\\' && it != '%') it else '_' }.joinToString("")
            val encoded = StringBuilder()
            for (b in name.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xff
                if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) || c == '.'.code || c == '-'.code || c == '_'.code) {
                    encoded.append(c.toChar())
                } else {
                    encoded.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
                }
            }
            return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
        }
    }
}
