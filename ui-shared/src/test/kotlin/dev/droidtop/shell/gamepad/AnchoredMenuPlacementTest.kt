package dev.droidtop.shell.gamepad

import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where an anchored menu opens beside its item (Droidtop/tracker#827): [anchoredMenuPosition]. */
class AnchoredMenuPlacementTest {
    private val windowWidth = 1280
    private val windowHeight = 720
    private val margin = 36

    private fun place(anchor: AnchorRect, width: Int = 360, height: Int = 300, gap: Int = 8) =
        anchoredMenuPosition(anchor, width, height, windowWidth, windowHeight, margin, gap)

    @Test
    fun opensBelowTheItemStartAligned() {
        assertEquals(IntOffset(100, 168), place(AnchorRect(100, 100, 200, 160)))
    }

    @Test
    fun flipsAboveWhenThereIsNoRoomBelow() {
        // A taskbar item at the foot of the screen: the menu stands on top of it.
        assertEquals(IntOffset(100, 364), place(AnchorRect(100, 672, 280, 720)))
    }

    @Test
    fun isHeldInsideTheRightEdge() {
        assertEquals(IntOffset(windowWidth - margin - 360, 168), place(AnchorRect(1200, 100, 1260, 160)))
    }

    @Test
    fun isHeldInsideTheBottomWhenItFitsNeitherSide() {
        // 500 tall beside an item in the middle: no room above or below, so it takes the larger side and moves up.
        assertEquals(IntOffset(100, windowHeight - margin - 500), place(AnchorRect(100, 300, 200, 340), height = 500))
    }

    @Test
    fun startsAtTheMarginWhenLargerThanTheWindow() {
        assertEquals(IntOffset(margin, margin), place(AnchorRect(10, 10, 20, 20), width = 2000, height = 2000))
    }
}
