package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The companion screen and displays (DisplayControls.kt, Droidtop/tracker#414 slice C14). */
class DisplayControlsTest {
    @Test fun `companion brightness stops at the floor, only off goes lower`() {
        assertEquals(DisplayControls.FLOOR, DisplayControls.clampCompanion(0f), 0.0001f)
        assertEquals(DisplayControls.FLOOR, DisplayControls.windowLevel(CompanionIdle.State.DIM, 0.2f), 0.0001f)
        assertEquals(0.8f, DisplayControls.windowLevel(CompanionIdle.State.ON, 0.8f), 0.0001f)
        assertTrue(DisplayControls.windowLevel(CompanionIdle.State.OFF, 1f) < DisplayControls.FLOOR)
    }

    @Test fun `idle dims after two minutes and goes off after five, unless exempt or switched off`() {
        assertEquals(CompanionIdle.State.ON, CompanionIdle.state(60_000, dimAfterMs = CompanionIdle.DIM_AFTER_MS, exempt = false))
        assertEquals(CompanionIdle.State.DIM, CompanionIdle.state(120_000, dimAfterMs = CompanionIdle.DIM_AFTER_MS, exempt = false))
        assertEquals(CompanionIdle.State.OFF, CompanionIdle.state(300_000, dimAfterMs = CompanionIdle.DIM_AFTER_MS, exempt = false))
        assertEquals(CompanionIdle.State.ON, CompanionIdle.state(600_000, dimAfterMs = CompanionIdle.DIM_AFTER_MS, exempt = true))
        assertEquals(CompanionIdle.State.ON, CompanionIdle.state(600_000, dimAfterMs = 0L, exempt = false))
        assertNull(CompanionIdle.nextChangeInMs(0, dimAfterMs = CompanionIdle.DIM_AFTER_MS, exempt = true))
        assertEquals(110_000L, CompanionIdle.nextChangeInMs(0, dimAfterMs = CompanionIdle.DIM_AFTER_MS, exempt = false))
        assertTrue(CompanionIdle.warnNow(115_000, dimAfterMs = CompanionIdle.DIM_AFTER_MS, exempt = false))
        assertFalse(CompanionIdle.warnNow(60_000, dimAfterMs = CompanionIdle.DIM_AFTER_MS, exempt = false))
    }

    @Test fun `the chosen dim time sets when it dims and goes off, and Never keeps it on`() {
        assertEquals(CompanionIdle.State.DIM, CompanionIdle.state(60_000, dimAfterMs = 60_000, exempt = false))
        assertEquals(CompanionIdle.State.OFF, CompanionIdle.state(60_000 + CompanionIdle.OFF_GAP_MS, dimAfterMs = 60_000, exempt = false))
        assertEquals(CompanionIdle.State.ON, CompanionIdle.state(3_600_000, dimAfterMs = 0L, exempt = false))
        assertEquals("Never", CompanionIdle.dimLabel(0))
        assertEquals("After 1 minute", CompanionIdle.dimLabel(60_000))
        assertEquals("After 10 minutes", CompanionIdle.dimLabel(600_000))
        assertTrue(CompanionIdle.DIM_AFTER_MS in CompanionIdle.DIM_CHOICES_MS)
    }

    @Test fun `Android's recommended timeout stretches the idle times`() {
        assertEquals(CompanionIdle.State.ON, CompanionIdle.state(150_000, dimAfterMs = CompanionIdle.DIM_AFTER_MS, exempt = false, recommendedMs = 180_000))
        assertEquals(180_000L to 360_000L, CompanionIdle.timeouts(CompanionIdle.DIM_AFTER_MS, 180_000))
    }

    @Test fun `a message brings an off or dimmed screen back to dimmed, except in Kid and Kiosk`() {
        val since = CompanionIdle.afterMessage(CompanionIdle.State.OFF, restricted = false, dimAfterMs = CompanionIdle.DIM_AFTER_MS)!!
        assertEquals(CompanionIdle.State.DIM, CompanionIdle.state(since, dimAfterMs = CompanionIdle.DIM_AFTER_MS, exempt = false))
        assertNull(CompanionIdle.afterMessage(CompanionIdle.State.OFF, restricted = true, dimAfterMs = CompanionIdle.DIM_AFTER_MS))
        assertNull(CompanionIdle.afterMessage(CompanionIdle.State.ON, restricted = false, dimAfterMs = CompanionIdle.DIM_AFTER_MS))
    }

    @Test fun `waking follows the chosen tap`() {
        assertFalse(CompanionIdle.wakes(CompanionIdle.Wake.DOUBLE_TAP, 1))
        assertTrue(CompanionIdle.wakes(CompanionIdle.Wake.DOUBLE_TAP, 2))
        assertTrue(CompanionIdle.wakes(CompanionIdle.Wake.SINGLE_TAP, 1))
        assertFalse(CompanionIdle.wakes(CompanionIdle.Wake.NONE, 3))
        assertEquals(CompanionIdle.Wake.DOUBLE_TAP, CompanionIdle.Wake.of("nonsense"))
    }

    @Test fun `the refresh rate command names the display`() {
        assertEquals(
            listOf("cmd", "display", "set-user-preferred-display-mode", "1920", "1080", "60.0", "2"),
            DisplayControls.refreshCommand(2, 1920, 1080, 60f),
        )
    }
}
