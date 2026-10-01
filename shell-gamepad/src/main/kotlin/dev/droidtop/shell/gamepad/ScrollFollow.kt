package dev.droidtop.shell.gamepad

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState

/*
 * Keeping the selection on screen, once per kind of container (docs/
 * SPEC.md 6e): a lazy list or grid scrolls only as far as it takes to show
 * the selected item whole, and not at all when it already is. A column
 * built with `verticalScroll` brings the selected `MenuRow` into view
 * itself, and a card grid moves through `GridPad`.
 *
 * Following the selection with `animateScrollToItem` put the selected row
 * at the TOP of the list on every change, a tap included, so the rows moved
 * under the finger and a second tap landed on a different row (rig,
 * dq-coordinator-23 F11 and dq-shell2-01: two settings changed by
 * accident); in the Quick Menu's System tab it made the grid jump on every
 * press and restarted the animation at the pad's repeat rate (tracker#152).
 *
 * [animate] is false for a held direction coming round again: each step
 * then lands at once, so the scroll keeps up with the cursor instead of
 * restarting an animation on every repeat.
 */

/** Scrolls [index] fully into view, by as little as it takes. */
internal suspend fun LazyListState.keepInView(index: Int, animate: Boolean = true) {
    // Two passes: an item that is not laid out yet is reached by an
    // estimate, and the second pass makes the estimate exact.
    repeat(2) {
        val info = layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return
        val top = info.viewportStartOffset
        val bottom = info.viewportEndOffset - info.afterContentPadding
        val row = visible.firstOrNull { it.index == index }
        val delta = when {
            row == null -> {
                val first = visible.first()
                val last = visible.last()
                val span = (last.offset + last.size - first.offset).coerceAtLeast(1)
                val perItem = span.toFloat() / visible.size
                if (index > last.index) {
                    (last.offset + last.size - bottom) + (index - last.index) * perItem
                } else {
                    (first.offset - top) - (first.index - index) * perItem
                }
            }
            row.offset < top -> (row.offset - top).toFloat()
            row.offset + row.size > bottom -> (row.offset + row.size - bottom).toFloat()
            else -> return
        }
        if (animate) animateScrollBy(delta) else scrollBy(delta)
    }
}

/** [LazyListState.keepInView] for a grid: rows instead of items. */
internal suspend fun LazyGridState.keepInView(index: Int, animate: Boolean = true) {
    repeat(2) {
        val info = layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return
        val top = info.viewportStartOffset
        val bottom = info.viewportEndOffset - info.afterContentPadding
        val item = visible.firstOrNull { it.index == index }
        val delta = when {
            item == null -> {
                val first = visible.first()
                val last = visible.last()
                val columns = visible.count { it.offset.y == first.offset.y }.coerceAtLeast(1)
                val rows = ((last.index - first.index) / columns + 1).coerceAtLeast(1)
                val span = (last.offset.y + last.size.height - first.offset.y).coerceAtLeast(1)
                val perRow = span.toFloat() / rows
                if (index > last.index) {
                    (last.offset.y + last.size.height - bottom) + ((index - last.index + columns - 1) / columns) * perRow
                } else {
                    (first.offset.y - top) - ((first.index - index + columns - 1) / columns) * perRow
                }
            }
            item.offset.y < top -> (item.offset.y - top).toFloat()
            item.offset.y + item.size.height > bottom -> (item.offset.y + item.size.height - bottom).toFloat()
            else -> return
        }
        if (animate) animateScrollBy(delta) else scrollBy(delta)
    }
}
