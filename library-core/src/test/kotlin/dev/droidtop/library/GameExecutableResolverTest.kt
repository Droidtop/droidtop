package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The three real shapes a native Linux build takes on disk, and the order
 * they have to win in. Modelled as whole folders rather than single
 * files, because the interesting cases are the ones where more than one
 * shape is present at once.
 */
class GameExecutableResolverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(name: String): File = File(tmp.root, name).also { it.createNewFile() }

    @Test
    fun `a bare ELF launcher is found by its dot-x86_64 extension`() {
        touch("Game.x86_64")
        touch("Game.pck")
        assertEquals("Game.x86_64", GameExecutableResolver.linuxExecutable(tmp.root)?.name)
    }

    @Test
    fun `a 32-bit dot-x86 launcher is found the same way`() {
        touch("Game.x86")
        assertEquals("Game.x86", GameExecutableResolver.linuxExecutable(tmp.root)?.name)
    }

    @Test
    fun `an ELF launcher with no execute bit is still found`() {
        // Games live on removable storage: exFAT and FAT32 carry no
        // execute bit at all, so requiring one would reject every real
        // SD-card install.
        val game = touch("Game.x86_64")
        game.setExecutable(false)
        assertEquals("Game.x86_64", GameExecutableResolver.linuxExecutable(tmp.root)?.name)
    }

    @Test
    fun `a shell wrapper beside an ELF launcher wins`() {
        // Ren'Py's real Linux shape. The `.sh` sets up the environment
        // and then execs the binary next to it, so running the binary
        // directly is not equivalent.
        touch("Game.sh")
        touch("Game.x86_64")
        assertEquals("Game.sh", GameExecutableResolver.linuxExecutable(tmp.root)?.name)
    }

    @Test
    fun `an extensionless executable remains the last resort`() {
        touch("Game").setExecutable(true)
        assertEquals("Game", GameExecutableResolver.linuxExecutable(tmp.root)?.name)
    }

    @Test
    fun `an ELF launcher wins over an extensionless data file`() {
        // The extensionless branch is the weakest evidence of the three
        // (an extensionless file is as likely to be data as a program),
        // so a named ELF launcher must outrank it.
        touch("Game.x86_64")
        touch("README").setExecutable(true)
        assertEquals("Game.x86_64", GameExecutableResolver.linuxExecutable(tmp.root)?.name)
    }

    @Test
    fun `several ELF launchers are disambiguated by the folder name, not guessed`() {
        val folder = File(tmp.root, "Eternum-0.9.5-pc").also { it.mkdirs() }
        File(folder, "Eternum.x86_64").createNewFile()
        File(folder, "Tools.x86_64").createNewFile()
        assertEquals("Eternum.x86_64", GameExecutableResolver.linuxExecutable(folder)?.name)
    }

    @Test
    fun `several unrelated ELF launchers resolve to nothing rather than the wrong one`() {
        touch("Alpha.x86_64")
        touch("Beta.x86_64")
        assertNull(GameExecutableResolver.linuxExecutable(tmp.root))
    }

    @Test
    fun `a Windows-only folder has no Linux executable`() {
        touch("Game.exe")
        assertNull(GameExecutableResolver.linuxExecutable(tmp.root))
        assertEquals("Game.exe", GameExecutableResolver.windowsExecutable(tmp.root)?.name)
    }
}
