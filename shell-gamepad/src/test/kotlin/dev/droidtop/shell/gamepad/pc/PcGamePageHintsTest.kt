package dev.droidtop.shell.gamepad.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Droidtop/tracker#660: the game page's bar named Play twice ("A Play", "X Play") with the cursor on Launch. */
class PcGamePageHintsTest {
    @Test
    fun `on the first action A names the verb, so X does not name it again`() {
        assertEquals("Play", pageActionLabel(PageButton.PRIMARY, "Play"))
        assertFalse(xNamesPlay(PageZone.ACTIONS, PageButton.PRIMARY))
    }

    @Test
    fun `everywhere else X names the verb`() {
        assertTrue(xNamesPlay(PageZone.ACTIONS, PageButton.OPTIONS))
        assertTrue(xNamesPlay(PageZone.TABS, PageButton.PRIMARY))
        assertTrue(xNamesPlay(PageZone.CONTENT, PageButton.PRIMARY))
    }
}
