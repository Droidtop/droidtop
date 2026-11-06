package org.pocketworkstation.pckeyboard

import kotlin.math.max
import kotlin.math.min

/**
 * What the one candidate bar ([CandidateView]) shows, for any engine (Droidtop/tracker#903 H6; docs/SPEC.md 6a, "The
 * candidate bar"): the candidates with their annotations, whether the row can open into a full grid and whether it is
 * open, the grid's pages, and the pad's selection. Pure, so paging, expanding and selection are tested on the JVM; the
 * view draws it and turns touches and pad moves into its calls.
 *
 * In the row, every candidate is in one scrolling line, and the expand button (when the engine allows the grid) is
 * one more stop after the last. In the grid, candidates are laid out [columns] across and [rows] down a page; a page
 * is the engine's own page size when it pages, else as many as fit.
 */
class CandidateBar {
    enum class Move { LEFT, RIGHT, UP, DOWN }

    var items: List<InputEngine.Candidate> = emptyList()
        private set

    /** The candidate the engine has chosen so far, -1 for none. */
    var focused = -1
        private set

    /** Whether the row offers the expand button: the engine allows it and there is something to show. */
    var expandable = false
        private set

    var expanded = false
        private set

    var columns = 1
        private set
    var rows = 1
        private set

    /** The grid's page shown, from 0. */
    var page = 0
        private set

    /** Whether the pad's ring is on the bar. */
    var padActive = false
        private set

    /** The pad's selection: a candidate's index, [expandStop] for the expand button, or -1 when the pad is elsewhere. */
    var selected = -1
        private set

    private var enginePageSize = 0

    /** The expand button's place among the row's stops: after the last candidate. */
    val expandStop: Int get() = items.size

    val onExpandButton: Boolean get() = padActive && expandable && selected == expandStop

    val pageSize: Int get() = if (enginePageSize > 0) enginePageSize else max(1, columns * rows)

    val pageCount: Int get() = if (items.isEmpty()) 0 else (items.size + pageSize - 1) / pageSize

    /** The candidates on the grid's page shown. */
    val pageRange: IntRange get() = if (items.isEmpty()) IntRange.EMPTY else page * pageSize until min(items.size, (page + 1) * pageSize)

    /** New candidates. The grid stays open only while it can; the pad stays on the bar, at the engine's choice or the first. */
    fun show(candidates: InputEngine.Candidates, expandable: Boolean) {
        items = candidates.items
        focused = if (candidates.focused in items.indices) candidates.focused else -1
        enginePageSize = max(0, candidates.pageSize)
        this.expandable = expandable && items.isNotEmpty()
        if (!this.expandable) expanded = false
        if (items.isEmpty()) {
            padActive = false
            selected = -1
        } else if (padActive) {
            selected = max(focused, 0)
        }
        // New candidates open at the first page, or at the one the engine or the pad is on.
        page = if (padActive || focused >= 0) pageOf(anchor()) else 0
    }

    fun clear() = show(InputEngine.Candidates.NONE, false)

    /** The grid's shape, worked out by the view from its size; the page follows what is selected or chosen. */
    fun layoutGrid(columns: Int, rows: Int) {
        this.columns = max(1, columns)
        this.rows = max(1, rows)
        page = pageOf(anchor())
    }

    fun expand(): Boolean {
        if (!expandable || expanded) return false
        expanded = true
        if (selected == expandStop) selected = max(focused, 0)
        page = pageOf(anchor())
        return true
    }

    fun collapse(): Boolean {
        if (!expanded) return false
        expanded = false
        return true
    }

    fun toggle(): Boolean = if (expanded) collapse() else expand()

    fun nextPage(): Boolean = goToPage(page + 1)

    fun previousPage(): Boolean = goToPage(page - 1)

    private fun goToPage(to: Int): Boolean {
        if (to < 0 || to >= pageCount || to == page) return false
        val column = if (selected in items.indices) (selected - page * pageSize) % columns else 0
        page = to
        if (padActive) selected = min(items.size - 1, page * pageSize + column)
        return true
    }

    /** The pad's ring onto the bar: false when there is nothing to choose. */
    fun padEnter(): Boolean {
        if (items.isEmpty()) return false
        padActive = true
        selected = max(focused, 0)
        page = pageOf(selected)
        return true
    }

    fun padLeave() {
        padActive = false
        selected = -1
    }

    /**
     * A pad direction: true when the ring stays on the bar (moved, or held at an edge), false when it leaves it (Down
     * from the row, back toward the keys). In the row Left and Right step, the expand button being the last stop; in
     * the grid every direction moves, Up and Down crossing to the page before or after.
     */
    fun move(move: Move): Boolean {
        if (!padActive || items.isEmpty()) return false
        if (!expanded) {
            when (move) {
                Move.LEFT -> selected = max(0, selected - 1)
                Move.RIGHT -> selected = min(if (expandable) expandStop else items.size - 1, selected + 1)
                Move.UP -> Unit
                Move.DOWN -> return false
            }
            return true
        }
        val at = selected.coerceIn(0, items.size - 1)
        val next = when (move) {
            Move.LEFT -> at - 1
            Move.RIGHT -> at + 1
            Move.UP -> {
                val start = page * pageSize
                if (at - columns >= start) at - columns
                else if (page > 0) min(items.size - 1, (page - 1) * pageSize + (pageRows() - 1) * columns + (at - start) % columns)
                else at
            }
            Move.DOWN -> {
                val start = page * pageSize
                val end = min(items.size, start + pageSize)
                if (at + columns < end) at + columns
                else if (page + 1 < pageCount) min(items.size - 1, (page + 1) * pageSize + (at - start) % columns)
                else at
            }
        }
        selected = next.coerceIn(0, items.size - 1)
        page = pageOf(selected)
        return true
    }

    /** The candidate the pad has selected, -1 when it has none or is on the expand button. */
    fun selectedCandidate(): Int = if (padActive && selected in items.indices) selected else -1

    /** The rows a page uses: the grid's, or as many as the engine's page needs. */
    private fun pageRows(): Int = (pageSize + columns - 1) / columns

    private fun anchor(): Int = when {
        selected in items.indices -> selected
        focused >= 0 -> focused
        else -> page * pageSize
    }

    private fun pageOf(index: Int): Int = if (items.isEmpty()) 0 else index.coerceIn(0, items.size - 1) / pageSize
}
