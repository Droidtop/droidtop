package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.TestPlugins.obj
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The clipboard bridge for plugins (docs/plugin-api.md 1.12, Droidtop/tracker#221): read and set, each its own permission. */
class HostClipboardApisTest {
    private class Bridge(var current: String?) : DesktopClipboard {
        val set = mutableListOf<String>()

        override fun text() = current
        override fun setText(text: String) = true.also { set += text; current = text }
    }

    private val record = TestPlugins.record(
        TestPlugins.manifest(id = "acme.clip") {
            it.put("permissions", JSONArray(listOf(obj("id" to "clipboard.read"), obj("id" to "clipboard.write"))))
        },
    )

    private fun request(op: String, args: JSONObject = JSONObject()) =
        obj("api" to "clipboard", "version" to 1, "op" to op, "args" to args).toString()

    private fun env(vararg granted: String) = FakeEnv(record).also { e ->
        e.user = true
        granted.forEach { e.setGrant("acme.clip", it, GrantState.GRANTED) }
    }

    @After
    fun noBridge() {
        DesktopPluginBridge.clipboard = null
    }

    @Test
    fun `reading needs clipboard read, and every read is in the activity log`() {
        DesktopPluginBridge.clipboard = Bridge("hello")
        // Asked and refused: the person answers Never on droidtop's own prompt.
        val refused = env().also { it.answer = GrantAnswer.NEVER }
        assertEquals(PluginErrorCode.PERMISSION_DENIED, PluginReply.parse(BrokerCore("acme.clip", refused).call(request("get_text"))).code)
        val allowed = env("clipboard.read")
        val reply = PluginReply.parse(BrokerCore("acme.clip", allowed).call(request("get_text")))
        assertEquals("hello", reply.data.getString("text"))
        assertTrue(allowed.audits.any { (_, e) -> e.op == "get_text" && e.permission == "clipboard.read" })
    }

    @Test
    fun `a read outside a call the person started is refused, granted or not`() {
        DesktopPluginBridge.clipboard = Bridge("a password")
        val background = env("clipboard.read").also { it.user = false }
        assertEquals(PluginErrorCode.PERMISSION_DENIED, PluginReply.parse(BrokerCore("acme.clip", background).call(request("get_text"))).code)
    }

    @Test
    fun `nothing to read, or a sensitive copy the bridge withholds, reads as null`() {
        DesktopPluginBridge.clipboard = Bridge(null)
        assertTrue(PluginReply.parse(BrokerCore("acme.clip", env("clipboard.read")).call(request("get_text"))).data.isNull("text"))
    }

    @Test
    fun `setting goes through the bridge, is audited, and is capped`() {
        val bridge = Bridge(null)
        DesktopPluginBridge.clipboard = bridge
        val allowed = env("clipboard.write")
        assertTrue(PluginReply.parse(BrokerCore("acme.clip", allowed).call(request("set_text", obj("text" to "copied")))).data.getBoolean("set"))
        assertEquals(listOf("copied"), bridge.set)
        assertTrue(allowed.audits.any { (_, e) -> e.op == "set_text" && e.target == "6 characters" })
        val huge = "x".repeat(HostClipboardApis.MAX_BYTES + 1)
        assertEquals(PluginErrorCode.INVALID_ARGS, PluginReply.parse(BrokerCore("acme.clip", allowed).call(request("set_text", obj("text" to huge)))).code)
        assertEquals(1, bridge.set.size)
    }

    @Test
    fun `with no desktop the clipboard is unsupported, and the event is known`() {
        assertEquals(PluginErrorCode.UNSUPPORTED, PluginReply.parse(BrokerCore("acme.clip", env("clipboard.read")).call(request("get_text"))).code)
        assertEquals(PluginEvent.CLIPBOARD_CHANGED, PluginEvent.fromId("clipboard.changed"))
        assertEquals(PermissionTier.DANGEROUS, PluginPermissions.find("clipboard.read")!!.tier)
    }
}
