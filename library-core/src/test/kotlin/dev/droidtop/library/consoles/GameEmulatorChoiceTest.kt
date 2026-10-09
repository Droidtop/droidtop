package dev.droidtop.library.consoles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-game emulator choice as one control model: the Quick Menu cycles it with A, the companion's Game tab and
 * the metadata editor lay the same options out (Droidtop/tracker#414, #248). Stand-in players; only the model is
 * tested.
 */
class GameEmulatorChoiceTest {
    private fun player(id: String, name: String, pkg: String) =
        Player.AmStart(id = id, name = name, argumentsTemplate = "-n $pkg/.Main -d {file.uri}", packageName = pkg)

    private val standalone = player("sys-standalone", "Standalone Emu", "org.example.standalone")
    private val other = player("sys-other", "Other Emu", "org.example.other")
    private val system = ConsoleSystemDef(id = "gba", displayName = "Game Boy Advance", extensions = setOf("gba"), retroArchCore = null)
    private val emulators = SystemEmulators(system, listOf(standalone, other), ResolvedEmulator(standalone, EmulatorSource.AUTOMATIC))

    @Test fun `Follow the system comes first and is current without a choice`() {
        val options = GameEmulatorChoice.options(emulators, null)
        assertEquals(listOf(null, "sys-standalone", "sys-other"), options.map { it.id })
        assertEquals("Follow the system", options.first().label)
        assertTrue(options.first().current)
        assertEquals("Standalone Emu, the first installed emulator that can run it", options.first().detail)
        assertEquals("org.example.other", options.last().detail)
    }

    @Test fun `the game's own choice is current, matched by id or by name`() {
        assertEquals("sys-other", GameEmulatorChoice.options(emulators, "sys-other").single { it.current }.id)
        assertEquals("sys-other", GameEmulatorChoice.current(emulators, "Other Emu"))
        // A choice naming something no longer installed follows the system.
        assertNull(GameEmulatorChoice.current(emulators, "Gone Emu"))
    }

    @Test fun `next cycles in option order and wraps to Follow the system`() {
        assertEquals("sys-standalone", GameEmulatorChoice.next(emulators, null))
        assertEquals("sys-other", GameEmulatorChoice.next(emulators, "sys-standalone"))
        assertNull(GameEmulatorChoice.next(emulators, "sys-other"))
        assertEquals("sys-standalone", GameEmulatorChoice.next(emulators, "Gone Emu"))
    }

    @Test fun `the summary names what runs and where that was decided`() {
        assertEquals("Loading...", GameEmulatorChoice.summary(null, null))
        assertEquals("Other Emu (set for this game)", GameEmulatorChoice.summary(emulators, "sys-other"))
        assertEquals("Standalone Emu (the first installed emulator that can run it)", GameEmulatorChoice.summary(emulators, null))
    }

    @Test fun `offered only with an emulator to choose`() {
        assertTrue(GameEmulatorChoice.offered(emulators))
        assertFalse(GameEmulatorChoice.offered(null))
        assertFalse(GameEmulatorChoice.offered(SystemEmulators(system, emptyList(), null)))
    }
}
