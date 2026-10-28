package org.pocketworkstation.pckeyboard

import kotlin.math.abs

/**
 * Where a pad's focus ring goes on the input method's keys (Droidtop/tracker#338; docs/SPEC.md 6a "A pad on the
 * keyboard"). Pure: keys are boxes, the focus is a point, so it survives a layer switch (Shift, symbols) by landing on
 * whatever key is nearest it on the new layer.
 *
 * Rows are the keys that share a top edge. Left and Right go along the row and wrap; Up and Down go to the next row
 * up or down, to the key whose centre is nearest across, and stop at the top and bottom rows (the keyboard is its own
 * surface; the pad never leaves it for the app).
 */
object KeyFocus {
    data class Box(val x: Int, val y: Int, val width: Int, val height: Int) {
        val cx: Float get() = x + width / 2f
        val cy: Float get() = y + height / 2f
    }

    /** The key nearest [px], [py]; the first key when there are none closer. -1 for no keys. */
    fun nearest(boxes: List<Box>, px: Float, py: Float): Int =
        boxes.indices.minByOrNull { val b = boxes[it]; (b.cx - px) * (b.cx - px) + (b.cy - py) * (b.cy - py) } ?: -1

    /** Where the ring starts: the key nearest the keyboard's middle. */
    fun start(boxes: List<Box>): Int {
        if (boxes.isEmpty()) return -1
        val right = boxes.maxOf { it.x + it.width }
        val bottom = boxes.maxOf { it.y + it.height }
        return nearest(boxes, right / 2f, bottom / 2f)
    }

    /** The rows, top to bottom, each left to right, as indices into [boxes]. */
    fun rows(boxes: List<Box>): List<List<Int>> =
        boxes.indices.groupBy { boxes[it].y }.toSortedMap().values.map { row -> row.sortedBy { boxes[it].x } }

    /** The key a move from [from] lands on. */
    fun next(boxes: List<Box>, from: Int, move: Slab.Move): Int {
        if (from !in boxes.indices) return start(boxes)
        val rows = rows(boxes)
        val r = rows.indexOfFirst { from in it }
        val row = rows[r]
        val c = row.indexOf(from)
        return when (move) {
            Slab.Move.LEFT -> row[(c - 1 + row.size) % row.size]
            Slab.Move.RIGHT -> row[(c + 1) % row.size]
            Slab.Move.UP -> if (r == 0) from else closestAcross(boxes, rows[r - 1], boxes[from].cx)
            Slab.Move.DOWN -> if (r == rows.lastIndex) from else closestAcross(boxes, rows[r + 1], boxes[from].cx)
        }
    }

    private fun closestAcross(boxes: List<Box>, row: List<Int>, cx: Float): Int = row.minByOrNull { abs(boxes[it].cx - cx) } ?: row.first()
}
