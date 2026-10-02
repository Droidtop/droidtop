package dev.droidtop.shell.gamepad.pc

import dev.droidtop.shell.gamepad.query.LibraryFacet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A store page's "Open library" lands on that store's filter and nothing else (docs/SPEC.md 7j "Places"). */
class PcGamesStateTest {

    @Test
    fun `showing a store selects only that store and leaves the shelves`() {
        val state = PcGamesState()
        state.showStore("GOG")
        assertEquals(setOf("GOG"), state.query.selected(LibraryFacet.STORE))
        assertEquals(1, state.query.facets.size)
        assertFalse(state.home)
        assertTrue(state.queryLoaded)
        assertEquals(0, state.itemIndex)
    }

    @Test
    fun `opening the grid from Home resets the cursor and opening Home leaves the strip`() {
        val state = PcGamesState()
        state.itemIndex = 5
        state.open(home = false)
        assertFalse(state.home)
        assertEquals(0, state.itemIndex)
        state.stripFocused = true
        state.itemIndex = 3
        state.open(home = true)
        assertTrue(state.home)
        assertFalse(state.stripFocused)
        assertEquals(0, state.itemIndex)
    }

    @Test
    fun `opening the view it already shows changes nothing`() {
        val state = PcGamesState()
        state.itemIndex = 4
        state.open(home = true)
        assertEquals(4, state.itemIndex)
    }

    @Test
    fun `showing another store replaces the first rather than adding to it`() {
        val state = PcGamesState()
        state.showStore("GOG")
        state.showStore("Epic")
        assertEquals(setOf("Epic"), state.query.selected(LibraryFacet.STORE))
    }
}
