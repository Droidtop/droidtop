package dev.droidtop.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The container's app icons in droidtop's chrome (docs/SPEC.md 2b "Linux app icons", Droidtop/tracker#689). */
class ContainerIconsTest {
    @Test
    fun `only a theme name or an absolute PNG or SVG path is looked for`() {
        assertTrue(ContainerIcons.lookable("foot"))
        assertTrue(ContainerIcons.lookable("org.gnome.Calculator"))
        assertTrue(ContainerIcons.lookable("/usr/share/foo/foo.png"))
        assertFalse(ContainerIcons.lookable("../../etc/passwd"))
        assertFalse(ContainerIcons.lookable("/usr/share/../../etc/x.png"))
        assertFalse(ContainerIcons.lookable("/usr/share/foo/foo.xpm"))
        assertFalse(ContainerIcons.lookable("two words"))
        assertFalse(ContainerIcons.lookable(".."))
    }

    @Test
    fun `the names go in as arguments after the script, each with its token`() {
        val command = ContainerIcons.command(listOf("foot", "pcmanfm"))
        assertEquals(listOf("/bin/sh", "-c"), command.take(2))
        assertEquals(ContainerIcons.SCRIPT, command[2])
        assertEquals(ContainerIcons.IN_CONTAINER_DIR, command[4])
        assertEquals(
            listOf(ContainerLauncher.token("foot"), "foot", ContainerLauncher.token("pcmanfm"), "pcmanfm"),
            command.drop(5),
        )
        assertFalse(ContainerIcons.SCRIPT.contains("foot"))
    }

    @Test
    fun `a cached icon is found PNG first, and an empty file is not one`() {
        val dir = Files.createTempDirectory("icons").toFile()
        try {
            val token = ContainerLauncher.token("foot")
            assertNull(ContainerIcons.cached(dir, "foot"))
            File(dir, "$token.svg").writeText("<svg/>")
            assertEquals("$token.svg", ContainerIcons.cached(dir, "foot")?.name)
            File(dir, "$token.png").writeBytes(byteArrayOf(1))
            assertEquals("$token.png", ContainerIcons.cached(dir, "foot")?.name)
            File(dir, "${ContainerLauncher.token("empty")}.png").writeBytes(ByteArray(0))
            assertNull(ContainerIcons.cached(dir, "empty"))
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }
}
