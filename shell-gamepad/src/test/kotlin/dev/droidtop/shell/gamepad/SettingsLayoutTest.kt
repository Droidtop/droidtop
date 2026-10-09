package dev.droidtop.shell.gamepad

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Steam's settings proportions (docs/SPEC.md "Settings layout", [SettingsLayout]). */
class SettingsLayoutTest {

    @Test
    fun `the category column is a fifth of a wide page and never under 240dp`() {
        assertEquals(240.dp, SettingsLayout.columnWidth(768.dp))
        assertEquals(240.dp, SettingsLayout.columnWidth(1000.dp))
        assertEquals(400f, SettingsLayout.columnWidth(2000.dp).value, 0.01f)
    }

    @Test
    fun `the column never takes more than the page`() {
        assertEquals(200.dp, SettingsLayout.columnWidth(200.dp))
    }

    @Test
    fun `the selected row keeps Steam's room above and below it`() {
        // 800 tall: Steam's own 250 above and 60 below.
        assertEquals(250 to 60, SettingsLayout.scrollRoom(viewportPx = 800, itemPx = 100))
    }

    @Test
    fun `room that would not fit beside the row shrinks, keeping its proportions`() {
        val (above, below) = SettingsLayout.scrollRoom(viewportPx = 300, itemPx = 250)
        assertTrue(above + below <= 50)
        assertTrue(above > below)
        assertEquals(0 to 0, SettingsLayout.scrollRoom(viewportPx = 100, itemPx = 120))
    }
}
