package dev.droidtop.runtime.windows

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddGameTest {
    private val roms = setOf("sfc", "smc", "iso", "zip")

    @Test
    fun `a picked item is a folder, a Windows program, an HTML page, a ROM or none`() {
        assertEquals(AddGame.Kind.FOLDER, AddGame.kindOf(true, "Some Game", roms))
        assertEquals(AddGame.Kind.WINDOWS_PROGRAM, AddGame.kindOf(false, "Game.EXE", roms))
        assertEquals(AddGame.Kind.WEB_PAGE, AddGame.kindOf(false, "index.html", roms))
        assertEquals(AddGame.Kind.ROM, AddGame.kindOf(false, "Chrono Trigger (USA).sfc", roms))
        assertEquals(AddGame.Kind.UNKNOWN, AddGame.kindOf(false, "notes.txt", roms))
        assertEquals(AddGame.Kind.UNKNOWN, AddGame.kindOf(false, "README", roms))
    }

    @Test
    fun `a volume's root is never one game`() {
        assertTrue(AddGame.isVolume(File("/storage/emulated/0")))
        assertTrue(AddGame.isVolume(File("/storage/1A2B-3C4D")))
        assertTrue(AddGame.isVolume(File("/sdcard")))
        assertTrue(AddGame.isVolume(File("/")))
        assertFalse(AddGame.isVolume(File("/storage/1A2B-3C4D/Game")))
        assertFalse(AddGame.isVolume(File("/storage/emulated/0/Download/Game")))
    }

    @Test
    fun `a folder that holds a game folder is refused, one inside it or elsewhere is not`() {
        val roots = listOf(File("/storage/1A2B-3C4D/Games"))
        assertNotNull(AddGame.refusalFor(File("/storage/1A2B-3C4D/Games"), roots))
        assertNotNull(AddGame.refusalFor(File("/storage/1A2B-3C4D"), roots))
        assertNull(AddGame.refusalFor(File("/storage/1A2B-3C4D/Games/Some Game"), roots))
        assertNull(AddGame.refusalFor(File("/storage/emulated/0/Download/Some Game"), roots))
    }

    @Test
    fun `a path is under a folder only on a whole name`() {
        assertTrue(AddGame.isUnder(File("/a/Games/x"), File("/a/Games")))
        assertTrue(AddGame.isUnder(File("/a/Games"), File("/a/Games/")))
        assertFalse(AddGame.isUnder(File("/a/Games2/x"), File("/a/Games")))
    }
}
