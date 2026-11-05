package dev.droidtop.runtime.keyboard

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which keys Android keeps are passed to a Desktop session in front (Droidtop/tracker#697). */
class DesktopSystemKeysTest {
    private val meta = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
    private val alt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
    private val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON

    @Test
    fun `Super, its chords, Alt+Tab, Ctrl+Space and All apps are the keys Android keeps`() {
        assertTrue(DesktopSystemKeys.Filter.isSystemKey(KeyEvent.KEYCODE_META_LEFT, 0))
        assertTrue(DesktopSystemKeys.Filter.isSystemKey(KeyEvent.KEYCODE_META_RIGHT, meta))
        assertTrue(DesktopSystemKeys.Filter.isSystemKey(KeyEvent.KEYCODE_E, meta))
        assertTrue(DesktopSystemKeys.Filter.isSystemKey(KeyEvent.KEYCODE_TAB, alt))
        assertTrue(DesktopSystemKeys.Filter.isSystemKey(KeyEvent.KEYCODE_TAB, alt or KeyEvent.META_SHIFT_ON))
        assertTrue(DesktopSystemKeys.Filter.isSystemKey(KeyEvent.KEYCODE_SPACE, ctrl))
        assertTrue(DesktopSystemKeys.Filter.isSystemKey(KeyEvent.KEYCODE_ALL_APPS, 0))
    }

    @Test
    fun `every other key stays with droidtop's windows`() {
        assertFalse(DesktopSystemKeys.Filter.isSystemKey(KeyEvent.KEYCODE_TAB, 0))
        assertFalse(DesktopSystemKeys.Filter.isSystemKey(KeyEvent.KEYCODE_SPACE, 0))
        assertFalse(DesktopSystemKeys.Filter.isSystemKey(KeyEvent.KEYCODE_SPACE, alt))
        assertFalse(DesktopSystemKeys.Filter.isSystemKey(KeyEvent.KEYCODE_C, ctrl))
        assertFalse(DesktopSystemKeys.Filter.isSystemKey(KeyEvent.KEYCODE_ESCAPE, ctrl))
        assertFalse(DesktopSystemKeys.Filter.isSystemKey(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON))
    }

    @Test
    fun `a taken press takes its release, after the modifier went up too`() {
        val filter = DesktopSystemKeys.Filter()
        assertTrue(filter.take(KeyEvent.KEYCODE_META_LEFT, down = true, metaState = meta))
        assertTrue(filter.take(KeyEvent.KEYCODE_E, down = true, metaState = meta))
        assertTrue(filter.take(KeyEvent.KEYCODE_META_LEFT, down = false, metaState = 0))
        // E released with Super already up still goes where its press went.
        assertTrue(filter.take(KeyEvent.KEYCODE_E, down = false, metaState = 0))
        // A key that was not taken is not taken on release either.
        assertFalse(filter.take(KeyEvent.KEYCODE_A, down = true, metaState = 0))
        assertFalse(filter.take(KeyEvent.KEYCODE_A, down = false, metaState = 0))
    }

    @Test
    fun `reset forgets held keys`() {
        val filter = DesktopSystemKeys.Filter()
        filter.take(KeyEvent.KEYCODE_TAB, down = true, metaState = alt)
        filter.reset()
        assertFalse(filter.take(KeyEvent.KEYCODE_TAB, down = false, metaState = 0))
    }
}
