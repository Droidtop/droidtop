package dev.droidtop.input.chinese

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Droidtop/tracker#903 H8b: the tar reader the data is unpacked with. */
class RimeTarTest {
    private fun read(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        RimeTar.read(ByteArrayInputStream(bytes)) { path, size, body ->
            val content = body.readBytes()
            assertEquals(size, content.size.toLong())
            out[path] = content
        }
        return out
    }

    private fun refused(bytes: ByteArray) {
        try {
            read(bytes)
            fail("read")
        } catch (e: java.io.IOException) {
            // refused
        }
    }

    @Test fun `files are read with their paths, directories skipped, and a body is read only as far as it goes`() {
        val big = ByteArray(1500) { it.toByte() }
        val files = read(TarWriter().directory("shared/").file("shared/a", "one".toByteArray()).file("shared/big", big).file("shared/empty", ByteArray(0)).bytes())
        assertEquals(listOf("shared/a", "shared/big", "shared/empty"), files.keys.toList())
        assertArrayEquals(big, files["shared/big"])
        // A visitor that reads nothing still leaves the reader at the next entry.
        val seen = ArrayList<String>()
        RimeTar.read(ByteArrayInputStream(TarWriter().file("shared/big", big).file("shared/b", "two".toByteArray()).bytes())) { path, _, _ -> seen += path }
        assertEquals(listOf("shared/big", "shared/b"), seen)
    }

    @Test fun `GNU long names, pax paths and ustar prefixes name the file`() {
        val long = "shared/" + "x".repeat(150)
        val files = read(
            TarWriter().longName(long, "long".toByteArray())
                .pax("shared/пакс/文件", "pax".toByteArray())
                .file("name", "prefixed".toByteArray(), ustarPrefix = "shared/deep")
                .file("shared/after", "plain".toByteArray())
                .bytes(),
        )
        assertEquals(listOf(long, "shared/пакс/文件", "shared/deep/name", "shared/after"), files.keys.toList())
        assertArrayEquals("prefixed".toByteArray(), files["shared/deep/name"])
    }

    @Test fun `links, a bad checksum and an archive cut short are refused`() {
        refused(TarWriter().file("shared/l", ByteArray(0), type = '2', link = "x").bytes())
        refused(TarWriter().file("shared/h", ByteArray(0), type = '1', link = "x").bytes())
        val corrupt = TarWriter().file("shared/a", "one".toByteArray()).bytes()
        corrupt[10] = 'Z'.code.toByte()
        refused(corrupt)
        val whole = TarWriter().file("shared/a", ByteArray(2000)).bytes()
        refused(whole.copyOf(700))
        refused(TarWriter().file("shared/a", "one".toByteArray()).bytes(ended = false))
    }
}
