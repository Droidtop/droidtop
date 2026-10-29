package dev.droidtop.net

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HttpTest {
    private lateinit var server: HttpServer
    private val body = "response"
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok") { it.sendResponseHeaders(200, body.length.toLong()); it.responseBody.use { out -> out.write(body.toByteArray()) } }
        server.createContext("/missing") { it.sendResponseHeaders(429, -1); it.close() }
        server.start()
    }

    @After fun stop() { server.stop(0) }

    @Test fun readsSuccessfulResponseAndPreservesRateLimitOnFailure() {
        assertEquals(body, Http.get(base + "/ok").text())
        server.createContext("/limited") { exchange -> exchange.responseHeaders.add("X-RateLimit-Remaining", "0"); exchange.sendResponseHeaders(429, -1); exchange.close() }
        try { Http.get(base + "/limited"); fail("expected HTTP failure") }
        catch (e: Http.HttpException) { assertEquals(429, e.status); assertEquals("0", e.rateLimitHeaders["x-ratelimit-remaining"]) }
    }

    @Test fun responseIsBounded() {
        try { Http.get(base + "/ok", maxBytes = 2); fail("expected bound failure") }
        catch (e: Http.TooLarge) { assertTrue(e.message!!.contains("exceeded")) }
    }

    @Test fun requestReturnsARefusalWithItsBody() {
        server.createContext("/refused") { exchange ->
            val reason = """{"message":"bad key"}""".toByteArray()
            exchange.sendResponseHeaders(403, reason.size.toLong()); exchange.responseBody.use { it.write(reason) }
        }
        val response = Http.request(base + "/refused")
        assertEquals(403, response.status)
        assertEquals("""{"message":"bad key"}""", response.text())
    }

    @Test fun refusalBodyIsCutAtTheErrorBound() {
        val long = ByteArray(Http.ERROR_BODY_MAX_BYTES.toInt() + 100) { 'x'.code.toByte() }
        server.createContext("/long-refusal") { exchange -> exchange.sendResponseHeaders(500, long.size.toLong()); exchange.responseBody.use { it.write(long) } }
        assertEquals(Http.ERROR_BODY_MAX_BYTES.toInt(), Http.request(base + "/long-refusal").body.size)
    }

    @Test fun postSendsItsBodyAndUserAgent() {
        var received = ""
        var agent: String? = null
        server.createContext("/echo") { exchange ->
            received = exchange.requestMethod + " " + exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            agent = exchange.requestHeaders.getFirst("User-Agent")
            exchange.sendResponseHeaders(200, -1); exchange.close()
        }
        Http.request(base + "/echo", method = "POST", headers = mapOf("Content-Type" to "text/plain"), body = "fields".toByteArray())
        assertEquals("POST fields", received)
        assertEquals(Http.USER_AGENT, agent)
    }

    // A token used to make get() set headers on the connection GitHubAuth.open had already connected, which throws
    // IllegalStateException; every catalog fetch of a signed-in person went that way.
    @Test fun aTokenRequestSendsItsHeadersAndNoTokenToAnotherHost() {
        var agent: String? = null
        var authorization: String? = "unset"
        server.createContext("/headers") { exchange ->
            agent = exchange.requestHeaders.getFirst("User-Agent")
            authorization = exchange.requestHeaders.getFirst("Authorization")
            exchange.sendResponseHeaders(200, body.length.toLong()); exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        assertEquals(body, Http.get(base + "/headers", token = "ghp_secret").text())
        assertEquals(Http.USER_AGENT, agent)
        assertNull(authorization)
    }

    @Test fun aRedirectIsNotFollowedWhenAskedNotTo() {
        server.createContext("/moved") { exchange ->
            exchange.responseHeaders.add("Location", "$base/ok"); exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        assertEquals(302, Http.request(base + "/moved", followRedirects = false).status)
        assertEquals(body, Http.request(base + "/moved").text())
    }

    @Test fun aWatchedReadReportsProgressAndChecksTheWholeBody() {
        val seen = mutableListOf<Pair<Long, Long>>()
        val answer = Http.request(base + "/ok", watch = Http.Watch(onProgress = { read, total -> seen += read to total }))
        assertEquals(body, answer.text())
        assertEquals(0L to body.length.toLong(), seen.first())
        assertEquals(body.length.toLong() to body.length.toLong(), seen.last())
    }

    @Test fun aWatchedReadStopsWhenCancelled() {
        try {
            Http.request(base + "/ok", watch = Http.Watch(isCancelled = { true }))
            fail("expected cancellation")
        } catch (e: java.io.IOException) {
            assertEquals("cancelled", e.message)
        }
    }

    @Test fun aWatchedReadIsBounded() {
        try { Http.request(base + "/ok", maxBytes = 2, watch = Http.Watch()); fail("expected bound failure") }
        catch (e: Http.TooLarge) { assertEquals(2L, e.limit) }
    }

    @Test fun peekTakesOnlyTheFirstBytesWhenTheServerIgnoresRange() {
        var range: String? = null
        server.createContext("/whole") { exchange ->
            range = exchange.requestHeaders.getFirst("Range")
            val big = ByteArray(1_000_000) { it.toByte() }
            exchange.sendResponseHeaders(200, big.size.toLong())
            try { exchange.responseBody.use { it.write(big) } } catch (_: java.io.IOException) { /* the reader left */ }
        }
        val (status, head) = Http.peek(base + "/whole", 8)
        assertEquals(200, status)
        assertEquals("bytes=0-7", range)
        assertArrayEquals(ByteArray(8) { it.toByte() }, head)
    }

    @Test fun peekReportsARefusalWithNoBytes() {
        val (status, head) = Http.peek(base + "/missing", 8)
        assertEquals(429, status)
        assertEquals(0, head.size)
    }
}
