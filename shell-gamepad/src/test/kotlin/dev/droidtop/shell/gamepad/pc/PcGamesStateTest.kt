package dev.droidtop.shell.gamepad.pc

import dev.droidtop.shell.gamepad.query.LibraryFacet
import dev.droidtop.shell.gamepad.query.LibraryQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PC_GAMES section's three surfaces (docs/SPEC.md 7i): Home, PC Games'
 * Overview and its grid views, and a store page's "Open library", which lands
 * on that store's filter and nothing else (docs/SPEC.md 7j "Places").
 */
class PcGamesStateTest {

    @Test
    fun `showing a store selects only that store and opens the grid`() {
        val state = PcGamesState()
        state.showStore("GOG")
        assertEquals(setOf("GOG"), state.query.selected(LibraryFacet.STORE))
        assertEquals(1, state.query.facets.size)
        assertEquals(PcView.GRID, state.view)
        assertFalse(state.home)
        assertTrue(state.queryLoaded)
        assertEquals(0, state.itemIndex)
    }

    @Test
    fun `PC Games opens on its Overview shelves and Home leaves the strip`() {
        val state = PcGamesState()
        state.itemIndex = 5
        state.shelfIndex = 2
        state.open(home = false)
        assertEquals(PcView.OVERVIEW, state.view)
        assertTrue(state.onShelves)
        assertEquals(0, state.itemIndex)
        assertEquals(0, state.shelfIndex)
        state.stripFocused = true
        state.itemIndex = 3
        state.open(home = true)
        assertTrue(state.home)
        assertFalse(state.stripFocused)
        assertEquals(0, state.itemIndex)
    }

    @Test
    fun `choosing PC Games while one of its grid views shows keeps that view`() {
        val state = PcGamesState()
        state.showStore("GOG")
        state.itemIndex = 7
        state.open(home = false)
        assertEquals(PcView.GRID, state.view)
        assertEquals(7, state.itemIndex)
    }

    @Test
    fun `opening the view it already shows changes nothing`() {
        val state = PcGamesState()
        state.itemIndex = 4
        state.open(home = true)
        assertEquals(4, state.itemIndex)
    }

    @Test
    fun `a grid view comes back to Overview, which is a shelf surface`() {
        val state = PcGamesState()
        state.open(home = false)
        state.showGrid(LibraryQuery())
        assertEquals(PcView.GRID, state.view)
        assertFalse(state.onShelves)
        state.itemIndex = 9
        state.showOverview()
        assertEquals(PcView.OVERVIEW, state.view)
        assertEquals(0, state.itemIndex)
    }

    @Test
    fun `showing another store replaces the first rather than adding to it`() {
        val state = PcGamesState()
        state.showStore("GOG")
        state.showStore("Epic")
        assertEquals(setOf("Epic"), state.query.selected(LibraryFacet.STORE))
    }
}
