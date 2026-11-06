package dev.droidtop.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The one size measurement, for a container's page and its row in the list (docs/SPEC.md 3d; Droidtop/tracker#911). */
class ContainerDiskUsageTest {
    private val scratch: File = Files.createTempDirectory("disk-usage-test").toFile()

    @After
    fun cleanUp() {
        scratch.walkTopDown().forEach { it.setReadable(true) }
        scratch.deleteRecursively()
    }

    @Test
    fun `files are counted once and links are not followed`() {
        File(scratch, "etc").mkdirs()
        File(scratch, "etc/a").writeBytes(ByteArray(100))
        File(scratch, "b").writeBytes(ByteArray(23))
        Files.createSymbolicLink(File(scratch, "loop").toPath(), scratch.toPath())
        assertEquals(123L, ContainerDiskUsage.bytesUnder(scratch))
        assertEquals(0L, ContainerDiskUsage.bytesUnder(File(scratch, "missing")))
    }

    @Test
    fun `a folder droidtop cannot read gives no size rather than a short one`() {
        File(scratch, "b").writeBytes(ByteArray(23))
        val closed = File(scratch, "root").apply { mkdirs() }
        File(closed, "secret").writeBytes(ByteArray(1000))
        closed.setReadable(false, false)
        // Root reads it anyway (this test is then about nothing).
        assumeFalse("running as root", closed.list() != null)
        try {
            ContainerDiskUsage.bytesUnder(scratch)
            fail("a partial size was returned")
        } catch (expected: ContainerDiskUsage.Unreadable) {
        }
    }
}
