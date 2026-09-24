package dev.droidtop.runtime.linux.noroot

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission

class RootfsTarExtractorTest {
    private val scratch: File = Files.createTempDirectory("tar-extract-test").toFile()
    private val rootfs = File(scratch, "rootfs")
    private val outside = File(scratch, "outside").also { it.mkdirs() }

    @After
    fun cleanUp() {
        TreeDelete.delete(rootfs, scratch)
        TreeDelete.delete(outside, scratch)
        scratch.delete()
    }

    private class TarBuilder {
        private val bytes = ByteArrayOutputStream()
        private val tar = TarArchiveOutputStream(bytes).apply { setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX) }

        fun dir(name: String, mode: Int = 0b111_101_101) = apply {
            tar.putArchiveEntry(TarArchiveEntry(name.trimEnd('/') + "/").also { it.mode = mode })
            tar.closeArchiveEntry()
        }

        fun file(name: String, content: String, mode: Int = 0b110_100_100) = apply {
            val data = content.toByteArray()
            tar.putArchiveEntry(TarArchiveEntry(name).also { it.size = data.size.toLong(); it.mode = mode })
            tar.write(data)
            tar.closeArchiveEntry()
        }

        fun symlink(name: String, target: String) = apply {
            tar.putArchiveEntry(TarArchiveEntry(name, TarConstants.LF_SYMLINK).also { it.linkName = target })
            tar.closeArchiveEntry()
        }

        fun hardlink(name: String, target: String) = apply {
            tar.putArchiveEntry(TarArchiveEntry(name, TarConstants.LF_LINK).also { it.linkName = target })
            tar.closeArchiveEntry()
        }

        fun fifo(name: String) = apply {
            tar.putArchiveEntry(TarArchiveEntry(name, TarConstants.LF_FIFO))
            tar.closeArchiveEntry()
        }

        fun build(): ByteArrayInputStream {
            tar.finish()
            tar.close()
            return ByteArrayInputStream(bytes.toByteArray())
        }
    }

    @Test
    fun `writes directories, files with their modes, symlinks and hard links`() {
        val tar = TarBuilder()
            .dir("./")
            .dir("./usr")
            .dir("./usr/bin")
            .file("./usr/bin/tool", "#!/bin/sh\n", mode = 0b111_101_101)
            .hardlink("./usr/bin/tool-again", "./usr/bin/tool")
            .symlink("./bin", "usr/bin")
            .file("./etc/os-release", "ID=test\n")
            .build()

        val result = RootfsTarExtractor.extract(tar, rootfs)

        assertEquals(emptyList<String>(), result.skipped)
        val tool = File(rootfs, "usr/bin/tool")
        assertEquals("#!/bin/sh\n", tool.readText())
        assertTrue(Files.getPosixFilePermissions(tool.toPath()).contains(PosixFilePermission.OWNER_EXECUTE))
        assertEquals("#!/bin/sh\n", File(rootfs, "usr/bin/tool-again").readText())
        assertTrue(Files.isSymbolicLink(File(rootfs, "bin").toPath()))
        assertEquals("usr/bin", Files.readSymbolicLink(File(rootfs, "bin").toPath()).toString())
        // A parent directory the archive never listed is created.
        assertEquals("ID=test\n", File(rootfs, "etc/os-release").readText())
    }

    @Test
    fun `never writes through a symlink that points out of the rootfs`() {
        // Debian ships /var/run -> /run; on the host an absolute link like
        // this points into Android's own filesystem.
        val tar = TarBuilder()
            .symlink("./escape", outside.absolutePath)
            .file("./escape/planted", "should not exist")
            .hardlink("./stolen", "./escape/planted")
            .build()

        val result = RootfsTarExtractor.extract(tar, rootfs)

        assertFalse(File(outside, "planted").exists())
        assertFalse(File(rootfs, "stolen").exists())
        assertEquals(2, result.skipped.size)
    }

    @Test
    fun `refuses entries that climb out with dot-dot`() {
        val tar = TarBuilder()
            .file("../outside/climbed", "no")
            .file("./usr/../../outside/climbed2", "no")
            .build()

        val result = RootfsTarExtractor.extract(tar, rootfs)

        assertFalse(File(outside, "climbed").exists())
        assertFalse(File(outside, "climbed2").exists())
        assertEquals(2, result.skipped.size)
    }

    @Test
    fun `a read-only directory in the image still gets its contents, and stays owner-writable`() {
        val tar = TarBuilder()
            .dir("./ro", mode = 0b101_101_101)
            .file("./ro/inside", "yes")
            .build()

        RootfsTarExtractor.extract(tar, rootfs)

        assertEquals("yes", File(rootfs, "ro/inside").readText())
        val permissions = Files.getPosixFilePermissions(File(rootfs, "ro").toPath(), LinkOption.NOFOLLOW_LINKS)
        assertTrue(permissions.contains(PosixFilePermission.OWNER_WRITE))
    }

    @Test
    fun `device nodes and fifos are skipped, not fatal`() {
        val tar = TarBuilder().fifo("./dev/initctl").file("./after", "ok").build()

        val result = RootfsTarExtractor.extract(tar, rootfs)

        assertEquals("ok", File(rootfs, "after").readText())
        assertEquals(1, result.skipped.size)
    }
}
