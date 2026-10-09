package dev.droidtop.runtime

import dev.droidtop.runtime.systemstatus.BatteryEstimate
import dev.droidtop.runtime.systemstatus.BatteryEstimator
import dev.droidtop.runtime.systemstatus.BatteryReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Time to empty or full from battery broadcasts (BatterySampler.kt). */
class BatteryEstimatorTest {
    private fun r(sec: Int, percent: Int, charging: Boolean = false, ua: Long? = 1_000_000, uah: Long? = 2_000_000) =
        BatteryReading(sec * 1000L, percent, charging, ua, uah, 3_850, 300)

    @Test fun `no estimate before enough readings over enough time`() {
        assertNull(BatteryEstimator.estimate(emptyList()))
        assertNull(BatteryEstimator.estimate(listOf(r(0, 50), r(10, 50))))
        // Three readings but only 30 seconds apart.
        assertNull(BatteryEstimator.estimate(listOf(r(0, 50), r(15, 50), r(30, 50))))
    }

    @Test fun `the estimate is the remaining charge over the current`() {
        // 2,000,000 uAh at 1,000,000 uA: two hours.
        assertEquals(BatteryEstimate(120, toFull = false), BatteryEstimator.estimate(listOf(r(0, 50), r(30, 50), r(60, 50))))
        // Charging at 50%: as much again to full.
        val charging = listOf(r(0, 50, true), r(30, 50, true), r(60, 50, true))
        assertEquals(BatteryEstimate(120, toFull = true), BatteryEstimator.estimate(charging))
        assertEquals("2 h 0 min to full", BatteryEstimator.estimate(charging)!!.label)
    }

    @Test fun `a spike is smoothed and plugging in starts over`() {
        val steady = listOf(r(0, 50), r(30, 50), r(60, 50), r(90, 50))
        val spiked = steady + r(100, 50, ua = 4_000_000)
        val minutes = BatteryEstimator.estimate(spiked)!!.minutes
        // A single four-times spike moves the estimate far less than to a quarter (30 minutes).
        assertTrue("got $minutes", minutes in 60..119)
        // The charger plugged in: only the new run counts, and it is too short yet.
        assertNull(BatteryEstimator.estimate(steady + r(95, 50, true)))
    }

    @Test fun `without a current the level's slope stands in`() {
        val readings = listOf(r(0, 50, ua = null), r(60, 49, ua = null), r(120, 48, ua = null))
        // One percent a minute from 48%: 48 minutes.
        assertEquals(BatteryEstimate(48, toFull = false), BatteryEstimator.estimate(readings))
        assertNull(BatteryEstimator.estimate(listOf(r(0, 50, ua = null), r(60, 50, ua = null), r(120, 50, ua = null))))
    }

    @Test fun `watts come from current and voltage`() {
        assertEquals(3.85, r(0, 50).watts!!, 0.001)
        assertNull(r(0, 50, ua = null).watts)
    }
}
