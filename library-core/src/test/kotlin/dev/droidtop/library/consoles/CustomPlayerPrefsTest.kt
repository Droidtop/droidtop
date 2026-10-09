package dev.droidtop.library.consoles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A custom player's own checks and its shared form (Droidtop/tracker#248): the check names what is
 * wrong with the text before anything starts, and a shared file is a players-database document that
 * the database's own parser reads back unchanged.
 */
class CustomPlayerPrefsTest {
    private val pkg = "org.example.emu"

    @Test
    fun `a complete command has no problems`() {
        assertEquals(
            emptyList<String>(),
            CustomPlayerPrefs.problems(pkg, "-a android.intent.action.VIEW -n $pkg/.MainActivity -d {file.uri}"),
        )
        assertEquals(emptyList<String>(), CustomPlayerPrefs.problems(pkg, "-p $pkg --es ROM {file.path}"))
    }

    @Test
    fun `a missing package or command is the only problem named`() {
        assertEquals(1, CustomPlayerPrefs.problems(" ", "-n a/.B -d {file.uri}").size)
        assertEquals(1, CustomPlayerPrefs.problems(pkg, "  ").size)
    }

    @Test
    fun `a command that never passes the game is named`() {
        val faults = CustomPlayerPrefs.problems(pkg, "-n $pkg/.MainActivity")
        assertEquals(1, faults.size)
        assertTrue(faults.single().contains("{file.path}"))
    }

    @Test
    fun `a command for another app is named`() {
        val faults = CustomPlayerPrefs.problems(pkg, "-n org.other.app/.Main -d {file.uri}")
        assertEquals(1, faults.size)
        assertTrue(faults.single().contains("org.other.app"))
    }

    @Test
    fun `a command that names no app, or a component without its activity, is named`() {
        assertTrue(CustomPlayerPrefs.problems(pkg, "-a android.intent.action.VIEW -d {file.uri}").single().contains("-n $pkg"))
        assertTrue(CustomPlayerPrefs.problems(pkg, "-n $pkg -d {file.uri}").single().contains("activity"))
    }

    @Test
    fun `an unterminated quote is named rather than thrown`() {
        assertTrue(CustomPlayerPrefs.problems(pkg, "-n $pkg/.Main --es ROM \"{file.path}").single().startsWith("The arguments cannot be read"))
    }

    @Test
    fun `a shared player is a players-database document the database parser reads back`() {
        val player = Player.AmStart(
            id = "custom-1",
            name = "My Emu",
            argumentsTemplate = "-n $pkg/.Main -d {file.uri}",
            killPackageProcesses = true,
            packageName = pkg,
            storagePathTemplate = "-n $pkg/.Main --es path {file.path}",
        )
        val row = KnownPlayers.parse(CustomPlayerPrefs.shareJson("psx", listOf(player))).single()
        assertEquals("psx", row.systemId)
        assertEquals("My Emu", row.label)
        assertEquals(pkg, row.pkg)
        assertEquals(player.copy(id = row.id), row.player)
    }
}
