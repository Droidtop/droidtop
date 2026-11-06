package dev.droidtop.input.japanese

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Droidtop/tracker#903 H7c: the pin the build and the dictionary download share. */
class MozcPinTest {
    private val good = """
        # a comment
        tag=mozc-engine-3.35.6354
        sha256=80d971c220b64cf956e937c7478c37810d0330493a9f7697ac4724b67b6486ca
        data_gz_sha256=a3b81db2ddbd5cccf8d14b182f9bc0940471b2d9e6a8c78abd94236bd5b5d620
        data_size=19001738
        data_sha256=7a47a2b3958baeb0796345aec2c3f380b1d783d6fa399c8a0c358a197bee6046
    """.trimIndent()

    @Test fun `the pin gives the release, the archive's address and the checks`() {
        val pin = MozcPin.parse(good)
        assertEquals("mozc-engine-3.35.6354", pin.tag)
        assertEquals("80d971c220b64cf956e937c7478c37810d0330493a9f7697ac4724b67b6486ca", pin.engineSha256)
        assertEquals("a3b81db2ddbd5cccf8d14b182f9bc0940471b2d9e6a8c78abd94236bd5b5d620", pin.dataGzSha256)
        assertEquals(19_001_738L, pin.dataSize)
        assertEquals("7a47a2b3958baeb0796345aec2c3f380b1d783d6fa399c8a0c358a197bee6046", pin.dataSha256)
        assertEquals("https://github.com/Droidtop/droidtop/releases/download/mozc-engine-3.35.6354/mozc.data.gz", pin.dataUrl)
    }

    @Test fun `the module's own pin parses and names the dictionary H7b released`() {
        val file = listOf(File("mozc-engine.pin"), File("input-japanese/mozc-engine.pin")).first { it.isFile }
        val pin = MozcPin.parse(file.readText())
        assertEquals(good.lines().filter { !it.startsWith("#") }.map { it.substringBefore('=') to it.substringAfter('=') },
            listOf("tag" to pin.tag, "sha256" to pin.engineSha256, "data_gz_sha256" to pin.dataGzSha256,
                "data_size" to pin.dataSize.toString(), "data_sha256" to pin.dataSha256))
    }

    private fun refused(text: String, says: String) {
        try {
            MozcPin.parse(text)
            fail("parsed: $text")
        } catch (e: IllegalArgumentException) {
            assertTrue("${e.message} names $says", e.message!!.contains(says))
        }
    }

    @Test fun `a broken pin is refused, naming what is wrong`() {
        refused(good.lines().filter { !it.startsWith("data_size") }.joinToString("\n"), "no data_size")
        refused(good + "\ntag=again", "tag given twice")
        refused(good.replace("sha256=80d9", "sha256=80D9"), "bad sha256")
        refused(good.replace("data_gz_sha256=a3b8", "data_gz_sha256=a3b"), "bad data_gz_sha256")
        refused(good.replace("data_size=19001738", "data_size=-1"), "bad data_size")
        refused(good.replace("tag=mozc-engine-3.35.6354", "tag=../x y"), "bad tag")
        refused(good + "\njust words", "not key=value")
    }
}
