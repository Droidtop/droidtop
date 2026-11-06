package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.TestPlugins.obj
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** E5, Desktop windows for plugins (docs/plugin-api.md 1.12, Droidtop/tracker#221): list, focus, close and the events. */
class HostWindowsApisTest {
    private val firefox = DesktopWindow(1L, "org.mozilla.firefox", "Bank - Mozilla Firefox", "HEADLESS-1", focused = true)
    private val terminal = DesktopWindow(2L, "foot", "~", "HEADLESS-1", focused = false)

    private class Session(var list: List<DesktopWindow>) : DesktopWindowControl {
        val focused = mutableListOf<Long>()
        val closed = mutableListOf<Long>()

        override fun windows() = list
        override fun focus(id: Long) = list.any { it.id == id }.also { if (it) focused += id }
        override fun close(id: Long) = list.any { it.id == id }.also { if (it) closed += id }
    }

    private fun plugin(vararg permissions: JSONObject) = TestPlugins.record(
        TestPlugins.manifest(id = "acme.windows") { it.put("permissions", JSONArray(permissions.toList())) },
    )

    private fun request(op: String, args: JSONObject = JSONObject()) =
        obj("api" to "windows", "version" to 1, "op" to op, "args" to args).toString()

    private fun granted(record: PluginRecord, vararg ids: String) = FakeEnv(record).also { env ->
        ids.forEach { env.setGrant(record.manifest.id, it, GrantState.GRANTED) }
    }

    @After
    fun noDesktop() {
        DesktopPluginBridge.windows = null
    }

    @Test
    fun `list gives every window's id, app, title and screen under windows read`() {
        DesktopPluginBridge.windows = Session(listOf(firefox, terminal))
        val record = plugin(obj("id" to "windows.read"))
        val reply = PluginReply.parse(BrokerCore("acme.windows", granted(record, "windows.read")).call(request("list")))
        assertTrue(reply.ok)
        val windows = reply.data.getJSONArray("windows")
        assertEquals(2, windows.length())
        assertEquals(setOf("id", "appId", "title", "output", "focused"), windows.getJSONObject(0).keys().asSequence().toSet())
        assertEquals("org.mozilla.firefox", windows.getJSONObject(0).getString("appId"))
    }

    @Test
    fun `a permission narrowed to apps sees and acts on those apps only`() {
        val session = Session(listOf(firefox, terminal))
        DesktopPluginBridge.windows = session
        val record = plugin(
            obj("id" to "windows.read", "apps" to JSONArray(listOf("foot"))),
            obj("id" to "windows.control", "apps" to JSONArray(listOf("foot"))),
        )
        val broker = BrokerCore("acme.windows", granted(record, "windows.read", "windows.control"))
        val listed = PluginReply.parse(broker.call(request("list"))).data.getJSONArray("windows")
        assertEquals(1, listed.length())
        assertEquals("foot", listed.getJSONObject(0).getString("appId"))
        assertEquals(PluginErrorCode.NOT_FOUND, PluginReply.parse(broker.call(request("close", obj("id" to 1L)))).code)
        assertTrue(PluginReply.parse(broker.call(request("close", obj("id" to 2L)))).ok)
        assertEquals(listOf(2L), session.closed)
    }

    @Test
    fun `focus and close need windows control, and every use is in the activity log`() {
        val session = Session(listOf(firefox))
        DesktopPluginBridge.windows = session
        val record = plugin(obj("id" to "windows.read"), obj("id" to "windows.control"))
        val readOnly = granted(record, "windows.read")
        assertEquals(PluginErrorCode.PERMISSION_DENIED, PluginReply.parse(BrokerCore("acme.windows", readOnly).call(request("focus", obj("id" to 1L)))).code)
        assertTrue(session.focused.isEmpty())

        val env = granted(record, "windows.read", "windows.control")
        assertTrue(PluginReply.parse(BrokerCore("acme.windows", env).call(request("focus", obj("id" to 1L)))).ok)
        assertEquals(listOf(1L), session.focused)
        assertTrue(env.audits.any { (_, entry) -> entry.api == "windows" && entry.op == "focus" && entry.permission == "windows.control" && entry.result == "ok" })
    }

    @Test
    fun `with no desktop running the ops are unsupported`() {
        val record = plugin(obj("id" to "windows.read"))
        assertEquals(PluginErrorCode.UNSUPPORTED, PluginReply.parse(BrokerCore("acme.windows", granted(record, "windows.read")).call(request("list"))).code)
    }

    @Test
    fun `events are an opened, a closed and a newly focused window, never a retitle`() {
        val retitled = terminal.copy(title = "vim")
        val browser = firefox.copy(focused = false)
        val events = WindowEvents.between(before = listOf(browser, terminal), after = listOf(retitled.copy(focused = true), DesktopWindow(3L, "gimp", "GIMP", "", false)))
        assertEquals(
            listOf(PluginEvent.WINDOW_CLOSED to 1L, PluginEvent.WINDOW_OPENED to 3L, PluginEvent.WINDOW_FOCUSED to 2L),
            events.map { it.event to it.window.id },
        )
        assertTrue(WindowEvents.between(listOf(terminal), listOf(terminal.copy(title = "vim"))).isEmpty())
        assertEquals(PluginEvent.WINDOW_FOCUSED, PluginEvent.fromId("window.focused"))
        assertEquals(mapOf("id" to "3", "appId" to "gimp", "title" to "GIMP", "output" to ""), WindowEvents.args(events[1].window))
    }

    @Test
    fun `the window permissions are dangerous`() {
        assertEquals(PermissionTier.DANGEROUS, PluginPermissions.find("windows.read")!!.tier)
        assertEquals(PermissionTier.DANGEROUS, PluginPermissions.find("windows.control")!!.tier)
        assertFalse(HostApis.all().filter { it.api == "windows" }.any { HostOpNames.check(it.op) != null })
    }
}
