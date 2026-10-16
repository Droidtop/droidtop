package dev.droidtop.pluginhost

import dev.droidtop.net.peer.PairedDevices
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D21, paired devices (docs/plugin-api.md 3, Droidtop/tracker#556, #553): what a plugin reads and what it may announce. */
class HostDevicesApisTest {
    @Test
    fun `a plugin reads each device's kind, state and only its own counterpart's value`() {
        val devices = listOf(
            PairedDevices.Device("ab".repeat(32), "Living room PC", "computer", 1_792_000_000_000L, online = true, announced = JSONObject().put("syncthing", "MFZWI3D")),
            PairedDevices.Device("cd".repeat(32), "Second handheld", "handheld", 1_792_000_100_000L, online = false, announced = null),
        )
        val json = HostDevicesApis.toJson(devices)
        val pc = json.getJSONObject(0)
        assertEquals("computer", pc.getString("kind"))
        assertTrue(pc.getBoolean("online"))
        assertEquals(1_792_000_000_000L, pc.getLong("pairedAt"))
        assertEquals("MFZWI3D", pc.getJSONObject("announced").getString("syncthing"))
        val handheld = json.getJSONObject(1)
        assertEquals("handheld", handheld.getString("kind"))
        assertTrue("nothing announced reads as null", handheld.isNull("announced"))
        // Never an address or a key: only these fields.
        assertEquals(setOf("id", "name", "kind", "pairedAt", "online", "announced"), pc.keys().asSequence().toSet())
    }

    @Test
    fun `a value is announced only within the agent's limits`() {
        assertNull(PairedDevices.problem("gamegrab.syncthing", JSONObject().put("syncthing", "MFZWI3D")))
        assertNull("withdrawing is always allowed", PairedDevices.problem("gamegrab.syncthing", null))
        assertNotNull(PairedDevices.problem("gamegrab.syncthing", JSONObject().put("big", "x".repeat(PairedDevices.MAX_VALUE_BYTES))))
        assertNotNull(PairedDevices.problem("bad id!", JSONObject()))
    }

    @Test
    fun `both ops are under devices paired and the event is known`() {
        val ops = HostApis.all().filter { it.api == "devices" }
        assertEquals(setOf("list_paired", "announce"), ops.map { it.op }.toSet())
        assertTrue(ops.all { it.permission == "devices.paired" })
        assertEquals(PermissionTier.DANGEROUS, PluginPermissions.find("devices.paired")!!.tier)
        assertEquals(PluginEvent.DEVICES_CHANGED, PluginEvent.fromId("devices.changed"))
        assertFalse(HostOpNames.check("list_paired", readOnly = true) != null)
    }
}
