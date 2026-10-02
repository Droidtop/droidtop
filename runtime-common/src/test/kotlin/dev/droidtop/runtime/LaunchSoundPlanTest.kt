package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The launch-static experiment's table and the modal-layer rule (docs/SPEC.md "Launch audio hand-off", tracker#160). */
class LaunchSoundPlanTest {
    @Test
    fun `variant A is the current behaviour, the launch sample at the press`() {
        val a = LaunchSoundVariant.A
        assertTrue(LaunchSoundPlan.launchSoundAtPress(a))
        assertFalse(LaunchSoundPlan.launchSoundAtDispatch(a))
        assertFalse(LaunchSoundPlan.quietFromPress(a))
    }

    @Test
    fun `variant B plays the launch sample only at the dispatch`() {
        val b = LaunchSoundVariant.B
        assertFalse(LaunchSoundPlan.launchSoundAtPress(b))
        assertTrue(LaunchSoundPlan.launchSoundAtDispatch(b))
        assertFalse(LaunchSoundPlan.quietFromPress(b))
    }

    @Test
    fun `variant D plays no droidtop sound from the press on`() {
        val d = LaunchSoundVariant.D
        assertFalse(LaunchSoundPlan.launchSoundAtPress(d))
        assertFalse(LaunchSoundPlan.launchSoundAtDispatch(d))
        assertTrue(LaunchSoundPlan.quietFromPress(d))
    }

    @Test
    fun `the launch sample plays at most once for every variant`() {
        for (variant in LaunchSoundVariant.entries) {
            assertFalse(
                "$variant would play the launch sample twice",
                LaunchSoundPlan.launchSoundAtPress(variant) && LaunchSoundPlan.launchSoundAtDispatch(variant),
            )
        }
    }

    @Test
    fun `a stored value that is missing or unknown means variant A`() {
        assertEquals(LaunchSoundVariant.A, LaunchSoundPlan.parse(null))
        assertEquals(LaunchSoundVariant.A, LaunchSoundPlan.parse("E"))
        assertEquals(LaunchSoundVariant.A, LaunchSoundPlan.parse("C"))
        assertEquals(LaunchSoundVariant.D, LaunchSoundPlan.parse("D"))
    }

    @Test
    fun `nothing plays beneath an open layer, and a hand-off supersedes the rule`() {
        assertTrue(LaunchSoundPlan.silenced(layerOpen = true, handedOff = false))
        assertFalse(LaunchSoundPlan.silenced(layerOpen = false, handedOff = false))
        assertFalse(LaunchSoundPlan.silenced(layerOpen = true, handedOff = true))
        assertFalse(LaunchSoundPlan.silenced(layerOpen = false, handedOff = true))
    }
}
