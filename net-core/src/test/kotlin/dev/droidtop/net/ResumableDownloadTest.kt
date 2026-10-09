package dev.droidtop.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The downloader against a local server that does, or does not do, Range requests (docs/SPEC.md 12a "Downloads"). */
class ResumableDownloadTest {
    private val dir: File = Files.createTempDirectory("resumable").toFile()
    private val data = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private lateinit var server: HttpServer
    private val base get() = "http://127.0.0.1:${server.address.port}"

    /** (Range, If-Range) of every request to /f, in order. */
    private val requests = CopyOnWriteArrayList<Pair<String?, String?>>()
    private val authorizations = CopyOnWriteArrayList<String?>()
    /** (Cookie, Referer) of every request, in order. */
    private val identifying = CopyOnWriteArrayList<Pair<String?, String?>>()
    @Volatile private var body = data
    @Volatile private var etag: String? = "\"v1\""
    @Volatile private var lastModified: String? = null
    @Volatile private var ranges = true
    @Volatile private var dropFirstAfter = -1

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/f") { ex -> serve(ex) }
        server.createContext("/gone") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
        server.createContext("/moved") { ex ->
            authorizations += ex.requestHeaders.getFirst("Authorization")
            identifying += ex.requestHeaders.getFirst("Cookie") to ex.requestHeaders.getFirst("Referer")
            ex.responseHeaders.add("Location", "http://localhost:${server.address.port}/f")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/same") { ex ->
            identifying += ex.requestHeaders.getFirst("Cookie") to ex.requestHeaders.getFirst("Referer")
            ex.responseHeaders.add("Location", "/f")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun serve(ex: HttpExchange) {
        val range = ex.requestHeaders.getFirst("Range")
        val ifRange = ex.requestHeaders.getFirst("If-Range")
        authorizations += ex.requestHeaders.getFirst("Authorization")
        identifying += ex.requestHeaders.getFirst("Cookie") to ex.requestHeaders.getFirst("Referer")
        requests += range to ifRange
        etag?.let { ex.responseHeaders.add("ETag", it) }
        lastModified?.let { ex.responseHeaders.add("Last-Modified", it) }
        val first = requests.size == 1
        val validator = etag ?: lastModified
        val partial = ranges && range != null && (ifRange == null || ifRange == validator)
        val payload: ByteArray
        if (partial) {
            val from = range!!.removePrefix("bytes=").removeSuffix("-").toInt()
            if (from >= body.size) {
                ex.responseHeaders.add("Content-Range", "bytes */${body.size}")
                ex.sendResponseHeaders(416, -1)
                ex.close()
                return
            }
            payload = body.copyOfRange(from, body.size)
            ex.responseHeaders.add("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
            ex.sendResponseHeaders(206, payload.size.toLong())
        } else {
            payload = body
            ex.sendResponseHeaders(200, payload.size.toLong())
        }
        try {
            if (first && dropFirstAfter >= 0) {
                ex.responseBody.write(payload, 0, dropFirstAfter)
                ex.responseBody.flush()
            } else {
                ex.responseBody.write(payload)
            }
        } catch (_: IOException) {
        } finally {
            runCatching { ex.close() }
        }
    }

    private fun target() = File(dir, "file.bin")

    private fun seedPart(bytes: Int, meta: ResumableDownload.Meta? = ResumableDownload.Meta("\"v1\"", null, data.size.toLong())) {
        val file = target()
        ResumableDownload.partOf(file).writeBytes(data.copyOf(bytes))
        meta?.let { ResumableDownload.metaOf(file).writeText(it.encode()) }
    }

    @Test
    fun `a fresh download lands whole and leaves no partial file`() {
        val result = ResumableDownload.fetch("$base/f", target())
        assertFalse(result.restarted)
        assertArrayEquals(data, target().readBytes())
        assertFalse(ResumableDownload.partOf(target()).exists())
        assertFalse(ResumableDownload.metaOf(target()).exists())
        assertEquals(listOf<Pair<String?, String?>>(null to null), requests.toList())
    }

    @Test
    fun `a partial file carries on with a Range request validated by the ETag`() {
        seedPart(100_000)
        val result = ResumableDownload.fetch("$base/f", target())
        assertFalse(result.restarted)
        assertArrayEquals(data, target().readBytes())
        assertEquals(listOf<Pair<String?, String?>>("bytes=100000-" to "\"v1\""), requests.toList())
    }

    @Test
    fun `the Last-Modified date validates when the server has no ETag`() {
        etag = null
        lastModified = "Wed, 21 Oct 2026 07:28:00 GMT"
        seedPart(1000, ResumableDownload.Meta(null, lastModified, data.size.toLong()))
        ResumableDownload.fetch("$base/f", target())
        assertArrayEquals(data, target().readBytes())
        assertEquals(listOf<Pair<String?, String?>>("bytes=1000-" to lastModified), requests.toList())
    }

    @Test
    fun `a file that changed on the server starts again from zero and says so`() {
        body = ByteArray(250_000) { (it % 97).toByte() }
        etag = "\"v2\""
        seedPart(100_000)
        val seen = CopyOnWriteArrayList<Boolean>()
        val result = ResumableDownload.fetch("$base/f", target(), onProgress = { seen += it.restarted })
        assertTrue(result.restarted)
        assertTrue(seen.last())
        assertArrayEquals(body, target().readBytes())
    }

    @Test
    fun `a server that refuses ranges restarts from zero`() {
        ranges = false
        seedPart(100_000)
        val result = ResumableDownload.fetch("$base/f", target())
        assertTrue(result.restarted)
        assertArrayEquals(data, target().readBytes())
    }

    @Test
    fun `with no validator at all a partial file cannot be trusted and no Range is sent`() {
        etag = null
        seedPart(100_000, ResumableDownload.Meta(null, null, data.size.toLong()))
        ResumableDownload.fetch("$base/f", target())
        assertArrayEquals(data, target().readBytes())
        assertEquals(listOf<Pair<String?, String?>>(null to null), requests.toList())
    }

    @Test
    fun `a partial file that is already everything is finished by the 416`() {
        seedPart(data.size)
        val result = ResumableDownload.fetch("$base/f", target())
        assertFalse(result.restarted)
        assertArrayEquals(data, target().readBytes())
    }

    @Test
    fun `a dropped connection resumes by itself from where it broke`() {
        dropFirstAfter = 50_000
        val waits = CopyOnWriteArrayList<String>()
        ResumableDownload.fetch("$base/f", target(), sleep = {}, waiting = { waits += it })
        assertArrayEquals(data, target().readBytes())
        assertEquals(2, requests.size)
        assertEquals("bytes=50000-", requests[1].first)
        assertEquals(listOf("Waiting for the connection"), waits.toList())
    }

    @Test
    fun `cancelling keeps the partial file and the next call finishes it`() {
        val handle = ResumableDownload.Handle()
        try {
            ResumableDownload.fetch("$base/f", target(), handle = handle, onProgress = { if (it.bytes >= 64 * 1024) handle.cancel() })
            fail("a cancelled download must throw")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("cancelled"))
        }
        val kept = ResumableDownload.partOf(target()).length()
        assertTrue("the partial file is kept", kept > 0 && kept < data.size)
        ResumableDownload.fetch("$base/f", target())
        assertArrayEquals(data, target().readBytes())
        assertEquals("bytes=$kept-", requests.last().first)
    }

    @Test
    fun `a file over the cap is refused and nothing is kept`() {
        try {
            ResumableDownload.fetch("$base/f", target(), maxBytes = 1024)
            fail("over the cap must throw")
        } catch (e: ResumableDownload.TooLargeException) {
            assertEquals(1024L, e.maxBytes)
        }
        assertFalse(ResumableDownload.partOf(target()).exists())
        assertFalse(target().exists())
    }

    @Test
    fun `a missing file is an HTTP error and not a retry`() {
        try {
            ResumableDownload.fetch("$base/gone", target(), sleep = { fail("a 404 must not be retried") })
            fail("a 404 must throw")
        } catch (e: Http.HttpException) {
            assertEquals(404, e.status)
        }
    }

    @Test
    fun `credentials do not follow a redirect to another host`() {
        ResumableDownload.fetch("$base/moved", target(), headers = mapOf("Authorization" to "Bearer secret", "Accept" to "*/*"))
        assertEquals("Bearer secret", authorizations.first())
        assertNull("the second hop is another host", authorizations.last())
        assertArrayEquals(data, target().readBytes())
    }

    @Test
    fun `cookie and referer follow a redirect on the same host`() {
        val headers = mapOf("Cookie" to "PHPSESSID=abc", "Referer" to "https://site.example/game")
        ResumableDownload.fetch("$base/same", target(), headers = headers)
        assertEquals(listOf<Pair<String?, String?>>(
            "PHPSESSID=abc" to "https://site.example/game",
            "PHPSESSID=abc" to "https://site.example/game",
        ), identifying.toList())
        assertArrayEquals(data, target().readBytes())
    }

    @Test
    fun `cookie and referer are dropped when a redirect changes host`() {
        val headers = mapOf("Cookie" to "PHPSESSID=abc", "Referer" to "https://site.example/game")
        ResumableDownload.fetch("$base/moved", target(), headers = headers)
        assertEquals("PHPSESSID=abc" to "https://site.example/game", identifying.first())
        assertEquals(null to null, identifying.last())
    }

    @Test
    fun `the Content-Range header and the sidecar parse and round trip`() {
        assertEquals(100L to 200L, ResumableDownload.parseContentRange("bytes 100-199/200"))
        assertEquals(-1L to 200L, ResumableDownload.parseContentRange("bytes */200"))
        assertEquals(0L to -1L, ResumableDownload.parseContentRange("bytes 0-9/*"))
        assertNull(ResumableDownload.parseContentRange("junk"))
        assertNull(ResumableDownload.parseContentRange(null))
        val meta = ResumableDownload.Meta("\"x\"", "Mon", 5L)
        assertEquals(meta, ResumableDownload.Meta.decode(meta.encode()))
        assertEquals(ResumableDownload.Meta(null, null, 7L), ResumableDownload.Meta.decode(ResumableDownload.Meta(null, null, 7L).encode()))
        assertNull(ResumableDownload.Meta.decode("only\ntwo"))
        assertNull("a weak ETag cannot validate a range", ResumableDownload.Meta("W/\"x\"", null, 1L).validator())
        assertEquals("Mon", ResumableDownload.Meta("W/\"x\"", "Mon", 1L).validator())
        assertEquals(1_000L, ResumableDownload.backoff(1))
        assertEquals(30_000L, ResumableDownload.backoff(9))
    }
}
