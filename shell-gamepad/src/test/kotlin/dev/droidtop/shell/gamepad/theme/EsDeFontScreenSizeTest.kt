package dev.droidtop.shell.gamepad.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A theme's `fontSize` is a fraction of the screen's SHORT axis on a
 * screen held upright and of its height otherwise -- real ES-DE's own
 * `getIsVerticalOrientation() ? getScreenWidth() : getScreenHeight()`
 * (Font.cpp:216-219). Scaling by height in both orientations made every
 * themed font about half again too large on a 1080x1920 phone.
 */
class EsDeFontScreenSizeTest {

    @Test
    fun `a wide area scales type with its height`() {
        assertEquals(720.dp, esDeFontScreenSize(1280.dp, 720.dp))
    }

    @Test
    fun `a tall area scales type with its width`() {
        assertEquals(411.dp, esDeFontScreenSize(411.dp, 731.dp))
    }

    @Test
    fun `a square area is not vertical, so it keeps the height`() {
        assertEquals(600.dp, esDeFontScreenSize(600.dp, 600.dp))
    }
}
