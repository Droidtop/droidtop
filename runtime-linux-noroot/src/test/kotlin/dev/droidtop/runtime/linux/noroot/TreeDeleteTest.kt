package dev.droidtop.runtime.linux.noroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class TreeDeleteTest {
    @Test
    fun `removes the tree but never enters a symlink out of it`() {
        val scratch = Files.createTempDirectory("tree-delete-test").toFile()
        val fence = File(scratch, "containers").also { it.mkdirs() }
        val victim = File(scratch, "user-data").also { it.mkdirs() }
        File(victim, "precious").writeText("keep me")

        val tree = File(fence, "c1/rootfs").also { it.mkdirs() }
        File(tree, "usr/bin").mkdirs()
        File(tree, "usr/bin/tool").writeText("x")
        // An absolute link, the way a rootfs points at / on the host.
        Files.createSymbolicLink(File(tree, "z").toPath(), victim.toPath())
        // A directory the guest made read-only.
        val locked = File(tree, "locked").also { it.mkdirs() }
        File(locked, "inside").writeText("y")
        Files.setPosixFilePermissions(locked.toPath(), PosixFilePermissions.fromString("r-x------"))

        TreeDelete.delete(File(fence, "c1"), fence)

        assertFalse(File(fence, "c1").exists())
        assertEquals("keep me", File(victim, "precious").readText())
        assertTrue(fence.isDirectory)
        File(victim, "precious").delete()
        victim.delete()
        fence.delete()
        scratch.delete()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses anything that is not strictly inside the fence`() {
        val scratch = Files.createTempDirectory("tree-delete-fence").toFile()
        try {
            TreeDelete.delete(scratch, File(scratch, "containers"))
        } finally {
            assertTrue(scratch.exists())
            scratch.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses the fence itself`() {
        val scratch = Files.createTempDirectory("tree-delete-self").toFile()
        try {
            TreeDelete.delete(scratch, scratch)
        } finally {
            assertTrue(scratch.exists())
            scratch.delete()
        }
    }
}
