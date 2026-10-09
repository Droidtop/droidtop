package dev.droidtop.runtime.systemstatus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** System > Power's probe (PowerProbe.kt, Droidtop/tracker#414 slice C16). Setting names here are made-up examples. */
class PowerProbeTest {
    private val system = """
        screen_brightness=120
        fan_mode=2
        example_fan_speed_level=3
        font_scale=1.0
        charge_limit_enabled=1
        battery_protect_charging=0
        fancy_animation=1
    """.trimIndent()

    @Test fun `fan and charge-limit keys are found, nothing else`() {
        val probe = PowerProbe.parseSettings("system", system)
        assertEquals(setOf("system/fan_mode", "system/example_fan_speed_level"), probe.fan.keys)
        assertEquals("2", probe.fan["system/fan_mode"])
        assertEquals(setOf("system/charge_limit_enabled", "system/battery_protect_charging"), probe.charge.keys)
        // "fancy" is not a fan.
        assertFalse(probe.fan.keys.any { it.contains("fancy") })
    }

    @Test fun `the probe result decides what the card shows`() {
        val readable = PowerProbe.Cluster("policy0", "0 1 2 3", 1_804_800, 2_016_000, "schedutil", listOf("schedutil", "performance"), listOf(1_000_000, 2_016_000))
        val hidden = readable.copy(policy = "policy4", curKHz = null)
        val nothing = PowerProbe.Probe(emptyMap(), emptyMap())
        val v = PowerProbe.visible(nothing, listOf(readable, hidden), gpuMHz = null, rootTuning = false)
        assertTrue(v.fan.isEmpty() && v.chargeLimit.isEmpty())
        assertEquals(listOf(readable), v.clusters)
        assertNull(v.gpuMHz)
        assertFalse(v.tuning)
        assertTrue(PowerProbe.visible(PowerProbe.parseSettings("system", system), listOf(readable), 585, rootTuning = true).tuning)
    }

    @Test fun `the device's own page first, else Android's, else none`() {
        val resolvable = setOf("vendor.fan", "android.battery")
        assertEquals("vendor.fan", PowerProbe.pageFor(listOf("missing", "vendor.fan"), "android.battery") { it in resolvable })
        assertEquals("android.battery", PowerProbe.pageFor(listOf("missing"), "android.battery") { it in resolvable })
        assertNull(PowerProbe.pageFor(listOf("missing"), "also.missing") { it in resolvable })
    }

    @Test fun `only cpufreq's own governor and clock files are written, with sane values`() {
        assertEquals(
            listOf("sh", "-c", "echo performance > /sys/devices/system/cpu/cpufreq/policy4/scaling_governor"),
            PowerProbe.writeCommand("policy4", "scaling_governor", "performance"),
        )
        assertNull(PowerProbe.writeCommand("policy4", "scaling_governor", "x; reboot"))
        assertNull(PowerProbe.writeCommand("../../power", "scaling_governor", "performance"))
        assertNull(PowerProbe.writeCommand("policy0", "scaling_min_freq", "1"))
        assertNull(PowerProbe.writeCommand("policy0", "scaling_max_freq", "fast"))
    }
}
