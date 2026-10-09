package dev.droidtop.library.consoles

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.SwitchGameFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchContentHandoffTest {
    private fun entry(facts: SwitchGameFacts?) =
        LibraryEntry(id = "/roms/switch/Game.nsp", title = "Game", kind = LibraryEntryKind.CONSOLE_ROM, switchFacts = facts)

    @Test
    fun `a game with an update and DLC lists the update first then the DLC`() {
        val facts = SwitchGameFacts(
            hasUpdate = true,
            dlcCount = 1,
            updatePaths = listOf("/roms/switch/Game [v65536].nsp"),
            dlcPaths = listOf("/roms/switch/Game DLC.nsp"),
        )
        val files = SwitchContentHandoff.filesToPick(entry(facts))
        assertEquals(listOf("Game [v65536].nsp", "Game DLC.nsp"), files.map { it.name })
        assertTrue(SwitchContentHandoff.applies(entry(facts)))
    }

    @Test
    fun `a plain game and a loose part have nothing to hand over`() {
        assertFalse(SwitchContentHandoff.applies(entry(null)))
        assertFalse(SwitchContentHandoff.applies(entry(SwitchGameFacts(baseTitleId = "01007ef00011e000"))))
        assertFalse(
            SwitchContentHandoff.applies(
                entry(SwitchGameFacts(loose = true, dlcCount = 1, dlcPaths = listOf("/roms/switch/Orphan DLC.nsp"))),
            ),
        )
    }

    @Test
    fun `the instruction names the emulator and caps the files it lists`() {
        assertEquals(
            "Opened Lemon. In its install-content picker, choose: a.nsp, b.nsp",
            SwitchContentHandoff.instruction("Lemon", listOf("a.nsp", "b.nsp")),
        )
        assertEquals(
            "Opened Lemon. In its install-content picker, choose: a, b, c, d and 2 more",
            SwitchContentHandoff.instruction("Lemon", listOf("a", "b", "c", "d", "e", "f")),
        )
    }
}
