package org.pocketworkstation.pckeyboard

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** The meta state every keyboard sink derives from droidtop's hardware-style key stream (Droidtop/tracker#314). */
class KeyMetaTest {
    @Test
    fun `modifiers set and clear their own bit and other keys leave the state alone`() {
        var meta = KeyMeta.updated(0, KeyEvent.KEYCODE_SHIFT_LEFT, true)
        assertEquals(KeyEvent.META_SHIFT_ON, meta)
        meta = KeyMeta.updated(meta, KeyEvent.KEYCODE_CTRL_RIGHT, true)
        assertEquals(KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON, meta)
        assertEquals(meta, KeyMeta.updated(meta, KeyEvent.KEYCODE_A, true))
        meta = KeyMeta.updated(meta, KeyEvent.KEYCODE_SHIFT_LEFT, false)
        assertEquals(KeyEvent.META_CTRL_ON, meta)
        assertEquals(0, KeyMeta.updated(meta, KeyEvent.KEYCODE_CTRL_RIGHT, false))
    }
}
