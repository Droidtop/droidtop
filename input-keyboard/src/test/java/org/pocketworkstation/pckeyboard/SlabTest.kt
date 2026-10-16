package org.pocketworkstation.pckeyboard

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pocketworkstation.pckeyboard.Slab.Action
import org.pocketworkstation.pckeyboard.Slab.Button
import org.pocketworkstation.pckeyboard.Slab.Form
import org.pocketworkstation.pckeyboard.Slab.Kind
import org.pocketworkstation.pckeyboard.Slab.Layer
import org.pocketworkstation.pckeyboard.Slab.Move
import org.pocketworkstation.pckeyboard.Slab.Output
import org.pocketworkstation.pckeyboard.Slab.ShiftState

/** The text-entry slab's model (docs/SPEC.md 4c, "The text-entry slab"; Droidtop/tracker#485 S7 slice 6). */
class SlabTest {
    private fun typed(text: String) = Output.Type(text)

    @Test
    fun `the slab opens on g, on the layer its kind asks for`() {
        val letters = Slab.open(Kind.SEARCH, large = false, form = Form.FIELD)
        assertEquals(Action.Char('g'), letters.focused?.action)
        assertEquals(Layer.SYMBOLS, Slab.open(Kind.NUMBER, false, Form.FIELD).layer)
        assertEquals(Layer.EMAIL, Slab.open(Kind.EMAIL, false, Form.FIELD).layer)
    }

    @Test
    fun `every printable ASCII character is on a layer`() {
        val reachable = (Slab.rows(Layer.LETTERS, false) + Slab.rows(Layer.SYMBOLS, false)).flatten()
            .mapNotNull { it.action as? Action.Char }.flatMap { listOf(it.lower, it.upper) }.toSet() + ' '
        val missing = (32..126).map { it.toChar() }.filter { it !in reachable }
        assertTrue("missing: $missing", missing.isEmpty())
    }

    @Test
    fun `large keys drop the number row and keep Backspace`() {
        val rows = Slab.rows(Layer.LETTERS, large = true)
        assertEquals(4, rows.size)
        assertTrue(rows.flatten().any { it.action == Action.Backspace })
        assertTrue(rows.flatten().none { (it.action as? Action.Char)?.lower == '1' })
    }

    @Test
    fun `left and right wrap in the row, up from the top row is the host's, down stops`() {
        val start = Slab.State(row = 1, col = 0)
        assertEquals(start.keys[1].lastIndex, Slab.move(start, Move.LEFT)!!.col)
        assertEquals(0, Slab.move(start.copy(col = start.keys[1].lastIndex), Move.RIGHT)!!.col)
        assertNull(Slab.move(start.copy(row = 0), Move.UP))
        val bottom = start.copy(row = start.keys.lastIndex)
        assertEquals(bottom, Slab.move(bottom, Move.DOWN))
    }

    @Test
    fun `down from a letter lands on the key under it, so the wide space bar is not skipped`() {
        // From 'v' in the Shift row, Down reaches Space, the widest key under the middle of the row.
        val rows = Slab.rows(Layer.LETTERS, false)
        val shiftRow = rows.indexOfFirst { row -> row.any { it.action == Action.Shift } }
        val v = rows[shiftRow].indexOfFirst { (it.action as? Action.Char)?.lower == 'v' }
        val down = Slab.move(Slab.State(row = shiftRow, col = v), Move.DOWN)!!
        assertEquals(Action.Space, down.focused?.action)
    }

    @Test
    fun `shift types one capital, a double press holds Caps, a third press lets go`() {
        var state = Slab.State()
        state = Slab.button(state, Button.L1, now = 0).first
        assertEquals(ShiftState.ONCE, state.shift)
        val (after, out) = Slab.press(state, Action.Char('a'), now = 1000)
        assertEquals(typed("A"), out)
        assertEquals(ShiftState.OFF, after.shift)

        state = Slab.button(Slab.State(), Button.L1, now = 0).first
        state = Slab.button(state, Button.L1, now = 200).first
        assertEquals(ShiftState.CAPS, state.shift)
        val (stillCaps, _) = Slab.press(state, Action.Char('b'), now = 300)
        assertEquals(ShiftState.CAPS, stillCaps.shift)
        assertEquals(ShiftState.OFF, Slab.button(stillCaps, Button.L1, now = 2000).first.shift)
    }

    @Test
    fun `two slow shift presses turn it off again instead of setting caps`() {
        var state = Slab.button(Slab.State(), Button.L1, now = 0).first
        state = Slab.button(state, Button.L1, now = 1000).first
        assertEquals(ShiftState.OFF, state.shift)
    }

    @Test
    fun `the pad buttons do their keycap's job`() {
        val state = Slab.State()
        assertEquals(Output.Press(KeyEvent.KEYCODE_DEL), Slab.button(state, Button.X, 0).second)
        assertEquals(typed(" "), Slab.button(state, Button.Y, 0).second)
        assertEquals(Output.Press(KeyEvent.KEYCODE_ENTER), Slab.button(state, Button.R2, 0).second)
        assertEquals(Output.Close, Slab.button(state, Button.B, 0).second)
        assertEquals(Output.OpenClipboard, Slab.button(state, Button.SELECT, 0).second)
        val symbols = Slab.button(state, Button.R1, 0).first
        assertEquals(Layer.SYMBOLS, symbols.layer)
        assertEquals(Layer.LETTERS, Slab.button(symbols, Button.R1, 0).first.layer)
    }

    @Test
    fun `labels follow shift, the entry's purpose and the form`() {
        val field = Slab.State()
        val enter = field.keys.flatten().first { it.action == Action.Enter }
        val done = field.keys.flatten().first { it.action == Action.Done }
        val q = field.keys.flatten().first { (it.action as? Action.Char)?.lower == 'q' }
        assertEquals("Search", field.label(enter, Slab.EnterPurpose.SEARCH))
        assertEquals("Done", field.label(done, Slab.EnterPurpose.SAVE))
        assertEquals("Cancel", field.copy(form = Form.PROMPT).label(done, Slab.EnterPurpose.SAVE))
        assertEquals("Q", field.copy(shift = ShiftState.ONCE).label(q, Slab.EnterPurpose.DONE))
    }

    @Test
    fun `a layer switch keeps the cursor near where it was`() {
        val state = Slab.State(row = 2, col = 4)
        val symbols = Slab.press(state, Action.Symbols, 0).first
        assertEquals(2, symbols.row)
        assertTrue(symbols.col in 3..5)
    }

    @Test
    fun `the extras add a whole keyboard's row above the letters and F1 to F12 above the symbols`() {
        val letters = Slab.rows(Layer.LETTERS, false, extras = true)
        assertEquals(listOf(Action.Escape, Action.Tab, Action.Ctrl, Action.Alt, Action.Super), letters.first().map { it.action })
        // The bottom row is a whole keyboard's: no Clipboard, globe or Done, and the four arrows together.
        assertEquals(
            listOf(Action.Symbols, Action.Space, Action.CaretLeft, Action.ArrowUp, Action.ArrowDown, Action.CaretRight),
            letters.last().map { it.action },
        )
        assertTrue(Slab.rows(Layer.SYMBOLS, false, extras = true).flatten().none { it.action == Action.Done || it.action == Action.Clipboard })
        val symbols = Slab.rows(Layer.SYMBOLS, false, extras = true)
        assertEquals((1..12).map { Action.Function(it) }, symbols.first().map { it.action })
        assertEquals(Slab.rows(Layer.LETTERS, false).size + 1, letters.size)
    }

    @Test
    fun `ctrl latches for one key and sends a chord, then lets go`() {
        var state = Slab.State(extras = true)
        state = Slab.press(state, Action.Ctrl, 0).first
        assertTrue(state.latched(Slab.Key(Action.Ctrl)))
        val (after, out) = Slab.press(state, Action.Char('c'), 0)
        assertEquals(Output.Chord(KeyEvent.KEYCODE_C, listOf(KeyEvent.KEYCODE_CTRL_LEFT)), out)
        assertTrue(after.modifiers.isEmpty())
        // Without a modifier the same key is plain text again.
        assertEquals(typed("c"), Slab.press(after, Action.Char('c'), 0).second)
    }

    @Test
    fun `ctrl and shift together reach the key, and a modifier pressed twice lets go`() {
        var state = Slab.State(extras = true, shift = ShiftState.ONCE)
        state = Slab.press(state, Action.Ctrl, 0).first
        val (after, out) = Slab.press(state, Action.Char('t'), 0)
        assertEquals(Output.Chord(KeyEvent.KEYCODE_T, listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT)), out)
        assertEquals(ShiftState.OFF, after.shift)
        val twice = Slab.press(Slab.press(Slab.State(extras = true), Action.Alt, 0).first, Action.Alt, 0).first
        assertTrue(twice.modifiers.isEmpty())
    }

    @Test
    fun `function keys and the arrows press their keys`() {
        assertEquals(Output.Press(KeyEvent.KEYCODE_F1 + 4), Slab.press(Slab.State(), Action.Function(5), 0).second)
        assertEquals(Output.Press(KeyEvent.KEYCODE_DPAD_UP), Slab.press(Slab.State(), Action.ArrowUp, 0).second)
        assertEquals(
            Output.Chord(KeyEvent.KEYCODE_TAB, listOf(KeyEvent.KEYCODE_ALT_LEFT)),
            Slab.press(Slab.press(Slab.State(extras = true), Action.Alt, 0).first, Action.Tab, 0).second,
        )
    }

    @Test
    fun `a whole keyboard holds a key with what is latched, and lets the one-shots go after`() {
        // Droidtop/tracker#567: a PC game sees a held arrow or letter for as long as the finger is on it.
        val latched = Slab.press(Slab.State(extras = true, shift = ShiftState.ONCE), Action.Ctrl, 0).first
        assertEquals(
            Output.Hold(KeyEvent.KEYCODE_A, listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT)),
            Slab.held(latched, Action.Char('a', 'A')),
        )
        assertEquals(Output.Hold(KeyEvent.KEYCODE_DPAD_UP, emptyList()), Slab.held(Slab.State(extras = true), Action.ArrowUp))
        assertNull("Shift acts on release", Slab.held(Slab.State(extras = true), Action.Shift))
        assertNull("a modifier latches on release", Slab.held(Slab.State(extras = true), Action.Alt))
        val after = Slab.released(latched)
        assertTrue(after.modifiers.isEmpty())
        assertEquals(ShiftState.OFF, after.shift)
        assertEquals(ShiftState.CAPS, Slab.released(Slab.State(shift = ShiftState.CAPS)).shift)
        assertTrue(Slab.repeats(Action.Backspace) && Slab.repeats(Action.ArrowDown) && !Slab.repeats(Action.Enter))
    }
}
