package dev.droidtop.pluginhost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A provider and a caller plugin end to end through the broker (docs/plugin-api.md 2.4 and 2.6, Droidtop/tracker#129):
 * the caller gets the provider's answer, PROVIDER_UNAVAILABLE while the provider is off or gone, and the answer again
 * when it is back. The rig half of #129 runs the same steps with real plugins.
 */
class ProviderCallerTest {
    /** Exports `acme.echo` 1.0, op `say`: answers what it was asked, and who asked. */
    class Echo : DroidtopPlugin {
        override fun invoke(capability: PluginCapability, args: PluginArgs): PluginResult = PluginResult.failure("contract 2 only")

        override fun handle(call: PluginCall): PluginReply =
            if (call.point == "api:acme.echo" && call.op == "say") {
                PluginReply.ok(JSONObject().put("said", call.args.optString("text")).put("to", call.caller.optString("id")))
            } else {
                PluginReply.error(PluginErrorCode.UNSUPPORTED, "Echo has no such op.")
            }
    }

    /** Requires `acme.echo`: its action asks the provider and answers with the broker's reply under `reply`. */
    class Caller : DroidtopPlugin {
        private lateinit var context: PluginContext

        override fun onLoad(context: PluginContext) {
            this.context = context
        }

        override fun invoke(capability: PluginCapability, args: PluginArgs): PluginResult = PluginResult.failure("contract 2 only")

        override fun handle(call: PluginCall): PluginReply =
            PluginReply.ok(JSONObject().put("reply", JSONObject(context.call("acme.echo", 1, "say", JSONObject().put("text", "hello").toString()))))
    }

    private val echo = TestPlugins.provider("acme.echo", api = "acme.echo", level = null, permission = "acme.echo.use", ops = listOf("say" to false)).manifest

    private fun host(): FakeHost {
        val caller = TestPlugins.caller(id = "acme.caller", api = "acme.echo", permission = "acme.echo.use").manifest
        return FakeHost(caller, Caller()).also {
            it.grant("acme.echo.use", GrantState.GRANTED)
            it.provide(echo, Echo())
        }
    }

    private fun ask(host: FakeHost): JSONObject =
        host.handle(PluginCall("t", PluginRunner.CALL_TIMEOUT_MS, "ui.status_tile", 1, "action")).toJson().getJSONObject("data").getJSONObject("reply")

    @Test
    fun theCallerGetsTheProvidersAnswer() {
        val reply = ask(host())
        assertTrue(reply.toString(), reply.getBoolean("ok"))
        assertEquals("hello", reply.getJSONObject("data").getString("said"))
        assertEquals("the provider sees who asked", "acme.caller", reply.getJSONObject("data").getString("to"))
    }

    @Test
    fun aTurnedOffProviderIsUnavailableAndTheCallerResumesWhenItIsBack() {
        val host = host()
        host.setEnabled("acme.echo", false)
        assertEquals("PROVIDER_UNAVAILABLE", ask(host).getJSONObject("error").getString("code"))
        host.setEnabled("acme.echo", true)
        assertTrue(ask(host).getBoolean("ok"))
    }

    @Test
    fun aRemovedProviderIsUnavailableAndTheCallerResumesWhenItIsInstalledAgain() {
        val host = host()
        host.remove("acme.echo")
        assertEquals("PROVIDER_UNAVAILABLE", ask(host).getJSONObject("error").getString("code"))
        host.provide(echo, Echo())
        assertTrue(ask(host).getBoolean("ok"))
    }
}
