package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/** The stick dead zone's thresholds (Droidtop/tracker#723). */
class StickDeadZoneTest {
    @Test
    fun `the default is the shell's long-standing band`() {
        val (press, release) = StickDeadZone.thresholds(StickDeadZone.DEFAULT_PERCENT)
        assertEquals(0.5f, press, 0.0001f)
        assertEquals(0.3f, release, 0.0001f)
    }

    @Test
    fun `a wider dead zone needs more lean, and the band stays sane`() {
        assertEquals(0.7f, StickDeadZone.thresholds(50).first, 0.0001f)
        assertEquals(0.5f, StickDeadZone.thresholds(50).second, 0.0001f)
        assertEquals(0.9f, StickDeadZone.thresholds(90).first, 0.0001f)
        assertEquals(0.5f, StickDeadZone.thresholds(0).first, 0.0001f)
        assertEquals(0.1f, StickDeadZone.thresholds(0).second, 0.0001f)
    }
}
