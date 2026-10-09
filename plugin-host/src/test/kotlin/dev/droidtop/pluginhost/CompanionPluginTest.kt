package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.TestPlugins.arr
import dev.droidtop.pluginhost.TestPlugins.obj
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plugins on the companion (docs/plugin-api.md 3 C15, Droidtop/tracker#414 slice C9): a panel's declared abilities,
 * its abilities line, and the Recording state only a declared recorder may raise and clear.
 */
class CompanionPluginTest {
    private fun request(args: JSONObject) = obj("api" to "companion", "version" to 1, "op" to "recording", "args" to args).toString()

    private fun reply(text: String) = PluginReply.parse(text)

    private fun panel(id: String, vararg abilities: String) = TestPlugins.record(
        TestPlugins.manifest(id = id, label = "Acme Rec") {
            it.put("provides", arr(obj("point" to "ui.panel", "companion" to JSONArray(abilities.toList()))))
        },
    )

    private val recorder = panel("acme.rec", CompanionAbilities.RECORDING, CompanionAbilities.KEEP_ON)

    @After fun reset() {
        PluginRecording.clear("acme.rec")
        PluginRecording.clear("acme.other")
    }

    @Test fun `abilities come from the panel entry, unknown ones dropped`() {
        val entry = recorder.manifest.v2.provides.single()
        assertEquals(setOf(CompanionAbilities.RECORDING, CompanionAbilities.KEEP_ON), CompanionAbilities.of(entry))
        assertTrue(CompanionAbilities.of(ProvidedPoint("ui.panel", extra = """{"companion":["fly"]}""")).isEmpty())
        assertTrue(CompanionAbilities.of(null).isEmpty())
        assertTrue(CompanionAbilities.declares(recorder.manifest, CompanionAbilities.RECORDING))
    }

    @Test fun `the abilities line says each ability and the tiles in plain words`() {
        assertEquals(
            "Rows on Game · Shows when it records · Keeps this screen on · 2 tiles to pin",
            CompanionAbilities.line(setOf(CompanionAbilities.KEEP_ON, CompanionAbilities.GAME, CompanionAbilities.RECORDING), 2),
        )
        assertEquals("1 tile to pin", CompanionAbilities.line(emptySet(), 1))
        assertNull(CompanionAbilities.line(emptySet(), 0))
    }

    @Test fun `a declared recorder raises and clears the Recording state`() {
        val env = FakeEnv(recorder)
        val core = BrokerCore("acme.rec", env)
        val started = reply(core.call(request(obj("on" to true))))
        assertTrue(started.ok)
        val shown = PluginRecording.shown(PluginRecording.all.value)!!
        assertEquals("acme.rec", shown.pluginId)
        assertEquals("Acme Rec", shown.label)
        assertEquals(env.now, shown.sinceMs)
        assertTrue(reply(core.call(request(obj("on" to false)))).ok)
        assertNull(PluginRecording.shown(PluginRecording.all.value))
    }

    @Test fun `a start time in the future is taken as now`() {
        val env = FakeEnv(recorder)
        assertTrue(reply(BrokerCore("acme.rec", env).call(request(obj("on" to true, "sinceMs" to env.now + 60_000)))).ok)
        assertEquals(env.now, PluginRecording.all.value.getValue("acme.rec").sinceMs)
    }

    @Test fun `a plugin without the ability, or with its panel switched off, is refused`() {
        val other = panel("acme.other", CompanionAbilities.KEEP_ON)
        assertEquals(PluginErrorCode.PERMISSION_DENIED, reply(BrokerCore("acme.other", FakeEnv(other)).call(request(obj("on" to true)))).code)
        val env = FakeEnv(recorder)
        env.setGrant("acme.rec", PluginPermissions.PROVIDE_PREFIX + "ui.panel", GrantState.DENIED)
        assertEquals(PluginErrorCode.PERMISSION_DENIED, reply(BrokerCore("acme.rec", env).call(request(obj("on" to true)))).code)
        assertTrue(PluginRecording.all.value.isEmpty())
    }

    @Test fun `on must be a boolean`() {
        val r = reply(BrokerCore("acme.rec", FakeEnv(recorder)).call(request(obj("on" to "yes"))))
        assertEquals(PluginErrorCode.INVALID_ARGS, r.code)
    }

    @Test fun `the timer reads minutes and seconds, then hours`() {
        assertEquals("Recording 0:05", PluginRecording.timer(0, 5_000))
        assertEquals("Recording 1:02:05", PluginRecording.timer(0, 3_725_000))
    }
}
