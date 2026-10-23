package dev.droidtop.shell.gamepad.pc

import dev.droidtop.shell.gamepad.query.LibraryFacet
import dev.droidtop.shell.gamepad.query.LibraryQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shelves surface's views (docs/SPEC.md 7i): Home (a section of its own), PC Games'
 * Overview and its grid views, and a store page's "Open library", which lands
 * on that store's filter and nothing else (docs/SPEC.md 7j "Places").
 */
class PcGamesStateTest {

    @Test
    fun `showing a store selects only that store and opens the grid`() {
        val state = PcGamesState()
        state.showSource("gog")
        assertEquals(setOf("gog"), state.query.selected(LibraryFacet.SOURCE))
        assertEquals(1, state.query.facets.size)
        assertEquals(PcView.GRID, state.view)
        assertFalse(state.home)
        assertTrue(state.queryLoaded)
        assertEquals(0, state.itemIndex)
    }

    @Test
    fun `Home and PC Games keep a place each`() {
        // Home is a section of its own with a state of its own (Droidtop/tracker#611): PC Games' grid view and
        // cursor no longer go when Home is shown, and the other way round.
        val home = PcGamesState()
        val pc = PcGamesState().apply { view = PcView.OVERVIEW }
        pc.showSource("gog")
        pc.itemIndex = 7
        home.itemIndex = 2
        assertTrue(home.home)
        assertEquals(PcView.GRID, pc.view)
        assertEquals(7, pc.itemIndex)
        assertEquals(2, home.itemIndex)
    }

    @Test
    fun `a grid view comes back to Overview, which is a shelf surface`() {
        val state = PcGamesState().apply { view = PcView.OVERVIEW }
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
        state.showSource("gog")
        state.showSource("epic")
        assertEquals(setOf("epic"), state.query.selected(LibraryFacet.SOURCE))
    }
}
