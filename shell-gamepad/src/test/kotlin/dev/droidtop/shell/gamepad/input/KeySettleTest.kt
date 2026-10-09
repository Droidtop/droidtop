package dev.droidtop.shell.gamepad.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A zero-delay burst (Start then Down in one adb call, Droidtop/tracker#359): the Down is dispatched before the
 * menu the Start opened has joined the overlay stack, and must not reach the library.
 */
class KeySettleTest {
    private data class Key(val code: Int, val down: Boolean, val repeat: Int = 0)

    private val start = 1
    private val dpadDown = 2

    private val stack = OverlayKeys()
    private val menu = Any()
    private val reached = ArrayList<Key>()
    private val frames = ArrayList<() -> Unit>()

    /** The library is the activity window, layer 0; pressing Start sets state that composes the menu a frame later. */
    private val settle = KeySettle<Key>(
        deliver = { key ->
            val delivered = stack.route(0, key.code, key.down, key.repeat)
            if (delivered) reached += key
            delivered
        },
        opens = { it.down && it.repeat == 0 },
        nextFrame = { frames += it },
    )

    /** A frame runs what was posted before it; what that posts waits for the next one. */
    private fun frame() {
        val due = frames.toList()
        frames.clear()
        due.forEach { it() }
    }

    @Test
    fun `a Down sent with the Start that opens a menu never reaches the library`() {
        assertTrue(settle.dispatch(Key(start, down = true)))
        assertTrue(settle.dispatch(Key(start, down = false)))
        assertTrue(settle.dispatch(Key(dpadDown, down = true)))
        assertEquals("only the Start press went in at once", listOf(Key(start, down = true)), reached)
        // The menu composes and joins the stack before the held keys are let go.
        stack.push(menu)
        frame()
        assertEquals("the release and the Down were swallowed by the stack", listOf(Key(start, down = true)), reached)
        assertFalse(stack.route(0, dpadDown, down = false, repeatCount = 0))
    }

    @Test
    fun `with no menu opened the held keys arrive in order after the frame`() {
        settle.dispatch(Key(dpadDown, down = true))
        settle.dispatch(Key(dpadDown, down = false))
        settle.dispatch(Key(start, down = true))
        assertEquals(listOf(Key(dpadDown, down = true)), reached)
        frame()
        assertEquals(
            listOf(Key(dpadDown, down = true), Key(dpadDown, down = false), Key(start, down = true)),
            reached,
        )
    }

    @Test
    fun `a press that was not handled holds nothing back`() {
        val unhandled = KeySettle<Key>(deliver = { false }, opens = { it.down }, nextFrame = { frames += it })
        assertFalse(unhandled.dispatch(Key(start, down = true)))
        assertFalse(unhandled.dispatch(Key(dpadDown, down = true)))
        assertTrue("no frame was needed", frames.isEmpty())
    }

    @Test
    fun `a release or a repeat does not start a wait`() {
        val delivered = ArrayList<Key>()
        val plain = KeySettle<Key>(
            deliver = { delivered += it; true },
            opens = { it.down && it.repeat == 0 },
            nextFrame = { frames += it },
        )
        plain.dispatch(Key(dpadDown, down = true, repeat = 1))
        plain.dispatch(Key(dpadDown, down = false))
        assertTrue(frames.isEmpty())
        assertEquals(2, delivered.size)
    }

    @Test
    fun `cleared, the held keys are forgotten`() {
        settle.dispatch(Key(start, down = true))
        settle.dispatch(Key(dpadDown, down = true))
        settle.clear()
        frame()
        assertEquals(listOf(Key(start, down = true)), reached)
    }
}
