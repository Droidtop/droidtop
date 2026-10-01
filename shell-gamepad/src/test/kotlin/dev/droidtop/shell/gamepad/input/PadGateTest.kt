package dev.droidtop.shell.gamepad.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The front of the input pipeline (docs/SPEC.md 6e): bounce, the stick and
 * hat as a held D-pad key with its deadzone, analog triggers, and a held
 * Select. Driven with a fake clock and scheduler, no device.
 */
class PadGateTest {

    private class FakeScheduler : PadScheduler {
        var time = 0L
        private val tasks = mutableListOf<Pair<Long, () -> Unit>>()

        override fun now(): Long = time

        override fun after(delayMs: Long, task: () -> Unit): () -> Unit {
            val entry = (time + delayMs) to task
            tasks += entry
            return { tasks.remove(entry) }
        }

        /** Moves the clock to [to], running every task that falls due on the way, in order. */
        fun advanceTo(to: Long) {
            while (true) {
                val next = tasks.filter { it.first <= to }.minByOrNull { it.first } ?: break
                tasks.remove(next)
                time = next.first
                next.second()
            }
            time = to
        }
    }

    private val scheduler = FakeScheduler()
    private val emitted = mutableListOf<PadSyntheticKey>()
    private val gate = PadGateCore(
        scheduler = scheduler,
        longPressMs = 500,
        repeatTimeoutMs = 400,
        repeatDelayMs = 50,
        emit = { emitted += it },
    )

    private val pad = 7

    private fun stick(x: Float = 0f, y: Float = 0f, hatX: Float = 0f, hatY: Float = 0f, lt: Float = 0f, rt: Float = 0f) =
        gate.onAxes(pad, hatX, hatY, x, y, lt, rt, scheduler.time)

    // ---- bounce ----

    @Test
    fun `a press right after the same key's release is a bouncing switch and is dropped whole`() {
        val a = KeyEvent.KEYCODE_BUTTON_A
        assertTrue(gate.onKey(a, down = true, repeatCount = 0, deviceId = pad, fallback = false, now = 0))
        assertTrue(gate.onKey(a, down = false, repeatCount = 0, deviceId = pad, fallback = false, now = 80))
        assertFalse(gate.onKey(a, down = true, repeatCount = 0, deviceId = pad, fallback = false, now = 90))
        assertFalse("its release goes with it", gate.onKey(a, down = false, repeatCount = 0, deviceId = pad, fallback = false, now = 95))
        assertTrue("a real second press gets through", gate.onKey(a, down = true, repeatCount = 0, deviceId = pad, fallback = false, now = 300))
    }

    // ---- the stick ----

    @Test
    fun `the stick is a held D-pad key - one down, repeats at the key-repeat timing, one up`() {
        stick(y = 1f)
        assertEquals(1, emitted.size)
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, emitted[0].keyCode)
        assertTrue(emitted[0].down)
        assertEquals(0, emitted[0].repeatCount)

        scheduler.advanceTo(399)
        assertEquals("no repeat before the repeat timeout", 1, emitted.size)
        scheduler.advanceTo(500)
        // 400, 450, 500
        assertEquals(4, emitted.size)
        assertEquals(listOf(1, 2, 3), emitted.drop(1).map { it.repeatCount })
        assertTrue(emitted.drop(1).all { it.down && it.downTime == 0L })

        stick(y = 0f)
        val last = emitted.last()
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, last.keyCode)
        assertFalse(last.down)
        scheduler.advanceTo(2000)
        assertEquals("released: no more repeats", 5, emitted.size)
    }

    @Test
    fun `inside the deadzone nothing is pressed, and a held stick only lets go once back near centre`() {
        stick(y = 0.4f)
        assertTrue(emitted.isEmpty())
        stick(y = -0.6f)
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, emitted.single().keyCode)
        stick(y = -0.35f)
        assertEquals("still held past the exit threshold", 1, emitted.size)
        stick(y = -0.1f)
        assertFalse(emitted.last().down)
    }

    @Test
    fun `swinging the stick straight across releases one direction and presses the other`() {
        stick(x = -1f)
        stick(x = 1f)
        assertEquals(
            listOf(KeyEvent.KEYCODE_DPAD_LEFT to true, KeyEvent.KEYCODE_DPAD_LEFT to false, KeyEvent.KEYCODE_DPAD_RIGHT to true),
            emitted.map { it.keyCode to it.down },
        )
    }

    @Test
    fun `the hat wins over the stick`() {
        stick(y = 1f, hatY = -1f)
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, emitted.single().keyCode)
    }

    @Test
    fun `a direction the device also sends as a real key is not translated again, per key`() {
        // The Retroid Pocket 5's shape (Droidtop/tracker#1/#43): Down is a
        // real key, Up only comes from the hat.
        gate.onKey(KeyEvent.KEYCODE_DPAD_DOWN, down = true, repeatCount = 0, deviceId = pad, fallback = false, now = 0)
        gate.onKey(KeyEvent.KEYCODE_DPAD_DOWN, down = false, repeatCount = 0, deviceId = pad, fallback = false, now = 50)
        stick(hatY = 1f)
        assertTrue("Down is the real key's job", emitted.isEmpty())
        stick(hatY = 0f)
        stick(hatY = -1f)
        assertEquals("Up still comes from the hat", KeyEvent.KEYCODE_DPAD_UP, emitted.single().keyCode)
    }

    @Test
    fun `the platform's own joystick D-pad keys do not count as a real key`() {
        gate.onKey(KeyEvent.KEYCODE_DPAD_UP, down = true, repeatCount = 0, deviceId = pad, fallback = true, now = 0)
        stick(hatY = -1f)
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, emitted.single().keyCode)
    }

    // ---- triggers ----

    @Test
    fun `an analog trigger presses and releases its button once, without repeating`() {
        stick(rt = 0.9f)
        scheduler.advanceTo(3000)
        stick(rt = 0f)
        assertEquals(
            listOf(KeyEvent.KEYCODE_BUTTON_R2 to true, KeyEvent.KEYCODE_BUTTON_R2 to false),
            emitted.map { it.keyCode to it.down },
        )
    }

    // ---- Select ----

    private fun select(down: Boolean, repeat: Int = 0, at: Long) =
        gate.onKey(KeyEvent.KEYCODE_BUTTON_SELECT, down, repeat, pad, fallback = false, now = at)

    @Test
    fun `a tapped Select reaches the screen as one press, on release`() {
        assertFalse(select(down = true, at = 0))
        scheduler.advanceTo(120)
        assertFalse(select(down = false, at = 120))
        assertEquals(
            listOf(KeyEvent.KEYCODE_BUTTON_SELECT to true, KeyEvent.KEYCODE_BUTTON_SELECT to false),
            emitted.map { it.keyCode to it.down },
        )
    }

    @Test
    fun `a held Select is one R2 press, and nothing of the Select itself gets through`() {
        assertFalse(select(down = true, at = 0))
        scheduler.advanceTo(450)
        assertFalse(select(down = true, repeat = 1, at = 450))
        scheduler.advanceTo(600)
        assertFalse(select(down = true, repeat = 2, at = 600))
        assertFalse(select(down = false, at = 900))
        assertEquals(
            listOf(KeyEvent.KEYCODE_BUTTON_R2 to true, KeyEvent.KEYCODE_BUTTON_R2 to false),
            emitted.map { it.keyCode to it.down },
        )
    }

    @Test
    fun `a Select release whose press this window never saw is passed on untouched`() {
        assertTrue(select(down = false, at = 10))
        assertTrue(emitted.isEmpty())
    }

    // ---- the axis rule itself (ported from the stick translator it replaces) ----

    private val up = 1
    private val down = 2
    private val realOnDownOnly: (Int) -> Boolean = { it == down }

    @Test
    fun `axis - per-key suppression never blinds the other direction`() {
        assertEquals(up, axisTargetKey(-1f, null, up, down, realOnDownOnly))
        assertNull(axisTargetKey(1f, null, up, down, realOnDownOnly))
    }

    @Test
    fun `axis - hysteresis holds the current key between the two thresholds`() {
        assertEquals(up, axisTargetKey(-0.4f, up, up, down) { false })
        assertNull(axisTargetKey(-0.4f, null, up, down) { false })
        assertNull(axisTargetKey(0f, up, up, down) { false })
    }
}
