package dev.droidtop.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Whether a Ren'Py folder offers the Linux container: only when it has the launcher the launch would run. */
class RenPyLinuxBuildTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(path: String) {
        val f = File(tmp.root, path)
        f.parentFile?.mkdirs()
        f.writeText("x")
    }

    @Test
    fun `a Windows download with only a Steam library in lib linux has no Linux build`() {
        // Projekt: Passion's _win copy on the console: an .exe, and lib/py2-linux-x86_64 holding libsteam_api.so alone.
        file("ProjektPassion-0.14-pc/ProjektPassion-32.exe")
        file("ProjektPassion-0.14-pc/game/script.rpyc")
        file("ProjektPassion-0.14-pc/lib/py2-linux-x86_64/libsteam_api.so")
        file("ProjektPassion-0.14-pc/lib/py2-windows-x86_64/ProjektPassion.exe")
        val facts = GameLaunchStrategyResolver.facts(GameEngine.RENPY, File(tmp.root, "ProjektPassion-0.14-pc"))
        assertFalse(facts.hasLinuxBuild)
    }

    @Test
    fun `a PC download with the game's own sh launcher is a Linux build`() {
        // The Steam copy's layout: ProjektPassion.sh beside the .py, and the game binary under lib/py2-linux-x86_64.
        file("Projekt Passion/ProjektPassion.sh")
        file("Projekt Passion/ProjektPassion.py")
        file("Projekt Passion/ProjektPassion.exe")
        file("Projekt Passion/lib/py2-linux-x86_64/ProjektPassion")
        val facts = GameLaunchStrategyResolver.facts(GameEngine.RENPY, File(tmp.root, "Projekt Passion"))
        assertTrue(facts.hasLinuxBuild)
    }
}
