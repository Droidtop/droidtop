package dev.droidtop.shell.gamepad.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Droidtop/tracker#660: the game page's bar named Play twice ("A Play", "X Play") with the cursor on Launch. */
class PcGamePageHintsTest {
    @Test
    fun `on the first action A names the verb, so X does not name it again`() {
        assertEquals("Play", pageActionLabel(0, "Play"))
        assertFalse(xNamesPlay(PageZone.ACTIONS, 0))
    }

    @Test
    fun `everywhere else X names the verb`() {
        assertTrue(xNamesPlay(PageZone.ACTIONS, 1))
        assertTrue(xNamesPlay(PageZone.TABS, 0))
        assertTrue(xNamesPlay(PageZone.CONTENT, 0))
    }
}
