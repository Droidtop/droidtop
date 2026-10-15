package dev.droidtop.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** docs/SPEC.md 3d "Exported apps" (Droidtop/tracker#555 D5): a sibling's apps in the desktop's menus. */
class ContainerExportsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val gimp = ContainerApp(id = "gimp.desktop", name = "GIMP", command = listOf("gimp-2.10"), terminal = false, genericName = "Image Editor", icon = "gimp")
    private val htop = ContainerApp(id = "htop.desktop", name = "htop", command = listOf("htop"), terminal = true)
    private val export = ExportedApp("droidtop-sibling-1a2b3c4d", "Arch", gimp)

    @Test
    fun `a token is sixteen hex digits, the same each time, and different per container`() {
        assertTrue(Regex("[0-9a-f]{16}").matches(export.token))
        assertEquals(export.token, export.copy().token)
        assertFalse(export.token == export.copy(containerId = "droidtop-sibling-ffff0000").token)
    }

    @Test
    fun `the entry names the container and launches through the helper with the token only`() {
        val entry = ContainerExports.desktopEntry(export)
        assertTrue(entry.contains("Name=GIMP (on Arch)\n"))
        assertTrue(entry.contains("Exec=sh ${ContainerLauncher.HELPER} ${export.token}\n"))
        assertTrue(entry.contains("Icon=gimp\n"))
        assertFalse(entry.contains("gimp-2.10"))
        assertTrue(ContainerExports.entryFileName(export).startsWith(ContainerExports.ENTRY_PREFIX))
        assertFalse(ContainerExports.entryFileName(export).startsWith(ContainerLauncher.ENTRY_PREFIX))
    }

    @Test
    fun `the list survives a write and a read, and a field with a tab is left out`() {
        val list = listOf(export, ExportedApp("droidtop-sibling-1a2b3c4d", "Arch", htop.copy(command = listOf("htop", "-d", "10"))))
        assertEquals(list, ContainerExports.decode(ContainerExports.encode(list)))
        val bad = ExportedApp("c", "Arch", gimp.copy(name = "GI\tMP"))
        assertEquals(listOf(export), ContainerExports.decode(ContainerExports.encode(listOf(bad, export))))
        assertEquals(emptyList<ExportedApp>(), ContainerExports.decode("garbage\n\n"))
    }

    @Test
    fun `switching an export on twice keeps one, off removes it, and a deleted container takes its exports`() {
        val on = ContainerExports.with(ContainerExports.with(emptyList(), export, true), export, true)
        assertEquals(listOf(export), on)
        assertEquals(emptyList<ExportedApp>(), ContainerExports.with(on, export, false))
        val other = ExportedApp("droidtop-sibling-99", "Debian", htop)
        assertEquals(listOf(other), ContainerExports.without(on + other, export.containerId))
    }

    @Test
    fun `a request names an export only by a token droidtop published`() {
        assertEquals(export, ContainerExports.forToken(listOf(export), export.token))
        assertNull(ContainerExports.forToken(listOf(export), "0123456789abcdef"))
    }

    @Test
    fun `saving writes each exported entry and removes stale ones, never the library's`() {
        val files = tmp.root
        val apps = ContainerLauncher.hostApplicationsDir(files).apply { mkdirs() }
        val library = File(apps, "${ContainerLauncher.ENTRY_PREFIX}0123456789abcdef.desktop").apply { writeText("library") }
        val stale = File(apps, "${ContainerExports.ENTRY_PREFIX}ffffffffffffffff.desktop").apply { writeText("stale") }
        ContainerExports.save(files, listOf(export))
        assertTrue(File(apps, ContainerExports.entryFileName(export)).isFile)
        assertFalse(stale.exists())
        assertTrue(library.isFile)
        assertEquals(listOf(export), ContainerExports.read(files))
        ContainerExports.save(files, emptyList())
        assertFalse(File(apps, ContainerExports.entryFileName(export)).exists())
    }
}
