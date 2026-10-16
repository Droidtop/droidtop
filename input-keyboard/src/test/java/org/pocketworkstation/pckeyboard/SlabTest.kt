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
}
