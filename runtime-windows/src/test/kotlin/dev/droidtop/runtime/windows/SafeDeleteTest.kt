package dev.droidtop.runtime.windows

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the 2026-09-02 incident: a recursive delete
 * pointed at a Wine prefix must remove the prefix and NOTHING the
 * prefix's symlinks point at.
 */
class SafeDeleteTest {

    private fun tempDir(): File = Files.createTempDirectory("safe-delete").toFile()

    @Test
    fun `deletes a wine-prefix shape without following its drive symlinks`() {
        val root = tempDir()
        try {
            val outside = File(root, "outside").apply { mkdirs() }
            val survivor = File(outside, "user-data.txt").apply { writeText("saves") }

            val boundary = File(root, "imagefs").apply { mkdirs() }
            val prefix = File(boundary, "home/xuser-1").apply { mkdirs() }
            File(prefix, ".wine/dosdevices").mkdirs()
            File(prefix, ".wine/drive_c/windows").mkdirs()
            File(prefix, ".wine/drive_c/windows/system.ini").writeText("ini")
            // The incident's exact shape: a drive letter pointing outside.
            Files.createSymbolicLink(
                Paths.get(File(prefix, ".wine/dosdevices/z:").path),
                outside.toPath(),
            )

            assertTrue(SafeDelete.deleteWithin(boundary, prefix))
            assertFalse(prefix.exists())
            assertTrue("data reachable through the symlink must survive", survivor.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `refuses a target outside the boundary`() {
        val root = tempDir()
        try {
            val boundary = File(root, "imagefs").apply { mkdirs() }
            val elsewhere = File(root, "elsewhere").apply { mkdirs() }
            val victim = File(elsewhere, "keep.txt").apply { writeText("keep") }

            assertFalse(SafeDelete.deleteWithin(boundary, elsewhere))
            assertTrue(victim.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `refuses a target whose parent is a symlink out of the boundary`() {
        val root = tempDir()
        try {
            val boundary = File(root, "imagefs").apply { mkdirs() }
            val outside = File(root, "outside").apply { mkdirs() }
            val victim = File(outside, "keep.txt").apply { writeText("keep") }
            Files.createSymbolicLink(
                Paths.get(File(boundary, "escape").path),
                outside.toPath(),
            )

            assertFalse(SafeDelete.deleteWithin(boundary, File(boundary, "escape/keep.txt")))
            assertTrue(victim.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a missing target is already deleted`() {
        val root = tempDir()
        try {
            val boundary = File(root, "imagefs").apply { mkdirs() }
            assertTrue(SafeDelete.deleteWithin(boundary, File(boundary, "never-existed")))
        } finally {
            root.deleteRecursively()
        }
    }
}
