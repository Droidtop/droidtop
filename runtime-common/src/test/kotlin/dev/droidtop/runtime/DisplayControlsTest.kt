package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The companion screen and displays (DisplayControls.kt, Droidtop/tracker#414 slice C14). */
class DisplayControlsTest {
    private fun screen(id: Int, name: String, w: Int, h: Int, builtIn: Boolean) =
        ScreenFacts(id, "u$id", name, if (builtIn) ScreenClass.BUILT_IN else ScreenClass.EXTERNAL, w, h)

    private val rp5 = screen(0, "Built-in Screen", 1080, 1920, builtIn = true)
    private val addOn = screen(9, "DP Screen", 1080, 1920, builtIn = false)
    private val monitorA = screen(11, "DELL U2720Q", 3840, 2160, builtIn = false)
    private val monitorB = screen(12, "DELL U2720Q", 3840, 2160, builtIn = false)

    @Test fun `the set key ignores display ids and order, and two identical monitors share a key`() {
        assertEquals(CompanionScreens.setKey(listOf(rp5, addOn)), CompanionScreens.setKey(listOf(addOn.copy(displayId = 4), rp5)))
        assertEquals(CompanionScreens.screenKey(monitorA), CompanionScreens.screenKey(monitorB))
        assertEquals(CompanionScreens.setKey(listOf(rp5, monitorA)), CompanionScreens.setKey(listOf(rp5, monitorB)))
        // A set with one monitor is not the set with two.
        assertTrue(CompanionScreens.setKey(listOf(rp5, monitorA)) != CompanionScreens.setKey(listOf(rp5, monitorA, monitorB)))
    }

    @Test fun `a Thor-shaped set picks the other built-in touch screen, alone and with a monitor`() {
        val top = screen(0, "Built-in Screen", 1080, 1920, builtIn = true)
        val bottom = screen(2, "Built-in Screen 2", 1080, 1240, builtIn = true)
        assertEquals(2, CompanionScreens.pick(listOf(top, bottom), mainDisplayId = 0, chosenKey = null))
        val monitor = screen(5, "LG", 1920, 1080, builtIn = false)
        assertEquals(2, CompanionScreens.pick(listOf(top, monitor, bottom), mainDisplayId = 0, chosenKey = null))
        // The main screen moved to the monitor: the companion still takes a built-in screen.
        assertEquals(0, CompanionScreens.pick(listOf(top, monitor, bottom), mainDisplayId = 5, chosenKey = null))
        assertNull(CompanionScreens.pick(listOf(top), mainDisplayId = 0, chosenKey = null))
    }

    @Test fun `a remembered choice wins while its screen is here`() {
        val set = listOf(rp5, addOn, monitorA)
        assertEquals(9, CompanionScreens.pick(set, 0, null))
        assertEquals(11, CompanionScreens.pick(set, 0, CompanionScreens.screenKey(monitorA)))
        // Unplugged: the default again.
        assertEquals(9, CompanionScreens.pick(listOf(rp5, addOn), 0, CompanionScreens.screenKey(monitorA)))
        // Never the main screen, even if chosen.
        assertEquals(9, CompanionScreens.pick(set, 0, CompanionScreens.screenKey(rp5)))
    }

    @Test fun `companion brightness stops at the floor, only off goes lower`() {
        assertEquals(DisplayControls.FLOOR, DisplayControls.clampCompanion(0f), 0.0001f)
        assertEquals(DisplayControls.FLOOR, DisplayControls.windowLevel(CompanionIdle.State.DIM, 0.2f), 0.0001f)
        assertEquals(0.8f, DisplayControls.windowLevel(CompanionIdle.State.ON, 0.8f), 0.0001f)
        assertTrue(DisplayControls.windowLevel(CompanionIdle.State.OFF, 1f) < DisplayControls.FLOOR)
    }

    @Test fun `idle dims after two minutes and goes off after five, unless exempt or switched off`() {
        assertEquals(CompanionIdle.State.ON, CompanionIdle.state(60_000, enabled = true, exempt = false))
        assertEquals(CompanionIdle.State.DIM, CompanionIdle.state(120_000, enabled = true, exempt = false))
        assertEquals(CompanionIdle.State.OFF, CompanionIdle.state(300_000, enabled = true, exempt = false))
        assertEquals(CompanionIdle.State.ON, CompanionIdle.state(600_000, enabled = true, exempt = true))
        assertEquals(CompanionIdle.State.ON, CompanionIdle.state(600_000, enabled = false, exempt = false))
        assertNull(CompanionIdle.nextChangeInMs(0, enabled = true, exempt = true))
        assertEquals(110_000L, CompanionIdle.nextChangeInMs(0, enabled = true, exempt = false))
        assertTrue(CompanionIdle.warnNow(115_000, enabled = true, exempt = false))
        assertFalse(CompanionIdle.warnNow(60_000, enabled = true, exempt = false))
    }

    @Test fun `Android's recommended timeout stretches the idle times`() {
        assertEquals(CompanionIdle.State.ON, CompanionIdle.state(150_000, enabled = true, exempt = false, recommendedMs = 180_000))
        assertEquals(180_000L to 360_000L, CompanionIdle.timeouts(180_000))
    }

    @Test fun `a message brings an off or dimmed screen back to dimmed, except in Kid and Kiosk`() {
        val since = CompanionIdle.afterMessage(CompanionIdle.State.OFF, restricted = false)!!
        assertEquals(CompanionIdle.State.DIM, CompanionIdle.state(since, enabled = true, exempt = false))
        assertNull(CompanionIdle.afterMessage(CompanionIdle.State.OFF, restricted = true))
        assertNull(CompanionIdle.afterMessage(CompanionIdle.State.ON, restricted = false))
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
