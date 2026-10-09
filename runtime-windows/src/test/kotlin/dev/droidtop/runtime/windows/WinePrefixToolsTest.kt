package dev.droidtop.runtime.windows

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a prefix tool hands Wine (docs/SPEC.md 7c, "Prefix tools"): the part that needs no device. */
class WinePrefixToolsTest {

    @Test
    fun `an exe is handed to Wine by its own path`() {
        val launch = WinePrefixTools.launchOf(File("/storage/emulated/0/Download/setup_game.exe"))
        assertEquals("/storage/emulated/0/Download/setup_game.exe", launch.target)
        assertEquals(emptyList<String>(), launch.arguments)
    }

    @Test
    fun `an msi goes through start so the prefix's installer opens it`() {
        val launch = WinePrefixTools.launchOf(File("/storage/emulated/0/Download/Game.MSI"))
        assertEquals("start", launch.target)
        assertEquals(listOf("/unix", "/storage/emulated/0/Download/Game.MSI"), launch.arguments)
    }

    @Test
    fun `only the kinds of file Wine starts are offered as programs`() {
        listOf("a.exe", "A.EXE", "b.msi", "c.bat", "d.cmd").forEach { assertTrue(it, WinePrefixTools.isProgram(File(it))) }
        listOf("readme.txt", "game.bin", "save.dat", "noextension", "setup.exe.bak").forEach { assertFalse(it, WinePrefixTools.isProgram(File(it))) }
    }

    @Test
    fun `the command prompt gets a console window of its own`() {
        val prompt = WinePrefixTools.Program.COMMAND_PROMPT
        assertEquals("wineconsole", prompt.target)
        assertEquals(listOf("cmd"), prompt.arguments)
        assertNull(WinePrefixTools.Program.CONFIGURATION.arguments.firstOrNull())
        assertEquals("winecfg", WinePrefixTools.Program.CONFIGURATION.target)
        assertEquals("regedit", WinePrefixTools.Program.REGISTRY_EDITOR.target)
    }

    @Test
    fun `a tool is not a failure when it closes quickly with code 0`() {
        assertNull(WinePresentation.exitReport(0, 2_000, showedWindow = false, output = "", tool = true))
        val failed = WinePresentation.exitReport(3, 2_000, showedWindow = true, output = "oops", tool = true)!!
        assertEquals("This Windows program closed straight after it started.", failed.title)
    }
}
