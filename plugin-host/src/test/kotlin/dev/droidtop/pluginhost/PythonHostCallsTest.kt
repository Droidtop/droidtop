package dev.droidtop.pluginhost

import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin end of Python's `droidtop.host.call` (docs/plugin-api.md 1.3): routing to the right plugin's broker context, and the refusals. */
class PythonHostCallsTest {
    private class Seen(var api: String = "", var version: Int = 0, var op: String = "", var args: String = "")

    private fun context(seen: Seen, reply: String): PluginContext =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PluginContext::class.java)) { _, method, args ->
            check(method.name == "call") { "python host calls must only use PluginContext.call, not " + method.name }
            seen.api = args[0] as String
            seen.version = args[1] as Int
            seen.op = args[2] as String
            seen.args = args[3] as String
            reply
        } as PluginContext

    private fun request(api: String = "ui.toast", op: String = "show", args: String = """{"text":"hi"}""") =
        """{"api":"$api","version":1,"op":"$op","args":$args}"""

    @Test
    fun `a call reaches the broker context of the plugin that made it and no other`() {
        val one = Seen()
        val two = Seen()
        PythonHostCalls.register("acme.one", context(one, """{"ok":true,"data":{"shown":true}}"""))
        PythonHostCalls.register("acme.two", context(two, """{"ok":true,"data":{}}"""))
        try {
            val reply = JSONObject(PythonHostCalls.call("acme.one", request()))
            assertTrue(reply.getBoolean("ok"))
            assertEquals("ui.toast", one.api)
            assertEquals(1, one.version)
            assertEquals("show", one.op)
            assertEquals("hi", JSONObject(one.args).getString("text"))
            assertEquals("", two.api)
        } finally {
            PythonHostCalls.unregister("acme.one")
            PythonHostCalls.unregister("acme.two")
        }
    }

    @Test
    fun `a refusal from the broker is the plugin's answer unchanged`() {
        val refusal = PluginReply.error(PluginErrorCode.PERMISSION_DENIED, "acme.one did not declare overlay.toast").encode()
        PythonHostCalls.register("acme.one", context(Seen(), refusal))
        try {
            val reply = JSONObject(PythonHostCalls.call("acme.one", request()))
            assertFalse(reply.getBoolean("ok"))
            assertEquals("PERMISSION_DENIED", reply.getJSONObject("error").getString("code"))
        } finally {
            PythonHostCalls.unregister("acme.one")
        }
    }

    @Test
    fun `an unloaded plugin, a malformed request and a throwing context are error replies, never exceptions`() {
        assertEquals("FAILED", JSONObject(PythonHostCalls.call("acme.gone", request())).getJSONObject("error").getString("code"))
        val throwing = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PluginContext::class.java)) { _, _, _ ->
            throw IllegalStateException("binder died")
        } as PluginContext
        PythonHostCalls.register("acme.one", throwing)
        try {
            assertEquals("INVALID_ARGS", JSONObject(PythonHostCalls.call("acme.one", "not json")).getJSONObject("error").getString("code"))
            assertEquals("INVALID_ARGS", JSONObject(PythonHostCalls.call("acme.one", """{"api":"ui.toast","version":1,"op":"show"}""")).getJSONObject("error").getString("code"))
            val failed = JSONObject(PythonHostCalls.call("acme.one", request()))
            assertEquals("FAILED", failed.getJSONObject("error").getString("code"))
            assertTrue(failed.getJSONObject("error").getString("message").contains("binder died"))
        } finally {
            PythonHostCalls.unregister("acme.one")
        }
    }

    @Test
    fun `a reply reaches C as ASCII so CPython can decode characters outside the BMP`() {
        val raw = "{\"ok\":true,\"data\":{\"title\":\"Caf\u00e9 \uD83D\uDE00\"}}"
        assertFalse(raw.all { it.code < 0x80 })
        val ascii = PythonHostCalls.asciiJson(raw)
        assertTrue(ascii.all { it.code < 0x80 })
        assertEquals(JSONObject(raw).getJSONObject("data").getString("title"), JSONObject(ascii).getJSONObject("data").getString("title"))
        assertEquals("plain stays as it is", PythonHostCalls.asciiJson("plain stays as it is"))
    }
}
