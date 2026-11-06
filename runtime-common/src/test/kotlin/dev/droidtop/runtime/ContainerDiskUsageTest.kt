package dev.droidtop.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(DiskUsage(123L), ContainerDiskUsage.measure(scratch))
        assertEquals(DiskUsage(0L), ContainerDiskUsage.measure(File(scratch, "missing")))
    }

    @Test
    fun `a folder droidtop cannot read makes the total a floor`() {
        File(scratch, "b").writeBytes(ByteArray(23))
        val closed = File(scratch, "root").apply { mkdirs() }
        File(closed, "secret").writeBytes(ByteArray(1000))
        closed.setReadable(false, false)
        // Root reads it anyway (this test is then about nothing).
        assumeFalse("running as root", closed.list() != null)
        assertEquals(DiskUsage(23L, complete = false), ContainerDiskUsage.measure(scratch))
    }

    @Test
    fun `du's answer is read in kilobytes, and a du that met errors gives a floor`() {
        val path = "/data/user/0/dev.droidtop/files/droidspaces/rootfs/droidtop-primary"
        assertEquals(DiskUsage(1_258_291L * 1024), ContainerDiskUsage.fromDu("1258291\t$path\n", succeeded = true))
        assertEquals(
            DiskUsage(42L * 1024, complete = false),
            ContainerDiskUsage.fromDu("du: $path/proc/1: No such file or directory\n42\t$path\n", succeeded = false),
        )
        assertNull(ContainerDiskUsage.fromDu("", succeeded = false))
        assertNull(ContainerDiskUsage.fromDu("du: $path: No such file or directory\n", succeeded = false))
    }
}
