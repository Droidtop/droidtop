package dev.droidtop.runtime.windows

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What an installer put in its prefix, as the folders offered to add to the library. */
class WindowsInstallsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun driveC(vararg folders: String): File {
        val c = tmp.newFolder("drive_c")
        folders.forEach { File(c, it).mkdirs() }
        return c
    }

    @Test
    fun `the folders inside the places installers use are offered, the system's own are not`() {
        val c = driveC(
            "windows/system32", "users/xuser", "ProgramData",
            "Program Files/Common Files", "Program Files/Internet Explorer", "Program Files/Monster Prom",
            "Program Files (x86)/60 Parsecs", "GOG Games/Some Game", "Games/Other",
        )
        val names = WindowsInstalls.findCandidates(c, null, emptySet()).map { it.name }.toSet()
        assertEquals(setOf("Monster Prom", "60 Parsecs", "Some Game", "Other"), names)
    }

    @Test
    fun `another folder at the top of C is offered, and what was there before is not`() {
        val c = driveC("Program Files/Old Thing", "MyGame")
        val before = setOf(File(c, "Program Files/Old Thing").absolutePath)
        assertEquals(listOf("MyGame"), WindowsInstalls.findCandidates(c, null, before).map { it.name })
    }

    @Test
    fun `the folder chosen to install into is offered with what is inside it, itself last`() {
        val c = driveC("Program Files")
        val target = tmp.newFolder("MyGames")
        File(target, "Installed Game").mkdirs()
        val found = WindowsInstalls.findCandidates(c, target, emptySet())
        assertEquals(listOf("Installed Game", "MyGames"), found.map { it.name })
    }

    @Test
    fun `a prefix with nothing installed offers nothing`() {
        assertEquals(emptyList<File>(), WindowsInstalls.findCandidates(driveC("windows", "users", "Program Files/Common Files"), null, emptySet()))
    }

    @Test
    fun `a game folder takes the id of its prefix`() {
        assertEquals("CUSTOM_GAME_1234567", WindowsInstalls.containerIdOf(1234567))
    }
}
