package dev.droidtop.input.japanese

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.UnknownHostException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Droidtop/tracker#903 H7c: the dictionary's download, checks and placing, against a fake server. */
class MozcDataTest {
    private val dir: File = Files.createTempDirectory("mozc-data").toFile()

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    private val dictionary = ByteArray(300_000) { (it * 31 + it / 7).toByte() }
    private val archive = gzip(dictionary)

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun pin(gz: ByteArray = archive, data: ByteArray = dictionary) =
        MozcPin("mozc-engine-test", "0".repeat(64), sha(gz), data.size.toLong(), sha(data))

    /** Serves [body] with [length] (its size by default) and counts requests. */
    private class Server(val code: Int = 200, val body: ByteArray = ByteArray(0), val length: Long = body.size.toLong(), val failAfter: Int = -1) : MozcData.Fetcher {
        var requests = 0
        var url = ""

        override fun open(url: String): MozcData.Response {
            requests++
            this.url = url
            val stream: InputStream = if (failAfter < 0) ByteArrayInputStream(body) else Dropping(body, failAfter)
            return MozcData.Response(code, length, if (code == 200) stream else null)
        }
    }

    /** A connection that drops after [after] bytes. */
    private class Dropping(body: ByteArray, private val after: Int) : InputStream() {
        private val inner = ByteArrayInputStream(body)
        private var read = 0
        override fun read(): Int {
            if (read >= after) throw IOException("connection reset")
            read++
            return inner.read()
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (read >= after) throw IOException("connection reset")
            val n = inner.read(b, off, minOf(len, after - read))
            if (n > 0) read += n
            return n
        }
    }

    private fun onlyTheDictionaryOrNothing(present: Boolean) {
        val names = dir.list()!!.toSet()
        assertEquals(if (present) setOf(MozcData.FILE) else emptySet<String>(), names)
    }

    @Test fun `a good download is checked twice, unpacked and moved into place`() {
        val server = Server(body = archive)
        val percents = ArrayList<Int>()
        assertNull(MozcData.ensure(dir, pin(), server) { percents += it })
        assertEquals("https://github.com/Droidtop/droidtop/releases/download/mozc-engine-test/mozc.data.gz", server.url)
        assertArrayEquals(dictionary, MozcData.file(dir).readBytes())
        onlyTheDictionaryOrNothing(present = true)
        assertTrue(MozcData.present(dir, pin()))
        assertEquals("progress rises to 100", 100, percents.last())
        assertEquals(percents.sorted(), percents)

        assertNull("in place: nothing is downloaded again", MozcData.ensure(dir, pin(), server))
        assertEquals(1, server.requests)
    }

    @Test fun `an archive with the wrong SHA-256 is not kept`() {
        val other = gzip(ByteArray(1000) { 7 })
        assertEquals(MozcData.Failure.Checksum, MozcData.ensure(dir, pin(), Server(body = other)))
        onlyTheDictionaryOrNothing(present = false)
    }

    @Test fun `a truncated download is not kept, whether the connection drops or ends early`() {
        assertEquals(MozcData.Failure.Truncated, MozcData.ensure(dir, pin(), Server(body = archive, failAfter = archive.size / 2)))
        onlyTheDictionaryOrNothing(present = false)
        val half = archive.copyOf(archive.size / 2)
        assertEquals(MozcData.Failure.Truncated, MozcData.ensure(dir, pin(), Server(body = half, length = archive.size.toLong())))
        onlyTheDictionaryOrNothing(present = false)
        assertEquals("no length given: the hash still refuses it", MozcData.Failure.Checksum, MozcData.ensure(dir, pin(), Server(body = half, length = -1)))
        onlyTheDictionaryOrNothing(present = false)
    }

    @Test fun `an archive that matches but unpacks to something else is not kept`() {
        val wrong = ByteArray(dictionary.size) { 1 }
        val gz = gzip(wrong)
        assertEquals(MozcData.Failure.Unpacked, MozcData.ensure(dir, pin(gz = gz), Server(body = gz)))
        onlyTheDictionaryOrNothing(present = false)
        val notGzip = ByteArray(5000) { 3 }
        assertEquals(MozcData.Failure.Unpacked, MozcData.ensure(dir, pin(gz = notGzip), Server(body = notGzip)))
        onlyTheDictionaryOrNothing(present = false)
    }

    @Test fun `no connection and a server's refusal say so`() {
        val offline = MozcData.Fetcher { throw UnknownHostException("github.com") }
        assertEquals(MozcData.Failure.Network("github.com"), MozcData.ensure(dir, pin(), offline))
        assertEquals(MozcData.Failure.Server(404, "github.com"), MozcData.ensure(dir, pin(), Server(code = 404)))
        onlyTheDictionaryOrNothing(present = false)
    }

    @Test fun `a stale file of the wrong size is replaced, and leftovers of a killed download go`() {
        MozcData.file(dir).writeBytes(ByteArray(10))
        File(dir, "mozc.data.part").writeBytes(ByteArray(10))
        File(dir, "mozc.data.gz.part").writeBytes(ByteArray(10))
        assertFalse(MozcData.present(dir, pin()))
        assertNull(MozcData.ensure(dir, pin(), Server(body = archive)))
        onlyTheDictionaryOrNothing(present = true)
    }

    @Test fun `deleting removes the dictionary, and forgetting keeps only it`() {
        assertNull(MozcData.ensure(dir, pin(), Server(body = archive)))
        File(dir, "user_history.db").writeText("learned")
        File(dir, "segment.db").writeText("learned")
        MozcData.forgetFiles(dir)
        onlyTheDictionaryOrNothing(present = true)
        assertTrue(MozcData.delete(dir))
        onlyTheDictionaryOrNothing(present = false)
    }
}
