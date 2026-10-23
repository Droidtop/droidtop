package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** docs/plugin-api.md 3 A7a (Droidtop/tracker#547): saves both ways with an app's plugin, and the engine games it is offered. */
class HostSavesOpsTest {
    @Test
    fun `saves where and report_changed need saves exchange, a normal permission`() {
        val ops = HostApis.all().filter { it.api == "saves" }
        assertEquals(setOf("where", "report_changed"), ops.map { it.op }.toSet())
        ops.forEach { assertNull(HostOpNames.check(it.op)) }
        assertEquals(setOf("saves.exchange"), ops.map { it.permission }.toSet())
        assertEquals(PermissionTier.NORMAL, PluginPermissions.find("saves.exchange")!!.tier)
    }

    @Test
    fun `the engine games are read with library read`() {
        val op = HostApis.find("library.read", "list_engine_games")
        assertNotNull(op)
        assertEquals("library.read", op!!.permission)
        assertNull(HostOpNames.check(op.op, readOnly = true))
    }

    @Test
    fun `saves roots is the medium risk point of tracker 546, and saves locations is gone`() {
        assertEquals(PointRisk.MEDIUM, ExtensionPoints.find("saves.roots")!!.risk)
        assertEquals(null, ExtensionPoints.find("saves.locations"))
    }
}
