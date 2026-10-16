package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D7, `power.state` (docs/plugin-api.md 3, Droidtop/tracker#553). */
class HostPowerApisTest {
    @Test
    fun `the battery reads as a level, charging and saver`() {
        val json = HostPowerApis.toJson(PowerState(level = 42, charging = true, saver = false))
        assertEquals(42, json.getInt("level"))
        assertTrue(json.getBoolean("charging"))
        assertEquals(setOf("level", "charging", "saver"), json.keys().asSequence().toSet())
        assertTrue("no battery is a null level", HostPowerApis.toJson(PowerState(null, charging = true, saver = false)).isNull("level"))
    }

    @Test
    fun `the level is a percentage of the broadcast's scale`() {
        assertEquals(50, HostPowerApis.level(50, 100))
        assertEquals(75, HostPowerApis.level(3, 4))
        assertNull(HostPowerApis.level(-1, 100))
        assertNull(HostPowerApis.level(5, 0))
    }

    @Test
    fun `the op is under power state, a normal permission`() {
        val ops = HostApis.all().filter { it.api == "power" }
        assertEquals(setOf("get_state"), ops.map { it.op }.toSet())
        assertNull(HostOpNames.check("get_state", readOnly = true))
        assertTrue(ops.all { it.permission == "power.state" })
        assertEquals(PermissionTier.NORMAL, PluginPermissions.find("power.state")!!.tier)
    }
}
