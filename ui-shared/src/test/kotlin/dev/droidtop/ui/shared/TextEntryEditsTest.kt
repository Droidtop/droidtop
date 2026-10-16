package dev.droidtop.ui.shared

import android.view.KeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.pocketworkstation.pckeyboard.Slab

/** What a slab press does to the entry's text (docs/SPEC.md 4c, "The text-entry slab"; Droidtop/tracker#485 S7). */
class TextEntryEditsTest {
    private fun at(text: String, caret: Int) = TextFieldValue(text, TextRange(caret))

    @Test
    fun `typing goes in at the caret and replaces a selection`() {
        assertEquals(at("zelda", 5), TextEntryEdits.apply(at("zeld", 4), Slab.Output.Type("a")))
        assertEquals(at("zx", 2), TextEntryEdits.apply(TextFieldValue("zelda", TextRange(1, 5)), Slab.Output.Type("x")))
    }

    @Test
    fun `backspace removes the selection, else one character before the caret, else nothing`() {
        val del = Slab.Output.Press(KeyEvent.KEYCODE_DEL)
        assertEquals(at("zeda", 2), TextEntryEdits.apply(at("zelda", 3), del))
        assertEquals(at("z", 1), TextEntryEdits.apply(TextFieldValue("zelda", TextRange(1, 5)), del))
        assertEquals(at("zelda", 0), TextEntryEdits.apply(at("zelda", 0), del))
    }

    @Test
    fun `the caret moves and stops at the ends`() {
        assertEquals(at("ab", 0), TextEntryEdits.apply(at("ab", 0), Slab.Output.Press(KeyEvent.KEYCODE_DPAD_LEFT)))
        assertEquals(at("ab", 2), TextEntryEdits.apply(at("ab", 1), Slab.Output.Press(KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals(at("ab", 2), TextEntryEdits.apply(at("ab", 2), Slab.Output.Press(KeyEvent.KEYCODE_DPAD_RIGHT)))
    }

    @Test
    fun `typing stops at the length limit`() {
        assertEquals(at("abc", 3), TextEntryEdits.apply(at("abc", 3), Slab.Output.Type("d"), maxLength = 3))
        assertEquals(at("abcd", 4), TextEntryEdits.apply(at("ab", 2), Slab.Output.Type("cdef"), maxLength = 4))
    }
}
