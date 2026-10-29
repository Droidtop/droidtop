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

    @Test
    fun `a tap of Super alone opens Start, and Super still reaches the container`() {
        assertEquals(false to null, down(KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_ON))
        assertEquals(false to Route.START_MENU, up(KeyEvent.KEYCODE_META_LEFT))
    }

    @Test
    fun `Super used with another key is not a tap`() {
        down(KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_ON)
        assertEquals(false to null, down(KeyEvent.KEYCODE_B, KeyEvent.META_META_ON))
        up(KeyEvent.KEYCODE_B, KeyEvent.META_META_ON)
        assertEquals(false to null, up(KeyEvent.KEYCODE_META_LEFT))
        // Ctrl held as Super goes down is not a tap either.
        down(KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON)
        assertEquals(false to null, up(KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_CTRL_ON))
    }

    @Test
    fun `Super S, Alt Tab and Ctrl Esc are droidtop's, press and release`() {
        assertEquals(true to Route.SEARCH, down(KeyEvent.KEYCODE_S, KeyEvent.META_META_ON))
        assertEquals(true to null, up(KeyEvent.KEYCODE_S))
        assertEquals(true to Route.WINDOWS, down(KeyEvent.KEYCODE_TAB, KeyEvent.META_ALT_ON))
        // A held Tab repeats without raising the sheet again.
        assertEquals(true to null, down(KeyEvent.KEYCODE_TAB, KeyEvent.META_ALT_ON, repeat = 1))
        assertEquals(true to null, up(KeyEvent.KEYCODE_TAB))
        assertEquals(true to Route.START_MENU, down(KeyEvent.KEYCODE_ESCAPE, KeyEvent.META_CTRL_ON))
        assertEquals(true to null, up(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test
    fun `everything else is the container's`() {
        assertEquals(false to null, down(KeyEvent.KEYCODE_S))
        assertEquals(false to null, up(KeyEvent.KEYCODE_S))
        assertEquals(false to null, down(KeyEvent.KEYCODE_TAB))
        assertEquals(false to null, down(KeyEvent.KEYCODE_ESCAPE, KeyEvent.META_ALT_ON))
    }
}
