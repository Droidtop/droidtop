package org.pocketworkstation.pckeyboard

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The meta state every keyboard sink derives from droidtop's hardware-style key stream (Droidtop/tracker#314, #772). */
class MetaTrackerTest {
    @Test
    fun `modifiers set and clear their own bit and other keys leave the state alone`() {
        val meta = MetaTracker()
        assertTrue(meta.track(KeyEvent.KEYCODE_SHIFT_LEFT, true))
        assertEquals(KeyEvent.META_SHIFT_ON, meta.state)
        assertTrue(meta.track(KeyEvent.KEYCODE_CTRL_RIGHT, true))
        assertEquals(KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON, meta.state)
        assertFalse(meta.track(KeyEvent.KEYCODE_A, true))
        assertEquals(KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON, meta.state)
        meta.track(KeyEvent.KEYCODE_SHIFT_LEFT, false)
        assertEquals(KeyEvent.META_CTRL_ON, meta.state)
        meta.track(KeyEvent.KEYCODE_CTRL_RIGHT, false)
        assertEquals(0, meta.state)
    }
}
