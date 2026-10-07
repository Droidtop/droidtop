package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.TestPlugins.arr
import dev.droidtop.pluginhost.TestPlugins.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jobs from points that have no contract 1 capability, and events under either manifest field or id. */
class PluginJobAndEventRoutingTest {
    private fun envelope(point: String): Map<String, String> =
        mapOf("call" to PluginCall(callId = "c-1", deadlineMs = 1000L, point = point, version = 1, op = "install", caller = org.json.JSONObject().put("kind", "host"), surface = org.json.JSONObject(), args = org.json.JSONObject()).toJson().toString())

    private val panelPlugin = TestPlugins.record(
        TestPlugins.manifest(id = "acme.panel") { it.put("provides", arr(obj("point" to "ui.panel"))) },
    )

    @Test
    fun `a point with no contract 1 capability still gets a job capability`() {
        assertEquals(PluginCapability.SETTINGS_ROWS, LegacyManifest.jobCapabilityFor("ui.panel"))
        assertEquals(PluginCapability.SETTINGS_ROWS, LegacyManifest.jobCapabilityFor("ui.game_section"))
        assertEquals(PluginCapability.ACQUIRE_CONTENT, LegacyManifest.jobCapabilityFor("library.sources"))
    }

    @Test
    fun `a job is checked as the point its envelope names, not the capability that labels it`() {
        val snap = PluginGrants.Snapshot()
        val label = LegacyManifest.jobCapabilityFor("ui.panel")
        assertNull("the panel is declared, so its job may start", PluginGrants.jobRefusal(panelPlugin, snap, label, envelope("ui.panel")))
        // ui.settings is the label's own point: the plugin never declared it, and the panel's job must not be judged by it.
        assertTrue(PluginGrants.jobRefusal(panelPlugin, snap, label, envelope("ui.game_section"))!!.contains("does not offer"))
    }

    @Test
    fun `a contract 1 job without an envelope is checked as its capability's point`() {
        val record = TestPlugins.record(TestPlugins.manifest(id = "acme.old", contract = 1))
        assertNull(PluginGrants.jobRefusal(record, PluginGrants.Snapshot(), PluginCapability.STATUS_TILE, emptyMap()))
    }

    @Test
    fun `a plugin hears an event it subscribed to through subscribes, subscribedEvents or the old id`() {
        val bare = TestPlugins.manifest(id = "acme.a", contract = 1) { it.put("subscribedEvents", TestPlugins.strings("default_player_changed")) }
        val renamed = TestPlugins.manifest(id = "acme.b") { it.put("subscribedEvents", TestPlugins.strings("library.default_player_changed")) }
        val v2Only = TestPlugins.manifest(id = "acme.c") { it.put("subscribes", arr(obj("event" to "library.default_player_changed", "version" to 2))) }
        val none = TestPlugins.manifest(id = "acme.d")
        assertTrue(bare.subscribesTo(PluginEvent.DEFAULT_PLAYER_CHANGED))
        assertTrue(renamed.subscribesTo(PluginEvent.DEFAULT_PLAYER_CHANGED))
        assertTrue(v2Only.subscribesTo(PluginEvent.DEFAULT_PLAYER_CHANGED))
        assertFalse(none.subscribesTo(PluginEvent.DEFAULT_PLAYER_CHANGED))
        assertFalse(v2Only.subscribesTo(PluginEvent.GAME_EXITED))
    }
}
