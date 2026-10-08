package dev.droidtop.runtime.keyboard

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The accessibility route's splice (docs/SPEC.md 4c, "Typing on the add-on display", Droidtop/tracker#314). */
class TextSpliceTest {
    private fun at(text: String, start: Int, end: Int = start) = FieldText(text, start, end)

    @Test
    fun `typing goes in at the cursor and keeps the text around it`() {
        assertEquals(at("heXllo", 3), TextSplice.apply(at("hello", 2), TextEdit.Insert("X")))
        assertEquals(at("hello!", 6), TextSplice.apply(at("hello", 5), TextEdit.Insert("!")))
        assertEquals(at("ab", 2), TextSplice.apply(at("", 0), TextEdit.Insert("ab")))
    }

    @Test
    fun `typing over a selection replaces it, in either direction`() {
        assertEquals(at("hXo", 2), TextSplice.apply(at("hello", 1, 4), TextEdit.Insert("X")))
        assertEquals(at("hXo", 2), TextSplice.apply(at("hello", 4, 1), TextEdit.Insert("X")))
    }

    @Test
    fun `backspace and delete remove one character or the selection, and nothing at the edges`() {
        assertEquals(at("hllo", 1), TextSplice.apply(at("hello", 2), TextEdit.DeleteBackward))
        assertEquals(at("helo", 2), TextSplice.apply(at("hello", 2), TextEdit.DeleteForward))
        assertEquals(at("ho", 1), TextSplice.apply(at("hello", 1, 4), TextEdit.DeleteBackward))
        assertEquals(at("ho", 1), TextSplice.apply(at("hello", 1, 4), TextEdit.DeleteForward))
        assertNull(TextSplice.apply(at("hello", 0), TextEdit.DeleteBackward))
        assertNull(TextSplice.apply(at("hello", 5), TextEdit.DeleteForward))
    }

    @Test
    fun `an emoji is one character, never half a surrogate pair`() {
        val smile = "a\uD83D\uDE00b"
        assertEquals(at("ab", 1), TextSplice.apply(at(smile, 3), TextEdit.DeleteBackward))
        assertEquals(at("ab", 1), TextSplice.apply(at(smile, 1), TextEdit.DeleteForward))
        assertEquals(at(smile, 1), TextSplice.apply(at(smile, 3), TextEdit.Left))
        assertEquals(at(smile, 3), TextSplice.apply(at(smile, 1), TextEdit.Right))
    }

    @Test
    fun `arrows collapse a selection and move a cursor, home and end and select all`() {
        assertEquals(at("hello", 1), TextSplice.apply(at("hello", 1, 4), TextEdit.Left))
        assertEquals(at("hello", 4), TextSplice.apply(at("hello", 1, 4), TextEdit.Right))
        assertEquals(at("hello", 1), TextSplice.apply(at("hello", 2), TextEdit.Left))
        assertNull(TextSplice.apply(at("hello", 0), TextEdit.Left))
        assertNull(TextSplice.apply(at("hello", 5), TextEdit.Right))
        assertEquals(at("hello", 0), TextSplice.apply(at("hello", 3), TextEdit.Home))
        assertEquals(at("hello", 5), TextSplice.apply(at("hello", 3), TextEdit.End))
        assertEquals(at("hello", 0, 5), TextSplice.apply(at("hello", 3), TextEdit.SelectAll))
        assertNull(TextSplice.apply(at("hello", 0, 5), TextEdit.SelectAll))
    }

    @Test
    fun `a hint is no text, and a missing or stale selection puts the cursor at the end`() {
        assertEquals(at("", 0), FieldText.of("Search", showingHint = true, selStart = -1, selEnd = -1))
        assertEquals(at("abc", 3), FieldText.of("abc", showingHint = false, selStart = -1, selEnd = -1))
        assertEquals(at("abc", 3), FieldText.of("abc", showingHint = false, selStart = 9, selEnd = 9))
        assertEquals(at("abc", 1, 2), FieldText.of("abc", showingHint = false, selStart = 1, selEnd = 2))
        assertEquals(at("abc", 1), FieldText.of("abc", showingHint = false, selStart = 1, selEnd = 7))
        assertEquals(at("", 0), FieldText.of(null, showingHint = false, selStart = 0, selEnd = 0))
    }

    @Test
    fun `keys map to edits, Ctrl to the clipboard, Enter to a new line or the field's own action`() {
        assertEquals(FieldKey.Edit(TextEdit.Insert("q")), FieldKeys.map(KeyEvent.KEYCODE_Q, 0, 'q', false))
        assertEquals(FieldKey.Edit(TextEdit.Insert(" ")), FieldKeys.map(KeyEvent.KEYCODE_SPACE, 0, ' ', false))
        assertEquals(FieldKey.Edit(TextEdit.DeleteBackward), FieldKeys.map(KeyEvent.KEYCODE_DEL, 0, null, false))
        assertEquals(FieldKey.Edit(TextEdit.Left), FieldKeys.map(KeyEvent.KEYCODE_DPAD_LEFT, 0, null, false))
        assertEquals(FieldKey.ImeEnter, FieldKeys.map(KeyEvent.KEYCODE_ENTER, 0, '\n', false))
        assertEquals(FieldKey.Edit(TextEdit.Insert("\n")), FieldKeys.map(KeyEvent.KEYCODE_ENTER, 0, '\n', true))
        assertEquals(FieldKey.Paste, FieldKeys.map(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON, 'v', false))
        assertEquals(FieldKey.Edit(TextEdit.SelectAll), FieldKeys.map(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON, 'a', false))
        assertNull(FieldKeys.map(KeyEvent.KEYCODE_Q, KeyEvent.META_CTRL_ON, 'q', false))
        assertNull(FieldKeys.map(KeyEvent.KEYCODE_TAB, 0, '\t', false))
        assertNull(FieldKeys.map(KeyEvent.KEYCODE_F5, 0, null, false))
    }
}
