package dev.droidtop.shell.gamepad

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a tip bubble goes relative to the row it explains (Droidtop/tracker#366). */
class HintTipPositionTest {
    private val window = IntSize(1000, 600)
    private val bubble = IntSize(300, 40)

    @Test
    fun sitsUnderTheRowNotOverIt() {
        assertEquals(IntOffset(100, 150), tipPosition(IntRect(100, 100, 900, 150), window, bubble))
    }

    @Test
    fun flipsAboveWhenThereIsNoRoomBelow() {
        assertEquals(IntOffset(100, 520), tipPosition(IntRect(100, 560, 900, 590), window, bubble))
    }

    @Test
    fun staysInsideTheWindowSideways() {
        assertEquals(IntOffset(700, 150), tipPosition(IntRect(900, 100, 990, 150), window, bubble))
    }

    @Test
    fun staysBelowWhenAboveHasNoRoomEither() {
        assertEquals(IntOffset(0, 20), tipPosition(IntRect(0, 0, 100, 20), IntSize(1000, 50), bubble))
    }
}
