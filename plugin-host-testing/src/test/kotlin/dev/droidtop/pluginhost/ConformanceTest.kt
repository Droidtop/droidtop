package dev.droidtop.pluginhost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `native_bundle` kind's fixture through the conformance script (docs/plugin-api.md 3 I2, Droidtop/tracker#71).
 * The python fixture runs the same cases in droidtop_fake_host.py.
 */
class ConformanceTest {
    /** The fixture conformance.json describes, written as a plugin author writes one. Never packaged. */
    class Fixture : DroidtopPlugin {
        private lateinit var context: PluginContext

        override fun onLoad(context: PluginContext) {
            this.context = context
        }

        override fun invoke(capability: PluginCapability, args: PluginArgs): PluginResult = PluginResult.failure("contract 2 only")

        override fun handle(call: PluginCall): PluginReply = when (call.op) {
            "state" -> PluginReply.ok(JSONObject().put("label", "Fixture").put("value", "ready"))
            "action" -> when (call.args.optString("id")) {
                "notify" -> {
                    val reply = context.call("notify", 1, "post", JSONObject().put("title", "Conformance").put("text", "Hello").toString())
                    PluginReply.ok(JSONObject().put("reply", JSONObject(reply)))
                }
                "fail" -> PluginReply.error(PluginErrorCode.FAILED, "The fixture could not do that.")
                else -> PluginReply.error(PluginErrorCode.UNSUPPORTED, "The fixture has no such action.")
            }
            else -> PluginReply.error(PluginErrorCode.UNSUPPORTED, "The fixture has no such op.")
        }

        override fun startJob(jobId: String, capability: PluginCapability, args: PluginArgs, progress: PluginJobProgress) {
            Thread {
                progress.report(50, "Halfway")
                progress.complete(PluginResult.success(mapOf("done" to "yes")))
            }.start()
        }
    }

    private fun manifest(declare: Boolean = true) = TestPlugins.manifest(id = "acme.fixture") {
        if (declare) it.put("permissions", org.json.JSONArray(listOf(JSONObject().put("id", "notify.post"))))
    }

    @Test
    fun theNativeFixturePassesEveryCase() {
        val results = Conformance.run({ FakeHost(manifest(), Fixture()) })
        assertEquals(Conformance.cases().getJSONArray("cases").length(), results.size)
        results.forEach { assertTrue("${it.name}: ${it.failures}", it.passed) }
    }

    @Test
    fun aFixtureMissingItsPermissionDeclarationFails() {
        val results = Conformance.run({ FakeHost(manifest(declare = false), Fixture()) })
        val granted = results.single { it.name == "a granted host call succeeds" }
        assertTrue(granted.failures.toString(), !granted.passed)
    }

    @Test
    fun aMessageWithAStatusOrMarkupIsNotPlain() {
        val plain = Regex(Conformance.cases().getString("plainMessage"))
        val expect = org.json.JSONArray().put(JSONObject().put("path", "error.message").put("plain", true))
        listOf("The site answered HTTP 403.", "<p>Blocked</p>", "boom at dev.acme.Tool.run(Tool.kt:3)").forEach { message ->
            val reply = JSONObject().put("error", JSONObject().put("message", message))
            assertEquals(message, 1, Conformance.check(reply, expect, plain).size)
        }
        assertEquals(0, Conformance.check(JSONObject().put("error", JSONObject().put("message", "The site did not answer.")), expect, plain).size)
    }
}
