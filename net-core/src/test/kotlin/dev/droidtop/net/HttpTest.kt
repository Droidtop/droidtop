package dev.droidtop.net

import com.sun.net.httpserver.HttpServer
import java.io.File
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
        catch (e: java.io.IOException) { assertTrue(e.message!!.contains("exceeded")) }
    }

    @Test fun digestMismatchRemovesPartialFile() {
        val dir = createTempDir()
        val target = File(dir, "payload")
        try { Http.downloadTo(base + "/ok", target, "00"); fail("expected digest mismatch") }
        catch (e: java.io.IOException) { assertTrue(e.message!!.contains("SHA-256")) }
        assertFalse(File(dir, "payload.part").exists())
        assertFalse(target.exists())
        dir.deleteRecursively()
    }
}
