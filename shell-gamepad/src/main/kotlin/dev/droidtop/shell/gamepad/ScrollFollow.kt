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

/**
 * Scrolls [index] fully into view, by as little as it takes. [under] is a
 * sticky header the item must stay clear of (the game page's tab strip): its
 * lower edge is the top of the room the item has. It is only for items
 * after that header.
 */
internal suspend fun LazyListState.keepInView(index: Int, animate: Boolean = true, under: Int? = null) {
    // Two passes: an item that is not laid out yet is reached by an
    // estimate, and the second pass makes the estimate exact.
    repeat(2) {
        val info = layoutInfo
        val all = info.visibleItemsInfo
        val header = under?.let { h -> all.firstOrNull { it.index == h } }
        // A pinned header is drawn over the rows, not among them: it takes no part in the estimate.
        val visible = if (header == null) all else all.filter { it.index != under }
        if (visible.isEmpty()) return
        val top = maxOf(info.viewportStartOffset, header?.let { it.offset + it.size } ?: Int.MIN_VALUE)
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
        if (animate) animateScrollBy(delta, Motion.scroll(chained = isScrollInProgress)) else scrollBy(delta)
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
        if (animate) animateScrollBy(delta, Motion.scroll(chained = isScrollInProgress)) else scrollBy(delta)
    }
}

/**
 * How far to scroll so an item lands in the middle of the visible span:
 * its own centre minus the span's centre. Positive scrolls forward. Pure,
 * for the tests.
 */
internal fun centreDelta(itemStart: Float, itemSize: Float, spanStart: Float, spanEnd: Float): Float =
    itemStart + itemSize / 2f - (spanStart + spanEnd) / 2f

/**
 * Scrolls [index] to the CENTRE of the list (docs/SPEC.md "Gaming motion
 * and focus"): a shelf or strip then always shows what is on both sides of
 * the selection, and at either end the scroll simply stops where the list
 * does. The first press eases; a press that arrives mid-scroll, or
 * [chained] (a held direction repeating), is linear, so holding a direction
 * glides ([Motion.scroll]). Nothing here measures anything the lazy list
 * has not already measured.
 */
internal suspend fun LazyListState.keepCentred(index: Int, chained: Boolean = false) {
    repeat(2) {
        val info = layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return
        val start = (info.viewportStartOffset + info.beforeContentPadding).toFloat()
        val end = (info.viewportEndOffset - info.afterContentPadding).toFloat()
        val item = visible.firstOrNull { it.index == index }
        val delta = if (item != null) {
            centreDelta(item.offset.toFloat(), item.size.toFloat(), start, end)
        } else {
            val first = visible.first()
            val last = visible.last()
            val perItem = ((last.offset + last.size - first.offset).coerceAtLeast(1)).toFloat() / visible.size
            val estimatedStart = if (index > last.index) {
                last.offset + (index - last.index) * perItem
            } else {
                first.offset - (first.index - index) * perItem
            }
            centreDelta(estimatedStart, perItem, start, end)
        }
        if (kotlin.math.abs(delta) < 1f) return
        animateScrollBy(delta, Motion.scroll(chained = chained || isScrollInProgress))
    }
}

/** [LazyListState.keepCentred] for a grid: the selection's row, centred vertically. */
internal suspend fun LazyGridState.keepCentred(index: Int, chained: Boolean = false) {
    repeat(2) {
        val info = layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return
        val start = (info.viewportStartOffset + info.beforeContentPadding).toFloat()
        val end = (info.viewportEndOffset - info.afterContentPadding).toFloat()
        val item = visible.firstOrNull { it.index == index }
        val delta = if (item != null) {
            centreDelta(item.offset.y.toFloat(), item.size.height.toFloat(), start, end)
        } else {
            val first = visible.first()
            val last = visible.last()
            val columns = visible.count { it.offset.y == first.offset.y }.coerceAtLeast(1)
            val rows = ((last.index - first.index) / columns + 1).coerceAtLeast(1)
            val perRow = ((last.offset.y + last.size.height - first.offset.y).coerceAtLeast(1)).toFloat() / rows
            val estimatedStart = if (index > last.index) {
                last.offset.y + ((index - last.index + columns - 1) / columns) * perRow
            } else {
                first.offset.y - ((first.index - index + columns - 1) / columns) * perRow
            }
            centreDelta(estimatedStart, perRow, start, end)
        }
        if (kotlin.math.abs(delta) < 1f) return
        animateScrollBy(delta, Motion.scroll(chained = chained || isScrollInProgress))
    }
}
