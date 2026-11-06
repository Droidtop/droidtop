package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The candidate bar's model (Droidtop/tracker#903 H6): the row, the expand button, the grid's pages and the pad's moves. */
class CandidateBarTest {
    private fun candidates(n: Int, focused: Int = -1, pageSize: Int = 0) =
        InputEngine.Candidates((0 until n).map { InputEngine.Candidate("w$it", if (it % 2 == 0) "note$it" else null) }, focused, pageSize)

    @Test fun `Hacker's words never offer the grid`() {
        val bar = CandidateBar()
        bar.show(InputEngine.Candidates.of(listOf("the", "they", "then")), expandable = false)
        assertFalse(bar.expandable)
        assertFalse(bar.expand())
        assertFalse(bar.expanded)
        assertEquals(listOf("the", "they", "then"), bar.items.map { it.text })
        assertEquals(null, bar.items[0].annotation)
    }

    @Test fun `the grid opens only with candidates, and closes when they go`() {
        val bar = CandidateBar()
        bar.show(InputEngine.Candidates.NONE, expandable = true)
        assertFalse(bar.expandable)
        bar.show(candidates(5), expandable = true)
        assertTrue(bar.expand())
        assertFalse(bar.expand())
        assertTrue(bar.expanded)
        bar.show(candidates(3), expandable = true)
        assertTrue("still open for new candidates", bar.expanded)
        bar.clear()
        assertFalse(bar.expanded)
        assertFalse(bar.toggle())
    }

    @Test fun `the grid pages by what fits, or by the engine's own page`() {
        val bar = CandidateBar()
        bar.show(candidates(23), expandable = true)
        bar.layoutGrid(columns = 4, rows = 2)
        assertEquals(8, bar.pageSize)
        assertEquals(3, bar.pageCount)
        assertEquals(0 until 8, bar.pageRange)
        assertTrue(bar.nextPage())
        assertEquals(8 until 16, bar.pageRange)
        assertTrue(bar.nextPage())
        assertEquals(16 until 23, bar.pageRange)
        assertFalse(bar.nextPage())
        assertTrue(bar.previousPage())
        assertEquals(1, bar.page)

        // An engine that pages by nine (as Mozc does) keeps its pages.
        bar.show(candidates(20, focused = 12, pageSize = 9), expandable = true)
        assertEquals(9, bar.pageSize)
        assertEquals(3, bar.pageCount)
        assertEquals("opens at the engine's choice", 1, bar.page)
        assertEquals(12, bar.focused)
    }

    @Test fun `new candidates open at the first page`() {
        val bar = CandidateBar()
        bar.show(candidates(30), expandable = true)
        bar.layoutGrid(3, 2)
        bar.nextPage()
        bar.nextPage()
        bar.show(candidates(30), expandable = true)
        assertEquals(0, bar.page)
    }

    @Test fun `the pad steps along the row and onto the expand button`() {
        val bar = CandidateBar()
        bar.show(candidates(3), expandable = true)
        assertTrue(bar.padEnter())
        assertEquals(0, bar.selectedCandidate())
        assertTrue(bar.move(CandidateBar.Move.LEFT))
        assertEquals(0, bar.selected)
        bar.move(CandidateBar.Move.RIGHT)
        bar.move(CandidateBar.Move.RIGHT)
        assertEquals(2, bar.selectedCandidate())
        bar.move(CandidateBar.Move.RIGHT)
        assertTrue(bar.onExpandButton)
        assertEquals(-1, bar.selectedCandidate())
        bar.move(CandidateBar.Move.RIGHT)
        assertTrue("held at the end", bar.onExpandButton)
        assertTrue("Up stays in the row", bar.move(CandidateBar.Move.UP))
        assertFalse("Down leaves the row", bar.move(CandidateBar.Move.DOWN))

        // Without the grid, the last word is the end.
        val words = CandidateBar()
        words.show(InputEngine.Candidates.of(listOf("a", "b")), expandable = false)
        words.padEnter()
        words.move(CandidateBar.Move.RIGHT)
        words.move(CandidateBar.Move.RIGHT)
        assertEquals(1, words.selectedCandidate())
        assertFalse(words.onExpandButton)
    }

    @Test fun `the expand button opens the grid with the ring on the first candidate`() {
        val bar = CandidateBar()
        bar.show(candidates(2), expandable = true)
        bar.padEnter()
        bar.move(CandidateBar.Move.RIGHT)
        bar.move(CandidateBar.Move.RIGHT)
        assertTrue(bar.onExpandButton)
        assertTrue(bar.toggle())
        assertTrue(bar.expanded)
        assertEquals(0, bar.selectedCandidate())
    }

    @Test fun `in the grid every direction moves, and Up and Down cross pages`() {
        val bar = CandidateBar()
        bar.show(candidates(14), expandable = true)
        bar.layoutGrid(columns = 3, rows = 2)
        bar.padEnter()
        assertTrue(bar.expand())
        bar.move(CandidateBar.Move.RIGHT)
        bar.move(CandidateBar.Move.DOWN)
        assertEquals(4, bar.selected)
        bar.move(CandidateBar.Move.DOWN)
        assertEquals("onto the next page, same column", 7, bar.selected)
        assertEquals(1, bar.page)
        bar.move(CandidateBar.Move.UP)
        assertEquals("back to the last row of the page before", 4, bar.selected)
        assertEquals(0, bar.page)
        bar.move(CandidateBar.Move.UP)
        bar.move(CandidateBar.Move.UP)
        assertEquals("held at the top of the first page", 1, bar.selected)
        repeat(10) { bar.move(CandidateBar.Move.DOWN) }
        assertEquals("held at the last candidate", 13, bar.selected)
        assertEquals(2, bar.page)
        bar.move(CandidateBar.Move.LEFT)
        assertEquals(12, bar.selected)
        assertTrue(bar.collapse())
        bar.padLeave()
        assertEquals(-1, bar.selectedCandidate())
    }

    @Test fun `turning a page by touch carries the pad's column`() {
        val bar = CandidateBar()
        bar.show(candidates(12), expandable = true)
        bar.layoutGrid(3, 2)
        bar.expand()
        bar.padEnter()
        bar.move(CandidateBar.Move.RIGHT)
        assertTrue(bar.nextPage())
        assertEquals(7, bar.selected)
    }

    @Test fun `the pad has nothing to enter on an empty bar, and new candidates keep it on the engine's choice`() {
        val bar = CandidateBar()
        assertFalse(bar.padEnter())
        bar.show(candidates(4), expandable = false)
        bar.padEnter()
        bar.move(CandidateBar.Move.RIGHT)
        bar.show(candidates(6, focused = 3), expandable = false)
        assertTrue(bar.padActive)
        assertEquals(3, bar.selectedCandidate())
        bar.show(InputEngine.Candidates.NONE, expandable = false)
        assertFalse(bar.padActive)
    }

    @Test fun `a notice stands in for candidates, and Hacker's words carry neither it nor a mode`() {
        val bar = CandidateBar()
        bar.show(InputEngine.Candidates.of(listOf("the")), expandable = false)
        assertNull(bar.notice)
        assertNull(bar.modeLabel)
        bar.show(candidates(3), expandable = true)
        bar.showMode("あ")
        assertTrue(bar.padEnter())
        bar.showNotice("Japanese is getting ready…")
        assertEquals("Japanese is getting ready…", bar.notice)
        assertTrue(bar.items.isEmpty())
        assertFalse(bar.expandable)
        assertFalse(bar.padActive)
        assertNull(bar.modeLabel)
        assertFalse("nothing for the pad to reach", bar.padEnter())
        bar.show(candidates(2), expandable = true)
        assertNull("candidates replace the notice", bar.notice)
    }
}
