package dev.droidtop.input.chinese

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Droidtop/tracker#903 H8b: the pin the build and the data download share, and the list of the data's files. */
class RimePinTest {
    private val good = """
        # a comment
        tag=rime-engine-56737cd2
        sha256=31d8f3b38b8add6c49c1d5a9db569335a7b6963013d6cb86998822dbb020c938
        data_gz_sha256=21595d0426286163ff8c9a51f066e38494a7df5912e6db14f3530af0c620dfb0
        data_files_sha256=148e30e77df286d2312bdcc958d16c53288d1e443ec98aae46b4278113f9b0a0
        data_size=14802733
    """.trimIndent()

    private fun moduleFile(name: String): File = listOf(File(name), File("input-chinese/$name")).first { it.isFile }

    @Test fun `the pin gives the release, the archive's address and the checks`() {
        val pin = RimePin.parse(good)
        assertEquals("rime-engine-56737cd2", pin.tag)
        assertEquals("31d8f3b38b8add6c49c1d5a9db569335a7b6963013d6cb86998822dbb020c938", pin.engineSha256)
        assertEquals("21595d0426286163ff8c9a51f066e38494a7df5912e6db14f3530af0c620dfb0", pin.dataGzSha256)
        assertEquals("148e30e77df286d2312bdcc958d16c53288d1e443ec98aae46b4278113f9b0a0", pin.dataFilesSha256)
        assertEquals(14_802_733L, pin.dataSize)
        assertEquals("https://github.com/Droidtop/droidtop/releases/download/rime-engine-56737cd2/rime-data-luna.tar.gz", pin.dataUrl)
        assertEquals("https://github.com/Droidtop/droidtop/releases/download/rime-engine-56737cd2/rime-data-luna-sources.tar.gz", pin.sourcesUrl)
    }

    @Test fun `the module's own pin parses and names the release H8a published`() {
        val pin = RimePin.parse(moduleFile("rime-engine.pin").readText())
        assertEquals(good.lines().filter { !it.startsWith("#") }.map { it.substringBefore('=') to it.substringAfter('=') },
            listOf("tag" to pin.tag, "sha256" to pin.engineSha256, "data_gz_sha256" to pin.dataGzSha256,
                "data_files_sha256" to pin.dataFilesSha256, "data_size" to pin.dataSize.toString()))
    }

    @Test fun `the module's file list is the one the pin names, and lists the schemas the keyboard selects`() {
        val pin = RimePin.parse(moduleFile("rime-engine.pin").readText())
        val manifest = RimeManifest.parse(moduleFile("rime-data-luna.files.sha256").readText(), pin)
        assertEquals(58, manifest.files.size)
        for (schema in RimeSchemas.SCHEMAS) {
            assertTrue(schema, "shared/build/$schema.schema.yaml" in manifest.files)
            assertTrue(schema, "shared/build/$schema.prism.bin" in manifest.files)
        }
        for (file in listOf("shared/build/luna_pinyin.table.bin", "shared/opencc/t2s.json", "shared/opencc/t2tw.json", "shared/opencc/t2hk.json")) {
            assertTrue(file, file in manifest.files)
        }
    }

    private fun refused(text: String, says: String) {
        try {
            RimePin.parse(text)
            fail("parsed: $text")
        } catch (e: IllegalArgumentException) {
            assertTrue("${e.message} names $says", e.message!!.contains(says))
        }
    }

    @Test fun `a broken pin is refused, naming what is wrong`() {
        refused(good.lines().filter { !it.startsWith("data_files_sha256") }.joinToString("\n"), "no data_files_sha256")
        refused(good + "\ntag=again", "tag given twice")
        refused(good.replace("sha256=31d8", "sha256=31D8"), "bad sha256")
        refused(good.replace("data_gz_sha256=2159", "data_gz_sha256=215"), "bad data_gz_sha256")
        refused(good.replace("data_size=14802733", "data_size=0"), "bad data_size")
        refused(good.replace("tag=rime-engine-56737cd2", "tag=../x y"), "bad tag")
        refused(good + "\njust words", "not key=value")
    }

    private fun manifestRefused(text: String, says: String, pin: RimePin? = null) {
        try {
            if (pin == null) RimeManifest.parse(text) else RimeManifest.parse(text, pin)
            fail("parsed: $text")
        } catch (e: IllegalArgumentException) {
            assertTrue("${e.message} names $says", e.message!!.contains(says))
        }
    }

    @Test fun `a file list that is not the pinned one, or names a path outside shared, is refused`() {
        val sha = "a".repeat(64)
        manifestRefused("$sha  shared/a\n", "not the list", RimePin.parse(good))
        manifestRefused("$sha  shared/../etc/passwd\n", "bad path")
        manifestRefused("$sha  /shared/a\n", "bad path")
        manifestRefused("$sha  other/a\n", "bad path")
        manifestRefused("$sha  shared//a\n", "bad path")
        manifestRefused("$sha  shared/a\n$sha  shared/a\n", "listed twice")
        manifestRefused("$sha shared/a\n", "not a sha256sum line")
        manifestRefused("\n", "no files")
        assertEquals(mapOf("shared/a" to sha, "shared/b c" to sha), RimeManifest.parse("$sha  shared/a\n$sha *shared/b c\n").files)
    }
}
