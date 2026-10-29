package dev.droidtop.pluginhost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/plugin-api.md 3 A7b (Droidtop/tracker#752): an app's plugin lists its engine cores, and says when they change. */
class HostEnginesOpsTest {
    private fun app(provides: Boolean, kind: String = "app_interface") = TestPlugins.record(
        PluginManifest.fromJson(
            JSONObject(
                """{"id": "dev.enginehost", "origin": "enginehost", "label": "Enginehost", "version": "1.5.0", "kind": "$kind",
                   "contractVersion": 3,
                   "app": {"package": "dev.enginehost", "service": "dev.enginehost.droidtop.DroidtopInterfaceService"},
                   "provides": [${if (provides) """{"point": "engines.cores", "version": 1}""" else """{"point": "saves.roots", "version": 1}"""}]}""",
            ),
        )!!,
    )

    private fun report(record: PluginRecord): Pair<PluginReply, List<String>> {
        val told = mutableListOf<String>()
        val env = object : BrokerEnvironment by FakeEnv(record) {
            override fun enginesChanged(pluginId: String): Boolean = told.add(pluginId)
        }
        val reply = PluginReply.parse(
            BrokerCore(record.manifest.id, env).call(
                JSONObject().put("api", "engines").put("version", 1).put("op", "report_changed").put("args", JSONObject()).toString(),
            ),
        )
        return reply to told
    }

    @Test
    fun `engines cores is a low risk point and report_changed follows the naming rule`() {
        assertEquals(PointRisk.LOW, ExtensionPoints.find("engines.cores")!!.risk)
        val op = HostApis.find("engines", "report_changed")!!
        assertNull(HostOpNames.check(op.op))
    }

    @Test
    fun `an app's plugin that lists engine cores says they changed, and droidtop asks it again`() {
        val (reply, told) = report(app(provides = true))
        assertTrue(reply.message, reply.ok)
        assertTrue(reply.data.getBoolean("accepted"))
        assertEquals(listOf("dev.enginehost"), told)
    }

    @Test
    fun `a plugin that does not provide engines cores is refused`() {
        val (reply, told) = report(app(provides = false))
        assertFalse(reply.ok)
        assertEquals(PluginErrorCode.PERMISSION_DENIED, reply.code)
        assertTrue(told.isEmpty())
    }
}
