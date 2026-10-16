package org.pocketworkstation.pckeyboard

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The slab's output reaching a KeyboardSink (docs/SPEC.md 4c, the companion's docked slab). */
class SlabSinkTest {
    private class Recorder(override val takesText: Boolean) : KeyboardSink {
        val keys = mutableListOf<Pair<Int, Boolean>>()
        val text = StringBuilder()
        override fun key(androidKeyCode: Int, down: Boolean) { keys += androidKeyCode to down }
        override fun text(chars: CharSequence) { text.append(chars) }
    }

    @Test
    fun `text goes through the text channel and keys as a down and an up`() {
        val sink = Recorder(takesText = true)
        SlabSink.deliver(Slab.Output.Type("Zo"), sink, onClose = {})
        SlabSink.deliver(Slab.Output.Press(KeyEvent.KEYCODE_DEL), sink, onClose = {})
        assertEquals("Zo", sink.text.toString())
        assertEquals(listOf(KeyEvent.KEYCODE_DEL to true, KeyEvent.KEYCODE_DEL to false), sink.keys)
    }

    @Test
    fun `a sink without text gets each character as its key, shift around a capital and a shifted symbol`() {
        val sink = Recorder(takesText = false)
        SlabSink.deliver(Slab.Output.Type("A!"), sink, onClose = {})
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_SHIFT_LEFT to true, KeyEvent.KEYCODE_A to true, KeyEvent.KEYCODE_A to false, KeyEvent.KEYCODE_SHIFT_LEFT to false,
                KeyEvent.KEYCODE_SHIFT_LEFT to true, KeyEvent.KEYCODE_1 to true, KeyEvent.KEYCODE_1 to false, KeyEvent.KEYCODE_SHIFT_LEFT to false,
            ),
            sink.keys,
        )
    }

    @Test
    fun `close goes to the host, and every printable ASCII character has a key`() {
        var closed = false
        SlabSink.deliver(Slab.Output.Close, Recorder(true), onClose = { closed = true })
        assertTrue(closed)
        val missing = (32..126).map { it.toChar() }.filter { SlabSink.UsKeys.of(it) == null }
        assertTrue("no key for $missing", missing.isEmpty())
    }

    @Test
    fun `a chord presses its modifiers around the key and lets them go in reverse`() {
        val sink = Recorder(takesText = true)
        SlabSink.deliver(Slab.Output.Chord(KeyEvent.KEYCODE_C, listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT)), sink, onClose = {})
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_CTRL_LEFT to true, KeyEvent.KEYCODE_SHIFT_LEFT to true,
                KeyEvent.KEYCODE_C to true, KeyEvent.KEYCODE_C to false,
                KeyEvent.KEYCODE_SHIFT_LEFT to false, KeyEvent.KEYCODE_CTRL_LEFT to false,
            ),
            sink.keys,
        )
    }

    @Test
    fun `a held key goes down with its modifiers, repeats as key-downs, and comes up in reverse`() {
        val sink = Recorder(takesText = true)
        val hold = Slab.Output.Hold(KeyEvent.KEYCODE_DPAD_UP, listOf(KeyEvent.KEYCODE_SHIFT_LEFT))
        SlabSink.deliver(Slab.Output.Down(hold), sink, onClose = {})
        SlabSink.deliver(Slab.Output.Down(hold, repeat = true), sink, onClose = {})
        SlabSink.deliver(Slab.Output.Up(hold), sink, onClose = {})
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_SHIFT_LEFT to true, KeyEvent.KEYCODE_DPAD_UP to true,
                KeyEvent.KEYCODE_DPAD_UP to true,
                KeyEvent.KEYCODE_DPAD_UP to false, KeyEvent.KEYCODE_SHIFT_LEFT to false,
            ),
            sink.keys,
        )
    }
}
