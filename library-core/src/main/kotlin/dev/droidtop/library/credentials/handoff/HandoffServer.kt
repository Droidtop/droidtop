package dev.droidtop.library.credentials.handoff

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The listening half of a phone handoff (docs/SPEC.md 7h). Binds an ephemeral
 * port only between [start] and [stop] (or the session ending), serves one
 * connection at a time, and answers only peers on the local network. Requests
 * are bounded: head 8 KB, body 4 KB, 5 seconds of silence. All decisions about
 * tokens, fields and rate limits are the [session]'s; this class only moves
 * bytes. Run [start] off the main thread.
 */
class HandoffServer(private val session: HandoffSession, private val http: HandoffHttp) {
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var thread: Thread? = null

    /** Binds and starts serving; returns the port. */
    @Synchronized
    fun start(): Int {
        socket?.let { return it.localPort }
        val bound = ServerSocket(0, 4)
        bound.soTimeout = 1_000
        socket = bound
        thread = Thread({ serve(bound) }, "droidtop-handoff").apply { isDaemon = true; start() }
        return bound.localPort
    }

    @Synchronized
    fun stop() {
        socket?.runCatching { close() }
        socket = null
        session.close()
    }

    private fun serve(bound: ServerSocket) {
        try {
            while (!bound.isClosed && session.isLive()) {
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
        val out = client.getOutputStream()
        val answer = try {
            val input = client.getInputStream()
            val head = readHead(input) ?: return reply(out, HandoffReply(400, ""))
            val lines = head.split("\r\n")
            val parts = lines.first().split(' ')
            if (parts.size < 2) return reply(out, HandoffReply(400, ""))
            val length = lines.drop(1).firstNotNullOfOrNull {
                if (it.startsWith("content-length:", ignoreCase = true)) it.substringAfter(':').trim().toIntOrNull() else null
            } ?: 0
            if (length < 0 || length > MAX_BODY) return reply(out, HandoffReply(413, ""))
            val body = String(readBody(input, length), Charsets.UTF_8)
            http.handle(parts[0], parts[1], body)
        } catch (e: java.io.IOException) {
            return
        }
        reply(out, answer)
    }

    private fun reply(out: java.io.OutputStream, reply: HandoffReply) {
        val bytes = reply.body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder("HTTP/1.1 ${reply.status} ${reason(reply.status)}\r\n")
        head.append("Content-Type: ${reply.contentType}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
        HandoffHttp.SECURITY_HEADERS.forEach { (k, v) -> head.append("$k: $v\r\n") }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        410 -> "Gone"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
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
        private const val MAX_BODY = 4 * 1024
        private val TERMINATOR = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())

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

        fun url(host: String, port: Int, token: String): String = "http://$host:$port/h/$token"
    }
}
