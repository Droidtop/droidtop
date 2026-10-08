package dev.droidtop.app

import android.view.KeyEvent
import dev.droidtop.library.social.SocialOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The companion's message draft, fed the hardware-style key stream droidtop's keyboard sends (Droidtop/tracker#327). */
class CompanionDraftTest {
    /** A US map for the letters, digits and space, enough to type with. */
    private val chars: (Int, Boolean) -> Char? = { code, shift ->
        when (code) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('a' + (code - KeyEvent.KEYCODE_A)).let { if (shift) it.uppercaseChar() else it }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> if (shift && code == KeyEvent.KEYCODE_1) '!' else '0' + (code - KeyEvent.KEYCODE_0)
            KeyEvent.KEYCODE_SPACE -> ' '
            else -> null
        }
    }

    private fun CompanionDraft.press(code: Int): CompanionDraft = key(code, true, chars).first.key(code, false, chars).first

    private fun CompanionDraft.type(vararg codes: Int): CompanionDraft = codes.fold(this) { d, c -> d.press(c) }

    @Test
    fun `keys type at the caret, and Shift around a key makes a capital`() {
        var d = CompanionDraft()
        d = d.key(KeyEvent.KEYCODE_SHIFT_LEFT, true, chars).first
        d = d.press(KeyEvent.KEYCODE_H)
        d = d.key(KeyEvent.KEYCODE_SHIFT_LEFT, false, chars).first
        d = d.type(KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_2)
        assertEquals("Hi 2", d.text)
        assertEquals(4, d.cursor)
        assertFalse(d.shift)
    }

    @Test
    fun `backspace, arrows and a placed caret edit in the middle`() {
        var d = CompanionDraft().type(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_C)
        d = d.press(KeyEvent.KEYCODE_DPAD_LEFT).press(KeyEvent.KEYCODE_DEL)
        assertEquals("ac", d.text)
        assertEquals(1, d.cursor)
        d = d.placeCursor(99).press(KeyEvent.KEYCODE_X)
        assertEquals("acx", d.text)
        d = d.placeCursor(-4).press(KeyEvent.KEYCODE_FORWARD_DEL)
        assertEquals("cx", d.text)
        assertEquals(0, d.press(KeyEvent.KEYCODE_DEL).cursor)
        assertEquals(2, d.press(KeyEvent.KEYCODE_MOVE_END).cursor)
    }

    @Test
    fun `an emoji is one step for the caret and for backspace`() {
        val d = CompanionDraft().insert("a😀")
        assertEquals(3, d.cursor)
        assertEquals("a", d.press(KeyEvent.KEYCODE_DEL).text)
        assertEquals(1, d.press(KeyEvent.KEYCODE_DPAD_LEFT).cursor)
    }

    @Test
    fun `Enter asks to send and changes nothing, and a release never types`() {
        val d = CompanionDraft().type(KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_K)
        val (after, submit) = d.key(KeyEvent.KEYCODE_ENTER, true, chars)
        assertTrue(submit)
        assertEquals(d, after)
        assertFalse(d.key(KeyEvent.KEYCODE_ENTER, false, chars).second)
        assertEquals(d, d.key(KeyEvent.KEYCODE_Q, false, chars).first)
    }

    @Test
    fun `a Ctrl chord types nothing, and a key with no character is ignored`() {
        var d = CompanionDraft().key(KeyEvent.KEYCODE_CTRL_LEFT, true, chars).first
        d = d.press(KeyEvent.KEYCODE_C)
        assertEquals("", d.text)
        d = d.key(KeyEvent.KEYCODE_CTRL_LEFT, false, chars).first.press(KeyEvent.KEYCODE_F1)
        assertEquals("", d.text)
    }

    @Test
    fun `the draft never grows past one message`() {
        val full = CompanionDraft().insert("x".repeat(SocialOrder.MAX_MESSAGE + 10))
        assertEquals(SocialOrder.MAX_MESSAGE, full.text.length)
        assertEquals(full, full.press(KeyEvent.KEYCODE_A))
    }
}
