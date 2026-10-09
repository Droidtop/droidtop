package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.PcInfo
import dev.droidtop.library.stores.StoreHolding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A game the account no longer holds is still a game on the device
 * (docs/SPEC.md 7g, "Stores", Droidtop/tracker#397 slice B): ownership never
 * turns an installed copy's Play into a store action.
 */
class PcPlayStateTest {

    private fun row(holding: StoreHolding, installed: Boolean) = LibraryEntry(
        id = "steam:42",
        title = "Left the library",
        kind = LibraryEntryKind.WINE_PROFILE,
        pcInfo = PcInfo(storeId = "steam:42", installed = installed, holding = holding),
    )

    @Test
    fun `an installed game no longer in the library has no store stage, so A plays it`() {
        assertNull(storeStageOf(row(StoreHolding.NOT_OWNED, installed = true), null))
        assertNull(storeStageOf(row(StoreHolding.FAMILY, installed = true), null))
    }

    @Test
    fun `a holding changes nothing about installing a game that is not on the device`() {
        StoreHolding.entries.forEach { holding ->
            assertEquals(holding.name, StoreStage.INSTALL, storeStageOf(row(holding, installed = false), null))
        }
    }
}
