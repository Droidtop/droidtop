package dev.droidtop.net

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Droidtop/tracker#766: one way to replace a file whole. */
class AtomicWriteTest {
    private val dir: File = Files.createTempDirectory("atomic").toFile()

    @Test
    fun `it writes a new file, replaces an old one and leaves no temp file`() {
        val target = File(dir, "deep/state.json")
        AtomicWrite.text(target, "one")
        assertEquals("one", target.readText())
        AtomicWrite.bytes(target, "two".toByteArray())
        assertEquals("two", target.readText())
        assertEquals(listOf("state.json"), target.parentFile!!.list()!!.toList())
    }

    @Test
    fun `a failed write leaves the old file as it was and no temp file`() {
        val target = File(dir, "kept.json").apply { writeText("old") }
        try {
            AtomicWrite.stream(target) { it.write("half".toByteArray()); throw IOException("disk full") }
            fail("the failure is thrown")
        } catch (expected: IOException) {
            assertEquals("disk full", expected.message)
        }
        assertEquals("old", target.readText())
        assertTrue(dir.list()!!.all { it == "kept.json" })
    }
}
