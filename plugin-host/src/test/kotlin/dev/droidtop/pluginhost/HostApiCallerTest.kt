package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.TestPlugins.arr
import dev.droidtop.pluginhost.TestPlugins.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostApiCallerTest {
    /** The official Shizuku provider: priv.packages with a force_stop op, on the priv.packages permission. */
    private fun shizuku(origin: String = "droidtop", ops: List<Pair<String, Boolean>> = listOf("force_stop" to false)) =
        TestPlugins.provider("droidtop.shizuku", api = "priv.packages", permission = "priv.packages", ops = ops, origin = origin)

    @Test
    fun `droidtop calls a provider without a caller plugin`() {
        val env = FakeEnv(shizuku())
        val reply = HostApiCaller(env).call("priv.packages", 1, "force_stop", obj("package" to "org.example.emulator"))
        assertTrue(reply.ok)
        val (providerId, call) = env.forwards.single()
        assertEquals("droidtop.shizuku", providerId)
        assertEquals("api:priv.packages", call.point)
        assertEquals("force_stop", call.op)
        assertEquals("host", call.caller.getString("kind"))
        assertFalse("no plugin is named as the caller", call.caller.has("id"))
        assertEquals("org.example.emulator", call.args.getString("package"))
    }

    @Test
    fun `the provider's side is audited because the permission is critical`() {
        val env = FakeEnv(shizuku())
        HostApiCaller(env).call("priv.packages", 1, "force_stop", obj("package" to "org.example.emulator"))
        val (plugin, entry) = env.audits.single()
        assertEquals("droidtop.shizuku", plugin)
        assertEquals("priv.packages", entry.permission)
        assertEquals("served force_stop for droidtop", entry.op)
        assertEquals("org.example.emulator", entry.target)
        assertEquals("ok", entry.result)
    }

    @Test
    fun `no provider, a missing op, a job op and a stopped provider are each refused with their own code`() {
        assertEquals(PluginErrorCode.PROVIDER_UNAVAILABLE, HostApiCaller(FakeEnv()).call("priv.packages", 1, "force_stop", obj()).code)
        assertEquals(PluginErrorCode.UNSUPPORTED, HostApiCaller(FakeEnv(shizuku())).call("priv.packages", 1, "wipe", obj()).code)
        val jobs = FakeEnv(shizuku(ops = listOf("force_stop" to true)))
        assertEquals(PluginErrorCode.UNSUPPORTED, HostApiCaller(jobs).call("priv.packages", 1, "force_stop", obj()).code)
        assertTrue(jobs.forwards.isEmpty())
        val stopped = FakeEnv(shizuku().copy(enabled = false))
        assertEquals(PluginErrorCode.PROVIDER_UNAVAILABLE, HostApiCaller(stopped).call("priv.packages", 1, "force_stop", obj()).code)
    }

    @Test
    fun `a privileged API from a source droidtop has not checked needs the provider's export grant`() {
        val env = FakeEnv(shizuku(origin = "acme"))
        val caller = HostApiCaller(env)
        assertEquals(PluginErrorCode.PROVIDER_UNAVAILABLE, caller.call("priv.packages", 1, "force_stop", obj()).code)
        assertTrue(env.forwards.isEmpty())
        val allowed = shizuku(origin = "acme").let { rec ->
            rec.copy(
                manifest = TestPlugins.manifest(id = "droidtop.shizuku", origin = "acme") {
                    it.put("exports", org.json.JSONArray(rec.manifest.v2.exports.map { e -> e.toJson() }))
                    it.put("permissions", arr(obj("id" to "plugins.export_privileged")))
                },
            )
        }
        val env2 = FakeEnv(allowed)
        env2.states["droidtop.shizuku"] = mutableMapOf("plugins.export_privileged" to GrantState.GRANTED)
        assertTrue(HostApiCaller(env2).call("priv.packages", 1, "force_stop", obj("package" to "org.example.emulator")).ok)
    }

    @Test
    fun `a version the provider does not serve finds no provider`() {
        val env = FakeEnv(shizuku())
        assertFalse(HostApiCaller(env).hasProvider("priv.packages", 2))
        assertTrue(HostApiCaller(env).hasProvider("priv.packages", 1))
    }

    @Test
    fun `force stop says Stopped when the provider ended the package`() {
        val env = FakeEnv(shizuku())
        env.forwardReply = PluginReply.ok(obj("stopped" to true))
        assertEquals(ForceStop.Result.Stopped, ForceStop.request(HostApiCaller(env), "org.example.emulator"))
    }

    @Test
    fun `force stop says NoProvider when nothing provides priv packages, so the caller keeps its honest fallback`() {
        assertEquals(ForceStop.Result.NoProvider, ForceStop.request(HostApiCaller(FakeEnv()), "org.example.emulator"))
    }

    @Test
    fun `force stop carries the provider's own words when it could not`() {
        val env = FakeEnv(shizuku())
        env.forwardReply = PluginReply.error(PluginErrorCode.PROVIDER_UNAVAILABLE, "Shizuku is not running")
        assertEquals(ForceStop.Result.Failed("Shizuku is not running"), ForceStop.request(HostApiCaller(env), "org.example.emulator"))
        env.forwardReply = PluginReply.ok(obj("stopped" to false))
        assertTrue(ForceStop.request(HostApiCaller(env), "org.example.emulator") is ForceStop.Result.Failed)
    }

    @Test
    fun `force stop refuses a name that is not a package, before any call`() {
        val env = FakeEnv(shizuku())
        for (bad in listOf("", "emulator", "org.example; reboot", "org..example", "1org.example", "org.example emu")) {
            assertTrue(bad, ForceStop.request(HostApiCaller(env), bad) is ForceStop.Result.Failed)
        }
        assertTrue(env.forwards.isEmpty())
    }

    @Test
    fun `ending a game is the person's own action, droidtop's other calls are not`() {
        val env = FakeEnv(shizuku())
        val caller = HostApiCaller(env)
        assertEquals(ForceStop.Result.Stopped, ForceStop.request(caller, "org.example.emulator"))
        caller.call("priv.packages", 1, "force_stop", obj("package" to "org.example.emulator"))
        // Only the first may let the provider ask for full access on the first-use sheet.
        assertEquals(listOf(true, false), env.personStartedForwards)
    }
}
