package dev.droidtop.shell.gamepad

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The D-pad over a grid of cards: one card per press, straight along the
 * row or the column, by index. The shell's three card grids (the Games
 * section's unthemed grid, the PC surface, the Launcher's Games grid) all
 * move through this one object.
 *
 * Compose's own focus search is not used for it, for two reasons the rig
 * showed. It also runs on the DOWN edge of a direction, while every screen
 * here moves on the UP edge, so a grid that left the DOWN edge unhandled
 * could move twice for one press; and it searches geometrically among the
 * cards that are composed, so Down from the second column landed on the
 * first column of the next row when that row was only partly on screen
 * (dq-shell2-01). Here the target is the card one column or one row away,
 * scrolled in by one row when it is not on screen yet, then focused.
 *
 * A card attaches [requester] for its own index and reports focus through
 * [focused]; the screen's key handler takes both edges of a direction and
 * calls [move] on the UP edge. [move] answers false at the grid's edge, so
 * the screen decides what the edge means (a system switch, the chip row).
 */
internal class GridPad(val state: LazyGridState, private val scope: CoroutineScope) {
    private val requesters = HashMap<Int, FocusRequester>()

    /** The index of the card that has focus, or -1 before any has. */
    var focused by mutableIntStateOf(-1)

    fun requester(index: Int): FocusRequester = requesters.getOrPut(index) { FocusRequester() }

    /** Columns as the grid is laid out now; a grid of equal cards fills its rows in order. */
    private fun columns(): Int = (state.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column } ?: 0) + 1

    /** Whether the focused card is in the grid's first row. */
    val onTopRow: Boolean get() = focused in 0 until columns()

    /** Moves one card in [direction]; false when there is no card that way. */
    fun move(direction: FocusDirection): Boolean {
        val count = state.layoutInfo.totalItemsCount
        val at = focused
        if (at !in 0 until count) return false
        val cols = columns()
        val target = when (direction) {
            FocusDirection.Left -> if (at % cols == 0) return false else at - 1
            FocusDirection.Right -> if (at % cols == cols - 1 || at + 1 >= count) return false else at + 1
            FocusDirection.Up -> if (at < cols) return false else at - cols
            FocusDirection.Down -> when {
                at + cols < count -> at + cols
                // The last row is shorter than this column: its last card.
                at / cols < (count - 1) / cols -> count - 1
                else -> return false
            }
            else -> return false
        }
        focus(target)
        return true
    }

    /**
     * Focuses the card at [index]. A card that is not on screen is not
     * composed and cannot take focus, so the grid first scrolls it in: by
     * one row when it is the next row, straight to it otherwise.
     */
    fun focus(index: Int) {
        scope.launch {
            val info = state.layoutInfo
            if (info.visibleItemsInfo.none { it.index == index }) {
                val first = info.visibleItemsInfo.firstOrNull()
                val last = info.visibleItemsInfo.lastOrNull()
                if (first != null && last != null) {
                    val row = (first.size.height + info.mainAxisItemSpacing).toFloat()
                    state.scrollBy(if (index > last.index) row else -row)
                }
                if (state.layoutInfo.visibleItemsInfo.none { it.index == index }) state.scrollToItem(index)
            }
            withTimeoutOrNull(1000) {
                snapshotFlow { state.layoutInfo.visibleItemsInfo.any { it.index == index } }.first { it }
            }
            // Focus brings a card only partly on screen fully into view.
            runCatching { requester(index).requestFocus() }
        }
    }
}

@Composable
internal fun rememberGridPad(state: LazyGridState = rememberLazyGridState()): GridPad {
    val scope = rememberCoroutineScope()
    return remember(state, scope) { GridPad(state, scope) }
}
