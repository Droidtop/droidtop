package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.PcInfo
import dev.droidtop.library.PcSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A game only on one of the person's computers: its page streams it (docs/SPEC.md 7o). */
class PcComputerGameStateTest {
    private val steamOnPc = LibraryEntry(
        id = "computer:abc123:steam:400",
        title = "Portal",
        kind = LibraryEntryKind.COMPUTER_GAME,
        pcInfo = PcInfo(storeId = "steam:400", installed = false),
    )

    @Test
    fun `its button streams from the computer, not a runner picker`() {
        PcSource.computerNames["abc123"] = "DESKTOP-PC"
        val state = playStateOf(null, steamOnPc)
        assertEquals("Stream", state.verb)
        assertEquals("Starts it on DESKTOP-PC and shows it here", state.detail)
        assertTrue(state.pressable && state.ready)
        assertFalse(state.chooseEngine)
    }

    @Test
    fun `a store id does not make it this device's store row`() {
        assertFalse(steamOnPc.isStoreRow())
        assertNull(storeStageOf(steamOnPc, null))
    }
}
