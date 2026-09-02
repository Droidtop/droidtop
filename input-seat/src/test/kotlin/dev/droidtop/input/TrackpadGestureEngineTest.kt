package dev.droidtop.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trackpad's gesture model, checked as pure logic.
 *
 * These are the tests that can exist without hardware, and they are the
 * ones worth having: every failure mode below (a stuck button, a click
 * that fires twice, a cursor that teleports when a second finger lands)
 * is invisible in a screenshot and obvious in a state machine.
 */
class TrackpadGestureEngineTest {

    private sealed interface Event {
        data class Move(val dx: Float, val dy: Float, val speed: Float) : Event
        data class Scroll(val dx: Float, val dy: Float) : Event
        data class Button(val code: Int, val pressed: Boolean) : Event
    }

    private class Recorder : TrackpadOutput {
        val events = mutableListOf<Event>()
        override fun onMove(dxMm: Float, dyMm: Float, speedMmPerS: Float) {
            events += Event.Move(dxMm, dyMm, speedMmPerS)
        }

        override fun onScroll(dxMm: Float, dyMm: Float) {
            events += Event.Scroll(dxMm, dyMm)
        }

        override fun onButton(evdevButton: Int, pressed: Boolean) {
            events += Event.Button(evdevButton, pressed)
        }

        val buttons: List<Event.Button> get() = events.filterIsInstance<Event.Button>()
        val moves: List<Event.Move> get() = events.filterIsInstance<Event.Move>()
        val scrolls: List<Event.Scroll> get() = events.filterIsInstance<Event.Scroll>()
    }

    private fun touch(id: Int, x: Float, y: Float) = TrackpadTouch(id, x, y)

    @Test
    fun `a quick single-finger tap is a left click`() {
        val out = Recorder()
        val engine = TrackpadGestureEngine(out)

        engine.onFrame(listOf(touch(0, 10f, 10f)), 0L)
        engine.onFrame(emptyList(), 50L)

        // Pressed straight away; the release waits for the tap-and-drag
        // window, because a finger coming back inside it means this was a
        // drag rather than a click.
        assertEquals(listOf(Event.Button(EvdevKeys.BTN_LEFT, true)), out.buttons)
        assertNotNull(engine.nextTimeoutAtMs())

        engine.tick(400L)
        assertEquals(
            listOf(
                Event.Button(EvdevKeys.BTN_LEFT, true),
                Event.Button(EvdevKeys.BTN_LEFT, false),
            ),
            out.buttons,
        )
        assertNull(engine.nextTimeoutAtMs())
    }

    @Test
    fun `a touch held longer than the tap timeout is not a click`() {
        val out = Recorder()
        val engine = TrackpadGestureEngine(out)

        engine.onFrame(listOf(touch(0, 10f, 10f)), 0L)
        engine.onFrame(emptyList(), 500L)

        assertTrue(out.buttons.isEmpty())
    }

    @Test
    fun `a touch that moves further than the tap slop is not a click`() {
        val out = Recorder()
        val engine = TrackpadGestureEngine(out)

        engine.onFrame(listOf(touch(0, 10f, 10f)), 0L)
        engine.onFrame(listOf(touch(0, 30f, 10f)), 20L)
        engine.onFrame(emptyList(), 40L)

        assertTrue(out.buttons.isEmpty())
        assertEquals(1, out.moves.size)
    }

    @Test
    fun `tap then finger down again drags with the button held`() {
        val out = Recorder()
        val engine = TrackpadGestureEngine(out)

        // Tap.
        engine.onFrame(listOf(touch(0, 10f, 10f)), 0L)
        engine.onFrame(emptyList(), 50L)
        // Finger returns inside the tap-and-drag window.
        engine.onFrame(listOf(touch(1, 10f, 10f)), 120L)
        engine.onFrame(listOf(touch(1, 40f, 10f)), 160L)

        // Exactly one press so far, and NO release: the click that would
        // otherwise have been delivered has become the start of a drag.
        assertEquals(listOf(Event.Button(EvdevKeys.BTN_LEFT, true)), out.buttons)
        assertEquals(1, out.moves.size)
        assertNull(engine.nextTimeoutAtMs())

        engine.onFrame(emptyList(), 200L)
        assertEquals(
            listOf(
                Event.Button(EvdevKeys.BTN_LEFT, true),
                Event.Button(EvdevKeys.BTN_LEFT, false),
            ),
            out.buttons,
        )
    }

    @Test
    fun `a finger returning after the tap-and-drag window is a second click, not a drag`() {
        val out = Recorder()
        val engine = TrackpadGestureEngine(out)

        engine.onFrame(listOf(touch(0, 10f, 10f)), 0L)
        engine.onFrame(emptyList(), 50L)
        // Well past the window: the tick inside onFrame closes the first
        // click before the new touch is even considered.
        engine.onFrame(listOf(touch(1, 10f, 10f)), 900L)
        engine.onFrame(emptyList(), 940L)
        engine.tick(1400L)

        assertEquals(
            listOf(
                Event.Button(EvdevKeys.BTN_LEFT, true),
                Event.Button(EvdevKeys.BTN_LEFT, false),
                Event.Button(EvdevKeys.BTN_LEFT, true),
                Event.Button(EvdevKeys.BTN_LEFT, false),
            ),
            out.buttons,
        )
    }

    @Test
    fun `two-finger tap is right click and three-finger tap is middle click`() {
        val out = Recorder()
        val engine = TrackpadGestureEngine(out)

        engine.onFrame(listOf(touch(0, 10f, 10f), touch(1, 20f, 10f)), 0L)
        engine.onFrame(emptyList(), 60L)
        assertEquals(
            listOf(
                Event.Button(EvdevKeys.BTN_RIGHT, true),
                Event.Button(EvdevKeys.BTN_RIGHT, false),
            ),
            out.buttons,
        )

        val out3 = Recorder()
        val engine3 = TrackpadGestureEngine(out3)
        engine3.onFrame(listOf(touch(0, 10f, 10f), touch(1, 20f, 10f), touch(2, 30f, 10f)), 0L)
        engine3.onFrame(emptyList(), 60L)
        assertEquals(
            listOf(
                Event.Button(EvdevKeys.BTN_MIDDLE, true),
                Event.Button(EvdevKeys.BTN_MIDDLE, false),
            ),
            out3.buttons,
        )
    }

    @Test
    fun `a two-finger gesture that scrolls does not also right click`() {
        val out = Recorder()
        val engine = TrackpadGestureEngine(out)

        engine.onFrame(listOf(touch(0, 10f, 10f), touch(1, 20f, 10f)), 0L)
        engine.onFrame(listOf(touch(0, 10f, 20f), touch(1, 20f, 20f)), 30L)
        engine.onFrame(emptyList(), 60L)

        assertTrue(out.buttons.isEmpty())
        assertTrue(out.scrolls.isNotEmpty())
    }

    @Test
    fun `two fingers scroll rather than moving the pointer`() {
        val out = Recorder()
        val engine = TrackpadGestureEngine(out)

        engine.onFrame(listOf(touch(0, 10f, 10f), touch(1, 20f, 10f)), 0L)
        engine.onFrame(listOf(touch(0, 10f, 15f), touch(1, 20f, 15f)), 30L)
        engine.onFrame(listOf(touch(0, 10f, 20f), touch(1, 20f, 20f)), 60L)

        assertTrue(out.moves.isEmpty())
        // The travel that proved it was a scroll is delivered rather than
        // discarded, so the total is the full 10 mm the fingers moved.
        assertEquals(10f, out.scrolls.sumOf { it.dy.toDouble() }.toFloat(), 0.001f)
    }

    @Test
    fun `a second finger landing mid-move does not teleport the pointer`() {
        val out = Recorder()
        val engine = TrackpadGestureEngine(out)

        // One finger at x=10 moves to x=20.
        engine.onFrame(listOf(touch(0, 10f, 10f)), 0L)
        engine.onFrame(listOf(touch(0, 20f, 10f)), 20L)
        // A second finger lands far away at x=80. A centroid computed over
        // ALL pointers would jump by 30 mm; only pointers present in both
        // frames may contribute, so this frame reports nothing.
        engine.onFrame(listOf(touch(0, 20f, 10f), touch(1, 80f, 10f)), 40L)

        assertEquals(1, out.moves.size)
        assertEquals(10f, out.moves[0].dx, 0.001f)
        assertTrue(out.scrolls.isEmpty())
    }

    @Test
    fun `a second finger landing mid-drag releases the button rather than leaving it stuck`() {
        val out = Recorder()
        val engine = TrackpadGestureEngine(out)

        engine.onFrame(listOf(touch(0, 10f, 10f)), 0L)
        engine.onFrame(emptyList(), 50L)
        engine.onFrame(listOf(touch(1, 10f, 10f)), 120L)
        engine.onFrame(listOf(touch(1, 10f, 10f), touch(2, 40f, 10f)), 160L)

        assertEquals(
            listOf(
                Event.Button(EvdevKeys.BTN_LEFT, true),
                Event.Button(EvdevKeys.BTN_LEFT, false),
            ),
            out.buttons,
        )
    }

    @Test
    fun `cancel releases a held drag`() {
        val out = Recorder()
        val engine = TrackpadGestureEngine(out)

        engine.onFrame(listOf(touch(0, 10f, 10f)), 0L)
        engine.onFrame(emptyList(), 50L)
        engine.onFrame(listOf(touch(1, 10f, 10f)), 120L)
        engine.cancel()

        assertEquals(
            listOf(
                Event.Button(EvdevKeys.BTN_LEFT, true),
                Event.Button(EvdevKeys.BTN_LEFT, false),
            ),
            out.buttons,
        )
        assertNull(engine.nextTimeoutAtMs())
    }

    @Test
    fun `cancel releases a click still waiting for its release`() {
        val out = Recorder()
        val engine = TrackpadGestureEngine(out)

        engine.onFrame(listOf(touch(0, 10f, 10f)), 0L)
        engine.onFrame(emptyList(), 50L)
        engine.cancel()

        assertEquals(
            listOf(
                Event.Button(EvdevKeys.BTN_LEFT, true),
                Event.Button(EvdevKeys.BTN_LEFT, false),
            ),
            out.buttons,
        )
    }
}
