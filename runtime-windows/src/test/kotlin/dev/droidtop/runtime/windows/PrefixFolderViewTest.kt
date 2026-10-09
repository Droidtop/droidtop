package dev.droidtop.runtime.windows

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The read-only view of a prefix lists, sorts and bounds its folders and never follows a link. */
class PrefixFolderViewTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `folders come first, then files, each by name without regard to case`() {
        val root = tmp.newFolder("prefix")
        File(root, "system.reg").writeText("x")
        File(root, "drive_c").mkdir()
        File(root, "Zeta").mkdir()
        File(root, "alpha.txt").writeText("12345")
        val listing = PrefixFolderView.list(root)
        assertEquals(listOf("drive_c", "Zeta", "alpha.txt", "system.reg"), listing.entries.map { it.name })
        assertEquals(listOf(true, true, false, false), listing.entries.map { it.directory })
        assertEquals(5L, listing.entries.first { it.name == "alpha.txt" }.size)
        assertEquals(0, listing.more)
    }

    @Test
    fun `a link is listed as a link with its target, never as a folder`() {
        val root = tmp.newFolder("prefix")
        val games = tmp.newFolder("games")
        val dos = File(root, "dosdevices").also { it.mkdir() }
        Files.createSymbolicLink(File(dos, "d:").toPath(), games.toPath())
        val entry = PrefixFolderView.list(dos).entries.single()
        assertTrue(entry.link)
        assertFalse(entry.directory)
        assertEquals(games.path, entry.target)
    }

    @Test
    fun `a long folder is cut at the limit and says how many it left out`() {
        val root = tmp.newFolder("prefix")
        (1..7).forEach { File(root, "f$it.dll").writeText("") }
        val listing = PrefixFolderView.list(root, limit = 5)
        assertEquals(5, listing.entries.size)
        assertEquals(2, listing.more)
    }

    @Test
    fun `a folder that is not there lists as empty`() {
        val listing = PrefixFolderView.list(File(tmp.root, "nope"))
        assertEquals(emptyList<PrefixFolderView.Entry>(), listing.entries)
        assertNull(listing.entries.firstOrNull())
    }

    @Test
    fun `only the prefix and what is inside it is inside it`() {
        val root = tmp.newFolder("prefix")
        val inner = File(root, "drive_c").also { it.mkdir() }
        val outside = tmp.newFolder("elsewhere")
        assertTrue(PrefixFolderView.isInside(root, root))
        assertTrue(PrefixFolderView.isInside(root, inner))
        assertFalse(PrefixFolderView.isInside(root, outside))
        assertFalse(PrefixFolderView.isInside(root, File(root, "../elsewhere")))
    }
}
