package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A game on one of the person's computers is filed under that computer (docs/SPEC.md 7o, "Library"). */
class ComputerSourceTest {
    private val pc = "a".repeat(64)

    @Test
    fun `a computer's game has the computer as its source, whatever store it came from`() {
        PcSource.computerNames[pc] = "DESKTOP-PC"
        val entry = LibraryEntry(
            id = "computer:$pc:steam:440",
            title = "Team Fortress 2",
            kind = LibraryEntryKind.COMPUTER_GAME,
            pcInfo = PcInfo(storeId = "steam:440", installed = false),
        )
        val source = PcSource.of(entry)
        assertEquals(PcSource.Computer(pc), source)
        assertEquals("On DESKTOP-PC", source?.label())
        assertEquals(source, PcSource.fromId(source!!.id))
        assertTrue(LibraryEntryKind.COMPUTER_GAME in LibraryKinds.GAMES)
    }

    @Test
    fun `a computer key is not a store`() {
        assertNull(PcSource.storeIdOf("computer:$pc:steam:440"))
    }
}
