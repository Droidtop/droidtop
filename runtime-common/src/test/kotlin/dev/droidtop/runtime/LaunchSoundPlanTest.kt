package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The launch-static experiment's table (docs/SPEC.md "Launch audio hand-off", tracker#160). */
class LaunchSoundPlanTest {
    @Test
    fun `variant A is the current behaviour, the launch sample at the press and nothing silenced`() {
        val a = LaunchSoundVariant.A
        assertTrue(LaunchSoundPlan.launchSoundAtPress(a))
        assertFalse(LaunchSoundPlan.launchSoundAtDispatch(a))
        assertFalse(LaunchSoundPlan.quietWhileChooser(a))
        assertFalse(LaunchSoundPlan.quietFromPress(a))
    }

    @Test
    fun `variant B plays the launch sample only at the dispatch`() {
        val b = LaunchSoundVariant.B
        assertFalse(LaunchSoundPlan.launchSoundAtPress(b))
        assertTrue(LaunchSoundPlan.launchSoundAtDispatch(b))
        assertFalse(LaunchSoundPlan.quietWhileChooser(b))
    }

    @Test
    fun `variant C plays at the press and silences the preview while the question is up`() {
        val c = LaunchSoundVariant.C
        assertTrue(LaunchSoundPlan.launchSoundAtPress(c))
        assertFalse(LaunchSoundPlan.launchSoundAtDispatch(c))
        assertTrue(LaunchSoundPlan.quietWhileChooser(c))
        assertFalse(LaunchSoundPlan.quietFromPress(c))
    }

    @Test
    fun `variant D plays no droidtop sound from the press on`() {
        val d = LaunchSoundVariant.D
        assertFalse(LaunchSoundPlan.launchSoundAtPress(d))
        assertFalse(LaunchSoundPlan.launchSoundAtDispatch(d))
        assertTrue(LaunchSoundPlan.quietWhileChooser(d))
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
        assertEquals(LaunchSoundVariant.C, LaunchSoundPlan.parse("C"))
    }
}
