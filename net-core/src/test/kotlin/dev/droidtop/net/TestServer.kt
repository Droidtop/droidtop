package dev.droidtop.net

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * A loopback HTTP/1.1 server for the tests in this module. An Android module's unit tests compile against
 * android.jar, which does not show the JDK's com.sun.net.httpserver, so the tests cannot use HttpServer (HttpTest
 * did, and did not compile in CI, Droidtop/tracker#730); java.net.ServerSocket is in android.jar. One request per
 * connection, answered by the handler registered for its path.
 */
internal class TestServer {
    class Exchange(
        val requestMethod: String,
        headers: Map<String, String>,
        val requestBody: InputStream,
        private val socket: Socket,
    ) {
        class ResponseHeaders {
            internal val sent = mutableListOf<Pair<String, String>>()
            fun add(name: String, value: String) { sent += name to value }
        }

        class RequestHeaders(private val headers: Map<String, String>) {
            fun getFirst(name: String): String? = headers[name.lowercase()]
        }

        val responseHeaders = ResponseHeaders()
        val requestHeaders = RequestHeaders(headers)
        val responseBody: OutputStream get() = socket.getOutputStream()

        /** [length] is the body's length; -1 means there is none. */
        fun sendResponseHeaders(status: Int, length: Long) {
            val head = StringBuilder("HTTP/1.1 $status \r\n")
            responseHeaders.sent.forEach { (name, value) -> head.append(name).append(": ").append(value).append("\r\n") }
            head.append("Content-Length: ${maxOf(length, 0L)}\r\nConnection: close\r\n\r\n")
            socket.getOutputStream().write(head.toString().toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
        }

        fun close() {
            runCatching { socket.getOutputStream().flush() }
            runCatching { socket.close() }
        }
    }

    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val handlers = ConcurrentHashMap<String, (Exchange) -> Unit>()
    val port: Int get() = listener.localPort

    fun createContext(path: String, handler: (Exchange) -> Unit) { handlers[path] = handler }

    fun start() {
        thread(isDaemon = true, name = "test-server") {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: IOException) { return@thread }
                thread(isDaemon = true, name = "test-server-connection") { serve(socket) }
            }
        }
    }

    fun stop() { runCatching { listener.close() } }

    private fun serve(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return
                if (b == '\n'.code) {
                    val text = line.toString().trimEnd('\r')
                    line.setLength(0)
                    if (text.isEmpty()) break
                    lines += text
                } else line.append(b.toChar())
            }
            val (method, target) = lines.first().split(' ').let { it[0] to it[1] }
            val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).lowercase() to l.substring(it + 1).trim() } }
                .toMap()
            val body = ByteArray(headers["content-length"]?.toIntOrNull() ?: 0).also { var n = 0; while (n < it.size) { val r = input.read(it, n, it.size - n); if (r < 0) break; n += r } }
            val exchange = Exchange(method, headers, ByteArrayInputStream(body), socket)
            val handler = handlers[target.substringBefore('?')]
            if (handler == null) { exchange.sendResponseHeaders(404, -1); exchange.close() } else handler(exchange)
        } catch (_: IOException) {
            // The client left, as a bounded read does on purpose.
        } finally {
            runCatching { socket.close() }
        }
    }
}
