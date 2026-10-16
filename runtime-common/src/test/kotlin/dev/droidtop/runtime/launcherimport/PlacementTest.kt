package dev.droidtop.runtime.launcherimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Putting a backup's cells on droidtop's whole-cell grid (Placement.kt). */
class PlacementTest {
    private fun raw(x: Double, y: Double, page: Int = 0, w: Double = 1.0, h: Double = 1.0, where: PlanContainer = PlanContainer.HOME, label: String = "$x,$y") =
        Placement.Raw(where, page, x, y, w, h, label) { p, cx, cy, sx, sy -> PlanItem.Widget(where, p, cx, cy, sx, sy, label) }

    private fun cells(result: Placement.Result) = result.items.map { it as PlanItem.Widget }.map { listOf(it.page, it.x, it.y, it.spanX, it.spanY) }

    @Test fun `whole cells stay where they were`() {
        val result = Placement.place(listOf(raw(0.0, 0.0), raw(3.0, 4.0), raw(1.0, 1.0, w = 2.0, h = 2.0)), 4 to 5, 5)
        assertEquals(4 to 5, result.grid)
        assertEquals(setOf(listOf(0, 0, 0, 1, 1), listOf(0, 3, 4, 1, 1), listOf(0, 1, 1, 2, 2)), cells(result).toSet())
        assertTrue(result.notes.isEmpty())
    }

    @Test fun `half cells double the grid when it fits`() {
        val result = Placement.place(listOf(raw(0.5, 1.5), raw(2.0, 0.0, w = 1.5)), 4 to 5, 5)
        assertEquals(8 to 10, result.grid)
        // A whole cell is two cells of the doubled grid.
        assertEquals(setOf(listOf(0, 1, 3, 2, 2), listOf(0, 4, 0, 3, 2)), cells(result).toSet())
        assertTrue(result.notes.single().imported)
    }

    @Test fun `half cells on a grid too big to double round down and collide into free cells`() {
        // 6x6 cannot double past 10: 0.5 and 0.0 both land on column 0, so the later one moves.
        val result = Placement.place(listOf(raw(0.0, 0.0, label = "a"), raw(0.5, 0.0, label = "b")), 6 to 6, 5)
        assertEquals(6 to 6, result.grid)
        assertEquals(listOf(listOf(0, 0, 0, 1, 1), listOf(0, 1, 0, 1, 1)), cells(result))
        assertEquals("b", result.notes.single().what)
    }

    @Test fun `a full page spills onto a new page, and pages are numbered without gaps`() {
        val full = (0 until 3).flatMap { y -> (0 until 3).map { x -> raw(x.toDouble(), y.toDouble(), page = 4) } }
        val result = Placement.place(full + raw(0.0, 0.0, page = 4, label = "extra") + raw(1.0, 1.0, page = 9), 3 to 3, 5)
        val pages = cells(result).map { it[0] }
        assertEquals(9, pages.count { it == 0 })
        assertEquals(1, pages.count { it == 1 })
        // "extra" went past both of the backup's pages.
        assertEquals(1, pages.count { it == 2 })
    }

    @Test fun `an item larger than the grid is reported, not placed`() {
        val result = Placement.place(listOf(raw(0.0, 0.0, w = 5.0, h = 1.0, label = "wide")), 4 to 5, 5)
        assertTrue(result.items.isEmpty())
        assertEquals("wide", result.notes.single().what)
    }

    @Test fun `the dock is one row, and its overflow and a switched-off dock are reported`() {
        val dock = (0 until 4).map { raw(it.toDouble(), 0.0, where = PlanContainer.DOCK, label = "d$it") }
        val three = Placement.place(dock, 4 to 5, 3)
        assertEquals(listOf(0, 1, 2), three.items.map { it.x })
        assertEquals("d3", three.notes.single().what)
        val off = Placement.place(dock, 4 to 5, 0)
        assertTrue(off.items.isEmpty())
        assertEquals(4, off.notes.size)
    }
}
