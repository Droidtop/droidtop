package dev.droidtop.pluginhost

import dev.droidtop.shared.net.BoundedStreamCopy
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One immutable browser operation's authenticated CONNECT path. It never inspects or logs TLS/application data. */
internal class BrowserConnectGateway private constructor(
    timeoutMs: Long,
    private val byteLimit: Long,
    private val beforeResolve: (String) -> Unit,
    private val authorize: (String, List<InetAddress>) -> Unit,
    private val resolve: (String) -> List<InetAddress>,
    private val connect: (Socket, InetAddress, Int, Int) -> Unit,
    private val failed: (Failure) -> Unit,
) : Closeable {
    enum class Failure { DEADLINE, PERMISSION, LIMIT }

    /** Deliberately not a data class: diagnostic stringification must never disclose authorization. */
    class Endpoint internal constructor(val port: Int, val authorization: String) {
        val host = "127.0.0.1"
        override fun toString() = "Browser CONNECT endpoint"
    }

    private val closed = AtomicBoolean()
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val lifecycle = Any()
    private val attempts = AtomicInteger()
    private val clients = AtomicInteger()
    private val connections = AtomicInteger()
    private val bytes = AtomicLong()
    private val failure = AtomicReference<Failure?>()
    private val listener = ServerSocket()
    private var expiry: ScheduledFuture<*>? = null
    val endpoint: Endpoint
    val failureReason: Failure? get() = failure.get()

    init {
        try {
        listener.reuseAddress = false
        listener.bind(InetSocketAddress(LOOPBACK, 0), 8)
        listener.soTimeout = 250
        check(listener.localPort in 1024..65535) { "The browser proxy could not reserve an ephemeral port" }
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(RANDOM::nextBytes))
        endpoint = Endpoint(listener.localPort, "Bearer $secret")
        } catch (error: Throwable) { runCatching { listener.close() }; throw error }
    }

    private fun start(timeoutMs: Long) {
        expiry = TIMER.schedule({ fail(Failure.DEADLINE) }, timeoutMs, TimeUnit.MILLISECONDS)
        Thread({ accept() }, "browser-connect-accept").apply { isDaemon = true; start() }
    }

    private fun leftMs(): Int = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceIn(1, 30_000).toInt()
    private fun live() {
        if (!closed.get() && System.nanoTime() >= deadline) fail(Failure.DEADLINE)
        if (closed.get()) throw SocketException("The browser operation ended")
    }

    private fun accept() {
        while (!closed.get()) {
            try {
                live()
                val socket = listener.accept()
                if (attempts.incrementAndGet() > 64) { socket.close(); fail(Failure.LIMIT); break }
                if (clients.incrementAndGet() > 8) { clients.decrementAndGet(); respond(socket, 503); socket.close(); continue }
                if (!track(socket)) { clients.decrementAndGet(); socket.close(); break }
                try { IO.execute { handle(socket) } }
                catch (_: Exception) { clients.decrementAndGet(); sockets -= socket; socket.close() }
            } catch (_: SocketTimeoutException) { /* deadline/cancellation remains observable without a blocked accept */ }
            catch (_: IOException) { if (!closed.get()) close(); break }
        }
    }

    private fun handle(client: Socket) {
        var target: Socket? = null
        val reverseDone = CountDownLatch(1)
        try {
            live()
            client.soTimeout = minOf(2_000, leftMs())
            val input = BufferedInputStream(client.getInputStream(), 8192)
            val request = try { Request.parse(readHeaders(input)) }
            catch (_: Exception) { respond(client, 400); return }
            if (!MessageDigest.isEqual(request.authorization.toByteArray(Charsets.US_ASCII), endpoint.authorization.toByteArray(Charsets.US_ASCII))) {
                respond(client, 407); return
            }
            val namePolicy = POLICY.submit(Callable { live(); beforeResolve(request.origin) })
            try { namePolicy.get(minOf(5_000, leftMs()).toLong(), TimeUnit.MILLISECONDS) }
            catch (_: Exception) { namePolicy.cancel(true); respond(client, 403); fail(Failure.PERMISSION); return }
            val task = POLICY.submit(Callable { live(); resolve(request.host).distinct().toList() })
            val addresses = try { task.get(minOf(5_000, leftMs()).toLong(), TimeUnit.MILLISECONDS) }
            catch (_: Exception) {
                task.cancel(true)
                respond(client, 502)
                return
            }
            if (addresses.isEmpty() || addresses.size > 16) { respond(client, 502); return }
            // No host string crosses into Socket.connect: the exact authorized objects are used for every attempt.
            for (address in addresses) {
                live()
                val policy = POLICY.submit(Callable { live(); authorize(request.origin, addresses) })
                try { policy.get(minOf(5_000, leftMs()).toLong(), TimeUnit.MILLISECONDS) }
                catch (_: Exception) { policy.cancel(true); respond(client, 403); fail(Failure.PERMISSION); return }
                if (connections.incrementAndGet() > 64) { respond(client, 503); fail(Failure.LIMIT); return }
                val candidate = Socket(Proxy.NO_PROXY)
                if (!track(candidate)) { candidate.close(); live() }
                try {
                    live()
                    connect(candidate, address, request.port, minOf(5_000, leftMs()))
                    target = candidate
                    break
                } catch (_: IOException) { sockets -= candidate; candidate.close() }
                catch (error: Exception) { sockets -= candidate; candidate.close(); throw error }
            }
            val remote = target ?: run { respond(client, 502); return }
            live()
            val policyLock = Any()
            var nextPolicyCheck = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            val currentPolicy = {
                synchronized(policyLock) {
                    if (System.nanoTime() >= nextPolicyCheck) {
                        val policy = POLICY.submit(Callable { live(); authorize(request.origin, addresses) })
                        try { policy.get(minOf(5_000, leftMs()).toLong(), TimeUnit.MILLISECONDS) }
                        catch (error: Exception) { policy.cancel(true); fail(Failure.PERMISSION); throw IOException("The browser connection permission ended") }
                        live()
                        nextPolicyCheck = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                    }
                }
            }
            client.soTimeout = leftMs()
            remote.soTimeout = leftMs()
            client.getOutputStream().apply {
                write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            IO.execute {
                try { pump(remote.getInputStream(), client.getOutputStream(), currentPolicy) }
                catch (_: Exception) { /* cancellation and peer close never log request or response bytes */ }
                finally { runCatching { client.shutdownOutput() }; reverseDone.countDown() }
            }
            try { pump(input, remote.getOutputStream(), currentPolicy) }
            catch (_: Exception) { /* the operation deadline owns blocked I/O lifetime */ }
            finally { runCatching { remote.shutdownOutput() } }
            reverseDone.await(leftMs().toLong(), TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // No exception message is logged: platform errors may contain request details.
        } finally {
            target?.let { sockets -= it; runCatching { it.close() } }
            sockets -= client
            runCatching { client.close() }
            clients.decrementAndGet()
        }
    }

    private fun pump(input: InputStream, output: java.io.OutputStream, currentPolicy: () -> Unit) {
        try {
        // The beforeWrite reservation bounds both directions together, including a first oversized chunk.
        BoundedStreamCopy.copy(input, output, maxBytes = Long.MAX_VALUE,
            beforeRead = { live(); currentPolicy() },
            beforeWrite = { count ->
                if (bytes.addAndGet(count.toLong()) > byteLimit) { fail(Failure.LIMIT); throw IOException("The browser operation exceeded its byte budget") }
                live()
                currentPolicy()
            },
        )
        } catch (error: IOException) {
            if (bytes.get() >= byteLimit) fail(Failure.LIMIT)
            throw error
        }
    }

    private fun fail(reason: Failure) {
        if (beginClose(reason)) { closeResources(); failed(reason) }
    }

    private fun track(socket: Socket): Boolean = synchronized(lifecycle) {
        if (closed.get()) false else { sockets += socket; true }
    }

    private fun beginClose(reason: Failure?): Boolean = synchronized(lifecycle) {
        if (closed.get()) false else { closed.set(true); failure.set(reason); true }
    }

    override fun close() { if (beginClose(null)) closeResources() }

    private fun closeResources() {
        expiry?.cancel(false)
        runCatching { listener.close() }
        sockets.toList().forEach { runCatching { it.close() } }
        sockets.clear()
        OPERATIONS.decrementAndGet()
    }

    private class Request(val host: String, val port: Int, val origin: String, val authorization: String) {
        override fun toString() = "Browser CONNECT request"
        companion object {
            private val ALLOWED = setOf("host", "user-agent", "proxy-authorization", "proxy-connection", "connection", "accept", "accept-encoding", "accept-language", "content-length")
            fun parse(text: String): Request {
                val lines = text.removeSuffix("\r\n\r\n").split("\r\n")
                val first = lines.first().split(' ')
                require(first.size == 3 && first[0] == "CONNECT" && first[2] in setOf("HTTP/1.0", "HTTP/1.1"))
                val authority = first[1]
                require(authority.length <= 4096 && authority.none { it in "%\\/?#" || it.isISOControl() })
                val uri = URI("https://$authority/")
                val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase(Locale.ROOT)
                require(!host.isNullOrBlank() && uri.rawUserInfo == null && uri.port in 1..65535 && uri.rawPath == "/" && uri.rawQuery == null && uri.rawFragment == null)
                val headers = HashMap<String, String>()
                require(lines.size <= 64)
                for (line in lines.drop(1)) {
                    require(line.isNotEmpty() && !line.first().isWhitespace())
                    val cut = line.indexOf(':')
                    require(cut > 0)
                    val key = line.substring(0, cut).lowercase(Locale.ROOT)
                    val value = line.substring(cut + 1).trim()
                    require(key in ALLOWED && value.none { it == '\u0000' || it == '\u007F' || (it < ' ' && it != '\t') })
                    require(headers.put(key, value) == null)
                }
                require(headers["content-length"].let { it == null || it == "0" })
                headers["host"]?.let { value ->
                    val headerHost = URI("https://$value/")
                    require(headerHost.rawUserInfo == null && headerHost.host?.removePrefix("[")?.removeSuffix("]")?.equals(host, true) == true &&
                        (if (headerHost.port < 0) 443 else headerHost.port) == uri.port && headerHost.rawPath == "/" && headerHost.rawQuery == null && headerHost.rawFragment == null)
                }
                return Request(host, uri.port, "https://$authority/", headers["proxy-authorization"].orEmpty())
            }
        }
    }

    companion object {
        private val LOOPBACK = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private val RANDOM = SecureRandom()
        private val OPERATIONS = AtomicInteger()
        private fun pool(name: String, count: Int, queue: Int) = ThreadPoolExecutor(count, count, 10, TimeUnit.SECONDS, ArrayBlockingQueue(queue), { task -> Thread(task, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
        private val IO = pool("browser-connect-io", 64, 128)
        private val POLICY = pool("browser-connect-policy", 4, 64)
        private val TIMER = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "browser-connect-deadline").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }

        fun open(timeoutMs: Long, byteLimit: Long = 64L * 1024 * 1024, beforeResolve: (String) -> Unit, authorize: (String, List<InetAddress>) -> Unit,
                 resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
                 connect: (Socket, InetAddress, Int, Int) -> Unit = { socket, address, port, timeout ->
                     socket.connect(InetSocketAddress(address, port), timeout)
                 }, failed: (Failure) -> Unit = {}): BrowserConnectGateway {
            require(timeoutMs in 1..30_000 && byteLimit in 1..64L * 1024 * 1024)
            check(OPERATIONS.incrementAndGet() <= 4 || run { OPERATIONS.decrementAndGet(); false }) { "Four browser operations are already active" }
            val gateway = try { BrowserConnectGateway(timeoutMs, byteLimit, beforeResolve, authorize, resolve, connect, failed) }
            catch (error: Throwable) { OPERATIONS.decrementAndGet(); throw error }
            return try { gateway.start(timeoutMs); gateway }
            catch (error: Throwable) { gateway.close(); throw error }
        }

        private fun readHeaders(input: InputStream): String {
            val bytes = java.io.ByteArrayOutputStream()
            var tail = 0
            while (bytes.size() < 8192) {
                val value = input.read()
                if (value < 0) throw IOException("The browser CONNECT header ended early")
                bytes.write(value)
                tail = (tail shl 8) or value
                if (tail == 0x0D0A0D0A) return bytes.toString("ISO-8859-1")
            }
            throw IOException("The browser CONNECT header exceeded its limit")
        }

        private fun respond(socket: Socket, status: Int) = runCatching {
            val auth = if (status == 407) "Proxy-Authenticate: Bearer realm=\"droidtop\"\r\n" else ""
            socket.getOutputStream().apply { write("HTTP/1.1 $status Unavailable\r\n${auth}Connection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush() }
        }.let { Unit }
    }
}
