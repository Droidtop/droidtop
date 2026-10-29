package dev.droidtop.pluginhost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginEnvelopeTest {
    private fun call(point: String, op: String = "state", args: JSONObject = JSONObject()) =
        PluginCall("c-1", 15_000, point, 1, op, args = args)

    @Test
    fun `a call keeps every field through JSON`() {
        val original = PluginCall(
            "c-9", 2_000, "api:priv.shell", 1, "exec",
            caller = TestPlugins.obj("kind" to "plugin", "id" to "acme.caller"),
            surface = TestPlugins.obj("mode" to "gaming"),
            args = TestPlugins.obj("package" to "com.example"),
        )
        val back = PluginCall.fromJson(original.toJson().toString())!!
        assertEquals("c-9", back.callId)
        assertEquals(2_000L, back.deadlineMs)
        assertEquals("api:priv.shell", back.point)
        assertEquals("exec", back.op)
        assertEquals("acme.caller", back.caller.getString("id"))
        assertEquals("gaming", back.surface.getString("mode"))
        assertEquals("com.example", back.args.getString("package"))
        assertEquals(PLUGIN_CONTRACT_VERSION, original.toJson().getInt("contract"))
    }

    @Test
    fun `a call with no point is refused`() {
        assertNull(PluginCall.fromJson("""{"op":"x"}"""))
        assertNull(PluginCall.fromJson("not json"))
    }

    @Test
    fun `an ok reply and an error reply round trip`() {
        val ok = PluginReply.parse(PluginReply.ok(TestPlugins.obj("n" to 3)).encode())
        assertTrue(ok.ok)
        assertEquals(3, ok.data.getInt("n"))
        val err = PluginReply.parse(PluginReply.error(PluginErrorCode.RATE_LIMITED, "slow down").encode())
        assertFalse(err.ok)
        assertEquals(PluginErrorCode.RATE_LIMITED, err.code)
        assertEquals("slow down", err.message)
    }

    @Test
    fun `an error code outside the closed set reads as FAILED`() {
        val reply = PluginReply.parse("""{"ok":false,"error":{"code":"MADE_UP","message":"x"}}""")
        assertEquals(PluginErrorCode.FAILED, reply.code)
    }

    @Test
    fun `a reply that is not the envelope shape is FAILED, not a crash`() {
        assertEquals(PluginErrorCode.FAILED, PluginReply.parse("[1,2").code)
        assertEquals(PluginErrorCode.FAILED, PluginReply.parse(null).code)
        assertFalse(PluginReply.parse("""{"nothing":true}""").ok)
    }

    @Test
    fun `a reply over the size cap is replaced by a FAILED reply`() {
        val big = JSONObject().put("blob", "x".repeat(PluginRunner.MAX_RESULT_BYTES))
        val encoded = PluginReply.ok(big).encode()
        assertTrue(encoded.length < 1_000)
        assertEquals(PluginErrorCode.FAILED, PluginReply.parse(encoded).code)
        assertEquals(PluginErrorCode.FAILED, PluginReply.parse("""{"ok":true,"data":{"blob":"${"x".repeat(PluginRunner.MAX_RESULT_BYTES)}"}}""").code)
    }

    @Test
    fun `a plugin that does not implement handle answers UNSUPPORTED`() {
        val plugin = object : DroidtopPlugin {
            override fun invoke(capability: PluginCapability, args: PluginArgs) = PluginResult.success(mapOf("k" to "v"))
        }
        val reply = plugin.handle(call("ui.status_tile"))
        assertEquals(PluginErrorCode.UNSUPPORTED, reply.code)
        assertEquals("this plugin does not answer ui.status_tile", reply.message)
    }

    @Test
    fun `a plugin that implements handle is called directly`() {
        val plugin = object : DroidtopPlugin {
            override fun invoke(capability: PluginCapability, args: PluginArgs) = PluginResult.failure("old path must not run")

            override fun handle(call: PluginCall) = PluginReply.ok(TestPlugins.obj("op" to call.op))
        }
        val reply = plugin.handle(call("ui.quick_tile", "toggle"))
        assertNotNull(reply)
        assertEquals("toggle", reply.data.getString("op"))
    }
}
