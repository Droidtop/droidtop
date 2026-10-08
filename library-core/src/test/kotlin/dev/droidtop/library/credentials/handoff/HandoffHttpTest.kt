package dev.droidtop.library.credentials.handoff

import java.net.InetAddress
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The routes a phone page may use, and the socket that serves them (docs/SPEC.md 7h). */
class HandoffHttpTest {
    private var now = 0L
    private val fields = listOf(HandoffField("api_key", "API key", secret = true))

    private fun make(maxBad: Int = 5): Pair<HandoffSession, HandoffHttp> {
        val session = HandoffSession(fields, clock = { now }, token = "tok", maxBadTokens = maxBad)
        return session to HandoffHttp(session, "Test key")
    }

    @Test
    fun `the form is served at the token address with a password field and no outside resources`() {
        val (_, http) = make()
        val reply = http.handle("GET", "/h/tok", "")
        assertEquals(200, reply.status)
        assertTrue(reply.body.contains("type=\"password\""))
        assertTrue(reply.body.contains("name=\"api_key\""))
        assertFalse(reply.body.contains("http://"))
        assertFalse(reply.body.contains("<script"))
    }

    @Test
    fun `anything else is an empty 404`() {
        val (_, http) = make()
        for (target in listOf("/", "/h/", "/h/wrong", "/h/tok/extra", "/admin")) {
            val reply = http.handle("GET", target, "")
            assertEquals(target, 404, reply.status)
            assertEquals("", reply.body)
        }
        assertEquals(404, http.handle("PUT", "/h/tok", "").status)
    }

    @Test
    fun `a post with the right token and field is accepted and waits for the console`() {
        val (session, http) = make()
        val reply = http.handle("POST", "/h/tok", "api_key=abc%2B123")
        assertEquals(200, reply.status)
        assertEquals(mapOf("api_key" to "abc+123"), session.received())
        assertEquals(410, http.handle("GET", "/h/tok", "").status)
    }

    @Test
    fun `a post with an extra field is refused and nothing is kept`() {
        val (session, http) = make()
        val reply = http.handle("POST", "/h/tok", "api_key=abc&password=hunter2")
        assertEquals(400, reply.status)
        assertNull(session.received())
    }

    @Test
    fun `the form is escaped`() {
        val session = HandoffSession(listOf(HandoffField("a", "<b>\"x\"", secret = false)), clock = { now }, token = "tok")
        val reply = HandoffHttp(session, "T&T").handle("GET", "/h/tok", "")
        assertTrue(reply.body.contains("&lt;b&gt;&quot;x&quot;"))
        assertTrue(reply.body.contains("T&amp;T"))
    }

    @Test
    fun `only local network peers are served`() {
        assertTrue(HandoffServer.isLocalPeer(InetAddress.getByName("192.168.1.20")))
        assertTrue(HandoffServer.isLocalPeer(InetAddress.getByName("10.0.0.5")))
        assertTrue(HandoffServer.isLocalPeer(InetAddress.getByName("127.0.0.1")))
        assertFalse(HandoffServer.isLocalPeer(InetAddress.getByName("8.8.8.8")))
        assertFalse(HandoffServer.isLocalPeer(null))
    }

    @Test
    fun `the lan address is the site local ipv4 one`() {
        val picked = HandoffServer.pickLanAddress(
            listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("fe80::1"), InetAddress.getByName("192.168.1.20")),
        )
        assertEquals("192.168.1.20", picked)
        assertNull(HandoffServer.pickLanAddress(listOf(InetAddress.getByName("8.8.8.8"))))
    }

    @Test
    fun `the server answers over a real socket and stops listening when stopped`() {
        val (session, http) = make()
        val server = HandoffServer(session, http)
        val port = server.start()
        try {
            val body = "api_key=abc123"
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write(
                    ("POST /h/tok HTTP/1.1\r\nHost: x\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
                        "Content-Length: ${body.length}\r\n\r\n$body").toByteArray(),
                )
                val text = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(text.startsWith("HTTP/1.1 200"))
                assertTrue(text.contains("Cache-Control: no-store"))
            }
            assertEquals(mapOf("api_key" to "abc123"), session.received())
        } finally {
            server.stop()
        }
        assertNull(session.received())
        assertFalse(session.isLive())
    }

    @Test
    fun `an oversized body is refused before it is read`() {
        val (session, http) = make()
        val server = HandoffServer(session, http)
        val port = server.start()
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write("POST /h/tok HTTP/1.1\r\nContent-Length: 999999\r\n\r\n".toByteArray())
                assertTrue(socket.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 413"))
            }
            assertNull(session.received())
        } finally {
            server.stop()
        }
    }
}
