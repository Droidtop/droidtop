package dev.droidtop.input.chinese

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.UnknownHostException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Droidtop/tracker#903 H8b: the data's download, checks, unpacking and placing, against a fake server. */
class RimeDataTest {
    private val dir: File = Files.createTempDirectory("rime-data").toFile()

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    private val table = ByteArray(200_000) { (it * 31 + it / 7).toByte() }
    private val schema = "schema:\n  schema_id: luna_pinyin_simp\n".toByteArray()
    private val t2s = "{\"name\": \"t2s\"}".toByteArray()

    private fun files() = linkedMapOf(
        "shared/build/luna_pinyin.table.bin" to table,
        "shared/build/luna_pinyin_simp.schema.yaml" to schema,
        "shared/opencc/t2s.json" to t2s,
    )

    private fun sha(bytes: ByteArray) = RimeManifest.sha256(bytes)

    private fun manifestText(files: Map<String, ByteArray> = files()) = files.entries.joinToString("") { "${sha(it.value)}  ${it.key}\n" }

    private fun archive(files: Map<String, ByteArray> = files()): ByteArray {
        val tar = TarWriter().directory("shared/").directory("shared/build/").directory("shared/opencc/")
        files.forEach { (path, body) -> tar.file(path, body) }
        return tar.gzip()
    }

    private val good = archive()
    private val manifest = RimeManifest.parse(manifestText())

    private fun pin(gz: ByteArray = good) = RimePin("rime-engine-test", "0".repeat(64), sha(gz), sha(manifestText().toByteArray()), table.size + schema.size + t2s.size.toLong())

    /** Serves [body] with [length] (its size by default) and counts requests. */
    private class Server(val code: Int = 200, val body: ByteArray = ByteArray(0), val length: Long = body.size.toLong(), val failAfter: Int = -1) : RimeData.Fetcher {
        var requests = 0
        var url = ""

        override fun open(url: String): RimeData.Response {
            requests++
            this.url = url
            val stream: InputStream = if (failAfter < 0) ByteArrayInputStream(body) else Dropping(body, failAfter)
            return RimeData.Response(code, length, if (code == 200) stream else null)
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

    private fun onlyWhatIsKept(vararg names: String) {
        assertEquals(names.toSet(), dir.list()!!.toSet())
    }

    @Test fun `the data downloads, unpacks, every file is checked and it moves into shared, once`() {
        val server = Server(body = good)
        val heard = ArrayList<Int>()
        assertNull(RimeData.ensure(dir, pin(), manifest, server) { heard += it })
        assertEquals("https://github.com/Droidtop/droidtop/releases/download/rime-engine-test/rime-data-luna.tar.gz", server.url)
        assertTrue(RimeData.present(dir, pin()))
        assertTrue(RimeData.verify(dir, manifest))
        assertTrue(File(dir, "shared/build/luna_pinyin.table.bin").readBytes().contentEquals(table))
        assertEquals(pin().dataGzSha256, File(dir, "shared/${RimeData.MARKER}").readText())
        assertEquals(100, heard.last())
        assertTrue(heard.zipWithNext().all { (a, b) -> a < b })
        onlyWhatIsKept("shared")
        assertNull(RimeData.ensure(dir, pin(), manifest, server))
        assertEquals(1, server.requests)
    }

    @Test fun `an archive that is not the pinned one is not kept`() {
        val other = archive(files() + ("shared/extra" to ByteArray(3)))
        assertEquals(RimeData.Failure.Checksum, RimeData.ensure(dir, pin(), manifest, Server(body = other)))
        assertFalse(RimeData.present(dir, pin()))
        onlyWhatIsKept()
    }

    @Test fun `a pinned archive whose files are not the list's is not kept`() {
        // A file the list does not name.
        val extra = archive(files() + ("shared/extra" to ByteArray(3)))
        assertEquals(RimeData.Failure.Unpacked, RimeData.ensure(dir, pin(extra), manifest, Server(body = extra)))
        onlyWhatIsKept()
        // A file the list names, with other bytes.
        val changed = archive(files() + ("shared/opencc/t2s.json" to "{}".toByteArray()))
        assertEquals(RimeData.Failure.Unpacked, RimeData.ensure(dir, pin(changed), manifest, Server(body = changed)))
        onlyWhatIsKept()
        // A file the list names, missing.
        val missing = archive(files() - "shared/opencc/t2s.json")
        assertEquals(RimeData.Failure.Unpacked, RimeData.ensure(dir, pin(missing), manifest, Server(body = missing)))
        onlyWhatIsKept()
        assertFalse(RimeData.present(dir, pin()))
    }

    @Test fun `an archive that reaches outside its directory, or holds a link, is refused`() {
        val escape = TarWriter().file("shared/../../escaped", ByteArray(4)).also { t -> files().forEach { t.file(it.key, it.value) } }.gzip()
        assertEquals(RimeData.Failure.Unpacked, RimeData.ensure(dir, pin(escape), manifest, Server(body = escape)))
        assertFalse(File(dir.parentFile, "escaped").exists())
        onlyWhatIsKept()
        val link = TarWriter().file("shared/link", ByteArray(0), type = '2', link = "/etc/passwd").gzip()
        assertEquals(RimeData.Failure.Unpacked, RimeData.ensure(dir, pin(link), manifest, Server(body = link)))
        onlyWhatIsKept()
        val notGzip = "plain bytes".toByteArray()
        assertEquals(RimeData.Failure.Unpacked, RimeData.ensure(dir, pin(notGzip), manifest, Server(body = notGzip)))
        onlyWhatIsKept()
        val unended = TarWriter().also { t -> files().forEach { t.file(it.key, it.value) } }.gzip(ended = false)
        assertEquals(RimeData.Failure.Unpacked, RimeData.ensure(dir, pin(unended), manifest, Server(body = unended)))
        onlyWhatIsKept()
    }

    @Test fun `a download that stops partway says so, whether or not the length was given`() {
        assertEquals(RimeData.Failure.Truncated, RimeData.ensure(dir, pin(), manifest, Server(body = good, failAfter = 100)))
        assertEquals(RimeData.Failure.Truncated, RimeData.ensure(dir, pin(), manifest, Server(body = good.copyOf(good.size / 2), length = good.size.toLong())))
        assertEquals(RimeData.Failure.Checksum, RimeData.ensure(dir, pin(), manifest, Server(body = good.copyOf(good.size / 2), length = -1)))
        onlyWhatIsKept()
    }

    @Test fun `no network and a server's refusal are told apart`() {
        val offline = RimeData.Fetcher { throw UnknownHostException("github.com") }
        assertEquals(RimeData.Failure.Network("github.com"), RimeData.ensure(dir, pin(), manifest, offline))
        assertEquals(RimeData.Failure.Server(404, "github.com"), RimeData.ensure(dir, pin(), manifest, Server(code = 404)))
        onlyWhatIsKept()
    }

    @Test fun `a new pin's data replaces the old, and the user's directory is never touched`() {
        assertNull(RimeData.ensure(dir, pin(), manifest, Server(body = good)))
        val user = File(dir, "user/luna_pinyin.userdb").apply { mkdirs() }
        File(user, "CURRENT").writeText("learned")
        File(dir, "shared/stale.yaml").writeText("from before")
        // The same files, archived again: another archive SHA-256, so another pin.
        val again = TarWriter().also { t -> files().forEach { t.file(it.key, it.value) } }.directory("shared/").gzip()
        val newer = pin(again)
        assertFalse(RimeData.present(dir, newer))
        assertNull(RimeData.ensure(dir, newer, manifest, Server(body = again)))
        assertTrue(RimeData.present(dir, newer))
        assertFalse(File(dir, "shared/stale.yaml").exists())
        assertEquals("learned", File(user, "CURRENT").readText())
        onlyWhatIsKept("shared", "user")
    }

    @Test fun `leftovers of an interrupted download are cleared first`() {
        File(dir, "rime-data-luna.tar.gz.part").writeText("old")
        File(dir, "unpack.part/shared").mkdirs()
        File(dir, "shared.old").mkdirs()
        assertNull(RimeData.ensure(dir, pin(), manifest, Server(body = good)))
        onlyWhatIsKept("shared")
    }

    @Test fun `delete removes the data and leaves what was learned, and forgetting removes only what was learned`() {
        assertNull(RimeData.ensure(dir, pin(), manifest, Server(body = good)))
        val user = File(dir, "user").apply { mkdirs() }
        File(user, "luna_pinyin.userdb").mkdirs()
        File(user, "luna_pinyin.userdb/000003.log").writeText("x")
        File(user, "luna_pinyin.userdb.txt").writeText("x")
        File(user, "sync/droidtop").mkdirs()
        File(user, "build").mkdirs()
        File(user, "installation.yaml").writeText("x")
        File(user, "user.yaml").writeText("x")
        assertTrue(RimeData.delete(dir))
        assertFalse(RimeData.present(dir, pin()))
        onlyWhatIsKept("user")
        RimeData.forgetFiles(user)
        assertEquals(setOf("build", "installation.yaml", "user.yaml"), user.list()!!.toSet())
    }
}
