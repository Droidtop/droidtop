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
}
