package dev.droidtop.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trackpad's arithmetic: the acceleration curve, the millimetre scale,
 * and the step quantiser the Handheld shell navigates by. All three are
 * things a device can only tell you feel wrong, never why -- so they are
 * pinned here instead.
 */
class TrackpadMathTest {

    @Test
    fun `the acceleration curve is flat at both ends and monotonic between`() {
        assertEquals(PointerAcceleration.MIN_FACTOR, PointerAcceleration.factor(0f), 0.0001f)
        assertEquals(
            PointerAcceleration.MIN_FACTOR,
            PointerAcceleration.factor(PointerAcceleration.LOW_MM_PER_S),
            0.0001f,
        )
        assertEquals(
            PointerAcceleration.MAX_FACTOR,
            PointerAcceleration.factor(PointerAcceleration.HIGH_MM_PER_S),
            0.0001f,
        )
        assertEquals(PointerAcceleration.MAX_FACTOR, PointerAcceleration.factor(5000f), 0.0001f)

        var previous = 0f
        var speed = 0f
        while (speed <= 400f) {
            val factor = PointerAcceleration.factor(speed)
            assertTrue("factor fell at $speed", factor >= previous - 0.0001f)
            previous = factor
            speed += 5f
        }
    }

    @Test
    fun `the midpoint of the ramp is the midpoint of the two plateaus`() {
        val middleSpeed = (PointerAcceleration.LOW_MM_PER_S + PointerAcceleration.HIGH_MM_PER_S) / 2f
        val expected = (PointerAcceleration.MIN_FACTOR + PointerAcceleration.MAX_FACTOR) / 2f
        assertEquals(expected, PointerAcceleration.factor(middleSpeed), 0.0001f)
    }

    @Test
    fun `user speed doubles and halves the whole curve, not just one end`() {
        for (speed in listOf(0f, 100f, 200f, 1000f)) {
            val neutral = PointerAcceleration.factor(speed, 0f)
            assertEquals(neutral * 2f, PointerAcceleration.factor(speed, 1f), 0.0001f)
            assertEquals(neutral / 2f, PointerAcceleration.factor(speed, -1f), 0.0001f)
        }
        // Out-of-range settings are clamped, not extrapolated.
        assertEquals(
            PointerAcceleration.factor(100f, 1f),
            PointerAcceleration.factor(100f, 9f),
            0.0001f,
        )
    }

    @Test
    fun `a plausible reported dpi is used and an implausible one is not`() {
        // 400 dpi reported by a panel that also says densityDpi 420: the
        // reported value is the more accurate of the two and is kept.
        assertEquals(25.4f / 400f, MmScale.mmPerPx(400f, 420), 0.00001f)

        // The failure modes real devices actually exhibit.
        assertEquals(25.4f / 420f, MmScale.mmPerPx(0f, 420), 0.00001f)
        assertEquals(25.4f / 420f, MmScale.mmPerPx(1f, 420), 0.00001f)
        assertEquals(25.4f / 420f, MmScale.mmPerPx(Float.NaN, 420), 0.00001f)
        assertEquals(25.4f / 420f, MmScale.mmPerPx(99999f, 420), 0.00001f)
    }

    @Test
    fun `an implausible densityDpi is clamped rather than trusted`() {
        assertEquals(25.4f / MmScale.MIN_PLAUSIBLE_DPI, MmScale.mmPerPx(0f, 10), 0.00001f)
        assertEquals(25.4f / MmScale.MAX_PLAUSIBLE_DPI, MmScale.mmPerPx(0f, 99999), 0.00001f)
    }

    @Test
    fun `the gain crosses the output once per reference travel at unity`() {
        val outputWidth = 1920
        val gain = outputWidth / TRACKPAD_TRAVEL_MM_PER_SCREEN_WIDTH
        assertEquals(outputWidth.toFloat(), gain * TRACKPAD_TRAVEL_MM_PER_SCREEN_WIDTH, 0.01f)
    }

    @Test
    fun `the stepper emits one step per step length and keeps the remainder`() {
        val stepper = DirectionalStepper(stepMm = 10f)
        assertEquals(emptyList<NavKey>(), stepper.accumulate(4f, 0f))
        assertEquals(listOf(NavKey.RIGHT), stepper.accumulate(7f, 0f))
        // 1 mm of remainder carried, so another 9 completes the next step
        // rather than needing a full 10.
        assertEquals(listOf(NavKey.RIGHT), stepper.accumulate(9f, 0f))
    }

    @Test
    fun `a long swipe emits every step it earned, not just one`() {
        val stepper = DirectionalStepper(stepMm = 10f)
        assertEquals(listOf(NavKey.DOWN, NavKey.DOWN, NavKey.DOWN), stepper.accumulate(0f, 35f))
    }

    @Test
    fun `the dominant axis is locked so a mostly-horizontal swipe emits no vertical steps`() {
        val stepper = DirectionalStepper(stepMm = 10f)
        // 40 mm right with 6 mm of drift down: without the axis lock the
        // drift accumulates into a stray row change.
        assertEquals(
            listOf(NavKey.RIGHT, NavKey.RIGHT, NavKey.RIGHT, NavKey.RIGHT),
            stepper.accumulate(40f, 6f),
        )
        assertEquals(emptyList<NavKey>(), stepper.accumulate(0f, 9f))
    }

    @Test
    fun `reset releases the axis lock for the next stroke`() {
        val stepper = DirectionalStepper(stepMm = 10f)
        assertEquals(listOf(NavKey.RIGHT), stepper.accumulate(12f, 0f))
        stepper.reset()
        assertEquals(listOf(NavKey.DOWN), stepper.accumulate(0f, 12f))
    }

    @Test
    fun `focus navigation acts on button release only, so a tap fires once`() {
        val keys = mutableListOf<NavKey>()
        val sink = FocusNavTrackpadSink(emit = { keys += it })
        sink.onButton(EvdevKeys.BTN_LEFT, pressed = true)
        sink.onButton(EvdevKeys.BTN_LEFT, pressed = false)
        sink.onButton(EvdevKeys.BTN_RIGHT, pressed = true)
        sink.onButton(EvdevKeys.BTN_RIGHT, pressed = false)
        sink.onButton(EvdevKeys.BTN_MIDDLE, pressed = false)
        assertEquals(listOf(NavKey.CONFIRM, NavKey.BACK), keys)
    }
}
