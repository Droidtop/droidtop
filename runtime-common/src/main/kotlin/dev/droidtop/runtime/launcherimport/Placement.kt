package dev.droidtop.runtime.launcherimport

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Puts a backup's items on droidtop's grid (Droidtop/tracker#564). droidtop's cells are whole numbers on a grid of
 * [MIN_GRID] to [MAX_GRID] a side; Nova's subgrid allows half cells and overlapping items. If any home position or size
 * is fractional, the grid is doubled when that still fits, so every position is whole; otherwise positions are rounded
 * down. Then each item that would overlap one already placed, or not fit, moves to the first free cells on its page,
 * else onto a new page. Each move is reported. Pure.
 */
object Placement {
    const val MIN_GRID = 3
    const val MAX_GRID = 10

    /** An item as the backup has it: cells may be fractional. [make] builds the plan item at its final cells. */
    class Raw(
        val container: PlanContainer,
        val page: Int,
        val x: Double,
        val y: Double,
        val spanX: Double,
        val spanY: Double,
        val label: String,
        val make: (page: Int, x: Int, y: Int, spanX: Int, spanY: Int) -> PlanItem,
    )

    class Result(val grid: Pair<Int, Int>, val items: List<PlanItem>, val notes: List<ImportNote>)

    fun place(raw: List<Raw>, grid: Pair<Int, Int>, dockIcons: Int): Result {
        val notes = mutableListOf<ImportNote>()
        val home = raw.filter { it.container == PlanContainer.HOME }
        val fractional = home.any { it.x.isFractional() || it.y.isFractional() || it.spanX.isFractional() || it.spanY.isFractional() }
        val doubled = fractional && grid.first * 2 <= MAX_GRID && grid.second * 2 <= MAX_GRID
        val scale = if (doubled) 2.0 else 1.0
        val cols = (grid.first * scale).toInt().coerceIn(MIN_GRID, MAX_GRID)
        val rows = (grid.second * scale).toInt().coerceIn(MIN_GRID, MAX_GRID)
        if (doubled) {
            notes += ImportNote("Grid", "Your home screen used half cells, so the grid is ${cols}x$rows, twice ${grid.first}x${grid.second}", imported = true)
        }
        val out = mutableListOf<PlanItem>()
        // Home pages, in the backup's order, numbered from 0 with no empty page between.
        val pages = home.map { it.page }.distinct().sorted()
        val occupied = mutableMapOf<Int, Array<BooleanArray>>()
        fun grid(page: Int) = occupied.getOrPut(page) { Array(rows) { BooleanArray(cols) } }
        var extraPage = pages.size
        for (item in home.sortedWith(compareBy({ it.page }, { it.y }, { it.x }))) {
            val page = pages.indexOf(item.page)
            val spanX = ceil(item.spanX * scale).toInt().coerceAtLeast(1)
            val spanY = ceil(item.spanY * scale).toInt().coerceAtLeast(1)
            if (spanX > cols || spanY > rows) {
                notes += ImportNote(item.label, "It is ${spanX}x$spanY cells, larger than the ${cols}x$rows grid")
                continue
            }
            var x = floor(item.x * scale).toInt()
            var y = floor(item.y * scale).toInt()
            var target = page
            if (!fits(grid(target), x, y, spanX, spanY)) {
                val spot = firstFree(grid(target), spanX, spanY)
                if (spot != null) {
                    x = spot.first
                    y = spot.second
                } else {
                    target = extraPage
                    while (firstFree(grid(target), spanX, spanY) == null) target++
                    val free = firstFree(grid(target), spanX, spanY)!!
                    x = free.first
                    y = free.second
                    extraPage = maxOf(extraPage, target)
                }
                notes += ImportNote(item.label, "Moved: its place was taken or off the grid (it overlapped another item or sat on half cells)", imported = true)
            }
            mark(grid(target), x, y, spanX, spanY)
            out += item.make(target, x, y, spanX, spanY)
        }
        // The dock is one row of [dockIcons]; droidtop has one dock page.
        val taken = BooleanArray(maxOf(dockIcons, 0))
        for (item in raw.filter { it.container == PlanContainer.DOCK }.sortedBy { it.x }) {
            if (dockIcons <= 0) {
                notes += ImportNote(item.label, "The dock was off in your launcher, so nothing goes in droidtop's dock")
                continue
            }
            var x = floor(item.x).toInt()
            if (x !in taken.indices || taken[x]) x = taken.indexOfFirst { !it }
            if (x < 0) {
                notes += ImportNote(item.label, "droidtop's dock has $dockIcons places and they were full")
                continue
            }
            taken[x] = true
            out += item.make(0, x, 0, 1, 1)
        }
        return Result(cols to rows, out, notes)
    }

    private fun Double.isFractional() = this != floor(this)

    private fun fits(cells: Array<BooleanArray>, x: Int, y: Int, w: Int, h: Int): Boolean {
        if (x < 0 || y < 0 || y + h > cells.size || x + w > cells[0].size) return false
        for (r in y until y + h) for (c in x until x + w) if (cells[r][c]) return false
        return true
    }

    private fun firstFree(cells: Array<BooleanArray>, w: Int, h: Int): Pair<Int, Int>? {
        for (y in cells.indices) for (x in cells[0].indices) if (fits(cells, x, y, w, h)) return x to y
        return null
    }

    private fun mark(cells: Array<BooleanArray>, x: Int, y: Int, w: Int, h: Int) {
        for (r in y until y + h) for (c in x until x + w) cells[r][c] = true
    }
}
