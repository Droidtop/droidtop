package dev.droidtop.shell.desktop

import android.view.KeyEvent
import dev.droidtop.shell.desktop.DesktopPadRoutes.Route
import org.junit.Assert.assertEquals
import org.junit.Test

/** The keyboard's shortcuts to droidtop's chrome (docs/SPEC.md 6b "Where Meta goes", Droidtop/tracker#697). */
class DesktopKeyShortcutsTest {
    private val keys = DesktopPadRoutes.KeyShortcuts()

    private fun down(code: Int, meta: Int = 0, repeat: Int = 0) = keys.onKey(code, true, repeat, meta)
    private fun up(code: Int, meta: Int = 0) = keys.onKey(code, false, 0, meta)

    private val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    private val alt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON

    @Test
    fun `Ctrl Esc, Alt Esc and Alt Space are droidtop's, press and release`() {
        assertEquals(true to Route.START_MENU, down(KeyEvent.KEYCODE_ESCAPE, ctrl))
        assertEquals(true to null, up(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(true to Route.WINDOWS, down(KeyEvent.KEYCODE_ESCAPE, alt))
        // A held Esc repeats without raising the sheet again.
        assertEquals(true to null, down(KeyEvent.KEYCODE_ESCAPE, alt, repeat = 1))
        assertEquals(true to null, up(KeyEvent.KEYCODE_ESCAPE, alt))
        assertEquals(true to Route.SEARCH, down(KeyEvent.KEYCODE_SPACE, alt or KeyEvent.META_SHIFT_ON))
        assertEquals(true to null, up(KeyEvent.KEYCODE_SPACE))
    }

    @Test
    fun `everything else is the container's`() {
        assertEquals(false to null, down(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(false to null, up(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(false to null, down(KeyEvent.KEYCODE_SPACE, ctrl))
        assertEquals(false to null, down(KeyEvent.KEYCODE_ESCAPE, ctrl or alt))
        assertEquals(false to null, down(KeyEvent.KEYCODE_TAB, alt))
        assertEquals(false to null, down(KeyEvent.KEYCODE_S, KeyEvent.META_META_ON))
        assertEquals(false to null, down(KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_ON))
    }
}
