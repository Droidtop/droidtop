package dev.droidtop.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Touch on the desktop surface (docs/SPEC.md 6b "Touch on the desktop", Droidtop/tracker#684). */
class DirectTouchGesturesTest {
    private val events = mutableListOf<String>()

    private val gestures = DirectTouchGestures(
        object : DirectTouchGestures.Output {
            override fun moveTo(x: Float, y: Float) { events += "move ${x.toInt()},${y.toInt()}" }
            override fun button(evdevButton: Int, pressed: Boolean) {
                val name = if (evdevButton == EvdevKeys.BTN_LEFT) "left" else if (evdevButton == EvdevKeys.BTN_RIGHT) "right" else "$evdevButton"
                events += "$name ${if (pressed) "down" else "up"}"
            }
            override fun scroll(dxPx: Float, dyPx: Float) { events += "scroll ${dxPx.toInt()},${dyPx.toInt()}" }
        },
        slopPx = 10f,
        longPressMs = 400L,
    )

    @Test
    fun `a tap is a left click where the finger was`() {
        gestures.down(100f, 100f, 0)
        gestures.move(103f, 102f, 50)
        gestures.up(103f, 102f, 120)
        assertEquals(listOf("move 100,100", "left down", "left up"), events)
    }

    @Test
    fun `a finger moved past the slop drags with the left button from where it went down`() {
        gestures.down(100f, 100f, 0)
        gestures.move(130f, 100f, 50)
        gestures.move(160f, 120f, 80)
        gestures.up(170f, 120f, 200)
        assertEquals(
            listOf("move 100,100", "left down", "move 130,100", "move 160,120", "move 170,120", "left up"),
            events,
        )
    }

    @Test
    fun `a finger held still is a right click once its time comes, and lifting adds nothing`() {
        gestures.down(50f, 60f, 1000)
        assertEquals(1400L, gestures.nextTimeoutAtMs())
        gestures.tick(1399)
        assertEquals(listOf("move 50,60"), events)
        gestures.tick(1400)
        assertEquals(listOf("move 50,60", "right down", "right up"), events)
        assertNull(gestures.nextTimeoutAtMs())
        gestures.up(50f, 60f, 1600)
        assertEquals(3, events.size)
    }

    @Test
    fun `a long press is noticed on the next event even when the timer was late`() {
        gestures.down(50f, 60f, 0)
        gestures.up(50f, 60f, 900)
        assertEquals(listOf("move 50,60", "right down", "right up"), events)
    }

    @Test
    fun `two fingers that do not move are a right click`() {
        gestures.down(100f, 100f, 0)
        gestures.secondDown(120f, 100f, 30)
        gestures.pointerUp(150)
        gestures.up(100f, 100f, 160)
        assertEquals(listOf("move 100,100", "right down", "right up"), events)
    }

    @Test
    fun `two fingers that move scroll, and a drag in progress is let go first`() {
        gestures.down(100f, 100f, 0)
        gestures.move(140f, 100f, 20)
        gestures.secondDown(150f, 100f, 40)
        gestures.twoMove(150f, 105f)
        gestures.twoMove(150f, 130f)
        gestures.twoMove(150f, 150f)
        gestures.pointerUp(300)
        gestures.up(150f, 150f, 310)
        assertEquals(
            listOf("move 100,100", "left down", "move 140,100", "left up", "scroll 0,30", "scroll 0,20"),
            events,
        )
    }

    @Test
    fun `a cancelled drag lets go of the button`() {
        gestures.down(0f, 0f, 0)
        gestures.move(50f, 0f, 10)
        gestures.cancel()
        assertEquals(listOf("move 0,0", "left down", "move 50,0", "left up"), events)
    }
}
