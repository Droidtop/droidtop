package dev.droidtop.runtime.windows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WinePrefixSavesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun users(prefix: File) = File(prefix, ".wine/drive_c/users").apply { mkdirs() }

    @Test
    fun `saves are copied, a name already there is kept, other folders are not touched`() {
        val from = tmp.newFolder("from")
        val to = tmp.newFolder("to")
        File(users(from), "xuser/Documents/My Games/game.sav").apply { parentFile.mkdirs(); writeText("old prefix") }
        File(users(from), "xuser/AppData/Roaming/Studio/cfg.ini").apply { parentFile.mkdirs(); writeText("cfg") }
        File(users(from), "xuser/Desktop/shortcut.lnk").apply { parentFile.mkdirs(); writeText("not a save") }
        File(users(to), "xuser/Documents/My Games/game.sav").apply { parentFile.mkdirs(); writeText("new prefix") }

        val copied = WinePrefixSaves.copy(from, to)

        assertEquals(1, copied)
        assertEquals("new prefix", File(users(to), "xuser/Documents/My Games/game.sav").readText())
        assertEquals("cfg", File(users(to), "xuser/AppData/Roaming/Studio/cfg.ini").readText())
        assertFalse(File(users(to), "xuser/Desktop/shortcut.lnk").exists())
    }

    @Test
    fun `links are neither followed nor copied`() {
        val from = tmp.newFolder("from2")
        val to = tmp.newFolder("to2")
        val storage = tmp.newFolder("device-storage").apply { File(this, "photo.jpg").writeText("not the game's") }
        val docs = File(users(from), "xuser/Documents").apply { mkdirs() }
        Files.createSymbolicLink(File(docs, "Linked").toPath(), storage.toPath())
        // A user folder that is itself a link is skipped as a whole.
        Files.createSymbolicLink(File(users(from), "elsewhere").toPath(), storage.toPath())

        assertEquals(0, WinePrefixSaves.copy(from, to))
        assertFalse(File(users(to), "xuser/Documents/Linked/photo.jpg").exists())
        assertFalse(File(users(to), "elsewhere").exists())
        assertTrue(File(storage, "photo.jpg").exists())
    }
}
