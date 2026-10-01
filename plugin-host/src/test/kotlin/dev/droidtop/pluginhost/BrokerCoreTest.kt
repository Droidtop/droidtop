package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.TestPlugins.arr
import dev.droidtop.pluginhost.TestPlugins.obj
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerCoreTest {
    private fun request(api: String, op: String, args: JSONObject = JSONObject(), version: Int = 1) =
        obj("api" to api, "version" to version, "op" to op, "args" to args).toString()

    private fun reply(text: String) = PluginReply.parse(text)

    private fun appsCaller(id: String = "acme.caller", packages: List<String> = listOf("*"), extra: List<JSONObject> = emptyList()) =
        TestPlugins.record(
            TestPlugins.manifest(id = id) {
                it.put(
                    "permissions",
                    org.json.JSONArray(
                        listOf(
                            obj("id" to "apps.check", "packages" to org.json.JSONArray(packages)),
                            obj("id" to "apps.launch"),
                            obj("id" to "apps.intents.out", "packages" to org.json.JSONArray(packages)),
                        ) + extra,
                    ),
                )
            },
        )

    @Test
    fun `the caller is the object that was called, never a name in the request`() {
        val a = appsCaller("acme.a")
        val b = appsCaller("acme.b", packages = listOf("com.only.this"))
        val env = FakeEnv(a, b)
        env.installed += setOf("com.example", "com.only.this")
        val brokerA = BrokerCore("acme.a", env)
        val brokerB = BrokerCore("acme.b", env)
        // The request even claims to be plugin A; broker B still speaks as B, and B's declared list is narrower.
        val claim = obj("api" to "apps", "version" to 1, "op" to "check", "args" to obj("packages" to org.json.JSONArray(listOf("com.example"))))
            .put("plugin", "acme.a")
        assertTrue(reply(brokerA.call(claim.toString())).ok)
        val refused = reply(brokerB.call(claim.toString()))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, refused.code)
    }

    @Test
    fun `host info answers every plugin and never names a device or account`() {
        val env = FakeEnv(appsCaller())
        val r = reply(BrokerCore("acme.caller", env).call(request("host", "info")))
        assertTrue(r.ok)
        assertEquals("0.2.0", r.data.getString("droidtopVersion"))
        assertEquals(PLUGIN_CONTRACT_VERSION, r.data.getInt("contract"))
        assertEquals("install-acme.caller", r.data.getString("installId"))
        assertTrue(r.data.getJSONObject("points").has("library.sources"))
        assertTrue(r.data.getJSONObject("apis").has("apps"))
    }

    @Test
    fun `plugins available reports the provider's version and label, never its id`() {
        val shizuku = TestPlugins.provider("droidtop.shizuku", origin = "droidtop")
        val caller = TestPlugins.caller()
        val env = FakeEnv(shizuku, caller)
        val r = reply(BrokerCore("acme.caller", env).call(request("plugins", "available", obj("api" to "priv.shell", "minLevel" to "adb"))))
        assertTrue(r.ok)
        assertTrue(r.data.getBoolean("available"))
        assertEquals("1.0", r.data.getString("version"))
        assertEquals("droidtop.shizuku", r.data.getString("label"))
        assertFalse(r.data.has("id"))
        val none = reply(BrokerCore("acme.caller", FakeEnv(caller)).call(request("plugins", "available", obj("api" to "priv.shell"))))
        assertFalse(none.data.getBoolean("available"))
        val tooStrong = reply(BrokerCore("acme.caller", env).call(request("plugins", "available", obj("api" to "priv.shell", "minLevel" to "root"))))
        assertFalse(tooStrong.data.getBoolean("available"))
    }

    @Test
    fun `a plugin that is not runnable gets nothing`() {
        for (rec in listOf(appsCaller().copy(trust = PluginTrustState.PENDING), appsCaller().copy(enabled = false), appsCaller().copy(disabledReason = "crashed"))) {
            val r = reply(BrokerCore("acme.caller", FakeEnv(rec)).call(request("host", "info")))
            assertEquals(PluginErrorCode.PERMISSION_DENIED, r.code)
        }
        assertEquals(PluginErrorCode.PERMISSION_DENIED, reply(BrokerCore("acme.gone", FakeEnv()).call(request("host", "info"))).code)
    }

    @Test
    fun `a plugin waiting for a provider is not served`() {
        val needy = TestPlugins.caller(optional = false)
        assertEquals(PluginErrorCode.PERMISSION_DENIED, reply(BrokerCore("acme.caller", FakeEnv(needy)).call(request("host", "info"))).code)
    }

    @Test
    fun `an unknown op or version is UNSUPPORTED and a bad request is INVALID_ARGS`() {
        val env = FakeEnv(appsCaller())
        val core = BrokerCore("acme.caller", env)
        assertEquals(PluginErrorCode.UNSUPPORTED, reply(core.call(request("host", "info", version = 9))).code)
        assertEquals(PluginErrorCode.UNSUPPORTED, reply(core.call(request("apps", "explode"))).code)
        assertEquals(PluginErrorCode.INVALID_ARGS, reply(core.call("not json")).code)
        assertEquals(PluginErrorCode.INVALID_ARGS, reply(core.call("""{"api":"apps"}""")).code)
        assertEquals(PluginErrorCode.INVALID_ARGS, reply(core.call("""{"op":"check"}""")).code)
        assertEquals(PluginErrorCode.INVALID_ARGS, reply(core.call(request("apps", "check", obj()))).code)
        val oversize = request("apps", "check", obj("junk" to "x".repeat(PluginRunner.MAX_RESULT_BYTES)))
        assertEquals(PluginErrorCode.INVALID_ARGS, reply(core.call(oversize)).code)
    }

    @Test
    fun `an op needs the permission declared in the manifest`() {
        val undeclared = TestPlugins.record(TestPlugins.manifest(id = "acme.quiet"))
        val r = reply(BrokerCore("acme.quiet", FakeEnv(undeclared)).call(request("apps", "launch", obj("package" to "com.example"))))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, r.code)
        assertTrue(r.message!!.contains("apps.launch"))
    }

    @Test
    fun `a normal permission is granted at approval and the host runs the call itself`() {
        val env = FakeEnv(appsCaller())
        env.installed += "com.example"
        val core = BrokerCore("acme.caller", env)
        val check = reply(core.call(request("apps", "check", obj("packages" to org.json.JSONArray(listOf("com.example", "com.absent"))))))
        assertTrue(check.ok)
        assertTrue(check.data.getJSONObject("installed").getBoolean("com.example"))
        assertFalse(check.data.getJSONObject("installed").getBoolean("com.absent"))
        assertTrue(reply(core.call(request("apps", "launch", obj("package" to "com.example")))).data.getBoolean("launched"))
        assertTrue("normal permissions are not audited one by one", env.audits.none { it.second.api == "apps" && it.second.op == "launch" })
    }

    @Test
    fun `the parameters must fit what the plugin declared`() {
        val env = FakeEnv(appsCaller(packages = listOf("com.retro.arch")))
        env.installed += setOf("com.retro.arch", "com.other")
        val core = BrokerCore("acme.caller", env)
        assertTrue(reply(core.call(request("apps", "check", obj("package" to "com.retro.arch")))).ok)
        val outside = reply(core.call(request("apps", "check", obj("packages" to org.json.JSONArray(listOf("com.retro.arch", "com.other"))))))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, outside.code)
        val intent = reply(core.call(request("apps", "intent", obj("package" to "com.other", "extras" to obj("ROM" to "x")))))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, intent.code)
        assertTrue(env.launched.isEmpty())
        val ok = reply(core.call(request("apps", "intent", obj("package" to "com.retro.arch", "action" to "android.intent.action.MAIN", "extras" to obj("ROM" to "x")))))
        assertTrue(ok.data.getBoolean("launched"))
        assertEquals(Triple("com.retro.arch", mapOf("ROM" to "x"), "android.intent.action.MAIN"), env.launched.single())
    }

    private fun shellCaller(env: FakeEnv) = BrokerCore("acme.caller", env)

    private fun shellWorld(): FakeEnv {
        val shizuku = TestPlugins.provider("droidtop.shizuku", origin = "droidtop", ops = listOf("exec" to false, "stream" to true))
        val caller = TestPlugins.caller()
        return FakeEnv(shizuku, caller)
    }

    @Test
    fun `an ask permission in a background call fails and leaves a note, never a prompt`() {
        val env = shellWorld()
        env.user = false
        val r = reply(shellCaller(env).call(request("priv.shell", "exec", obj("cmd" to "pm list"))))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, r.code)
        assertTrue(env.prompts.isEmpty())
        assertEquals(listOf("acme.caller" to "priv.shell.adb"), env.wanted)
        assertTrue(env.forwards.isEmpty())
    }

    @Test
    fun `a user-initiated call in ask state shows the sheet, and Allow is remembered`() {
        val env = shellWorld()
        env.user = true
        env.answer = GrantAnswer.ALLOW
        val core = shellCaller(env)
        val first = reply(core.call(request("priv.shell", "exec", obj("cmd" to "pm list"))))
        assertTrue(first.ok)
        val prompt = env.prompts.single()
        assertEquals("priv.shell.adb", prompt.permission)
        assertEquals(PermissionTier.CRITICAL, prompt.tier)
        assertEquals(GrantState.GRANTED, env.states["acme.caller"]!!["priv.shell.adb"])
        assertTrue(reply(core.call(request("priv.shell", "exec"))).ok)
        assertEquals("asked once", 1, env.prompts.size)
    }

    @Test
    fun `Not now fails the call and keeps asking, Never turns it off`() {
        val env = shellWorld()
        env.user = true
        env.answer = GrantAnswer.NOT_NOW
        val core = shellCaller(env)
        assertEquals(PluginErrorCode.PERMISSION_DENIED, reply(core.call(request("priv.shell", "exec"))).code)
        assertTrue(env.states["acme.caller"].isNullOrEmpty())
        env.answer = GrantAnswer.NEVER
        assertEquals(PluginErrorCode.PERMISSION_DENIED, reply(core.call(request("priv.shell", "exec"))).code)
        assertEquals(GrantState.DENIED, env.states["acme.caller"]!!["priv.shell.adb"])
        env.answer = GrantAnswer.ALLOW
        val prompts = env.prompts.size
        assertEquals("a denied permission does not ask again", PluginErrorCode.PERMISSION_DENIED, reply(core.call(request("priv.shell", "exec"))).code)
        assertEquals(prompts, env.prompts.size)
    }

    @Test
    fun `a granted provider call is forwarded with the caller block and audited on both sides`() {
        val env = shellWorld()
        env.states["acme.caller"] = mutableMapOf("priv.shell.adb" to GrantState.GRANTED)
        env.remaining = 15_000
        val r = reply(shellCaller(env).call(request("priv.shell", "exec", obj("cmd" to "pm list"))))
        assertTrue(r.ok)
        val (providerId, call) = env.forwards.single()
        assertEquals("droidtop.shizuku", providerId)
        assertEquals("api:priv.shell", call.point)
        assertEquals("exec", call.op)
        assertEquals("pm list", call.args.getString("cmd"))
        assertEquals("plugin", call.caller.getString("kind"))
        assertEquals("acme.caller", call.caller.getString("id"))
        assertEquals("Added by you", call.caller.getString("trust"))
        assertEquals(listOf("priv.shell.adb"), (0 until call.caller.getJSONArray("grants").length()).map { call.caller.getJSONArray("grants").getString(it) })
        assertEquals("acme.caller", call.caller.getJSONArray("via").getString(0))
        assertEquals("the provider's deadline is the remaining time minus a 1 s margin, at most 15 s", 14_000L, env.lastTimeout)
        val sides = env.audits.map { it.first }
        assertEquals(setOf("acme.caller", "droidtop.shizuku"), sides.toSet())
        assertTrue(env.audits.first { it.first == "droidtop.shizuku" }.second.op.startsWith("served exec for acme.caller"))
    }

    @Test
    fun `the provider deadline follows the caller's remaining time`() {
        val env = shellWorld()
        env.states["acme.caller"] = mutableMapOf("priv.shell.adb" to GrantState.GRANTED)
        env.remaining = 4_000
        shellCaller(env).call(request("priv.shell", "exec"))
        assertEquals(3_000L, env.lastTimeout)
    }

    @Test
    fun `a caller that did not declare requires gets PERMISSION_DENIED and never reaches a provider`() {
        val provider = TestPlugins.provider("droidtop.shizuku", origin = "droidtop")
        val stranger = TestPlugins.record(TestPlugins.manifest(id = "acme.caller") { it.put("permissions", arr(obj("id" to "priv.shell.adb"))) })
        val env = FakeEnv(provider, stranger)
        env.states["acme.caller"] = mutableMapOf("priv.shell.adb" to GrantState.GRANTED)
        assertEquals(PluginErrorCode.PERMISSION_DENIED, reply(BrokerCore("acme.caller", env).call(request("priv.shell", "exec"))).code)
        assertTrue(env.forwards.isEmpty())
    }

    @Test
    fun `no provider is PROVIDER_UNAVAILABLE for an optional requirement`() {
        val env = FakeEnv(TestPlugins.caller())
        env.states["acme.caller"] = mutableMapOf("priv.shell.adb" to GrantState.GRANTED)
        assertEquals(PluginErrorCode.PROVIDER_UNAVAILABLE, reply(BrokerCore("acme.caller", env).call(request("priv.shell", "exec"))).code)
    }

    @Test
    fun `an op the provider does not export, or a version it does not serve, is UNSUPPORTED`() {
        val env = shellWorld()
        env.states["acme.caller"] = mutableMapOf("priv.shell.adb" to GrantState.GRANTED)
        val core = shellCaller(env)
        assertEquals(PluginErrorCode.UNSUPPORTED, reply(core.call(request("priv.shell", "reboot"))).code)
        assertEquals(PluginErrorCode.UNSUPPORTED, reply(core.call(request("priv.shell", "exec", version = 2))).code)
    }

    @Test
    fun `the caller must declare the op's permission in its own manifest`() {
        val provider = TestPlugins.provider("droidtop.shizuku", origin = "droidtop")
        val caller = TestPlugins.record(TestPlugins.manifest(id = "acme.caller") { it.put("requires", arr(obj("api" to "priv.shell", "version" to "1.0", "optional" to true))) })
        val env = FakeEnv(provider, caller)
        val r = reply(BrokerCore("acme.caller", env).call(request("priv.shell", "exec")))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, r.code)
        assertTrue(env.forwards.isEmpty())
    }

    @Test
    fun `a privileged API from a source the user added needs the export grant, an official one does not`() {
        val added = TestPlugins.provider("acme.shell", origin = "acme")
        val caller = TestPlugins.caller()
        val env = FakeEnv(added, caller)
        env.states["acme.caller"] = mutableMapOf("priv.shell.adb" to GrantState.GRANTED)
        assertEquals(PluginErrorCode.PROVIDER_UNAVAILABLE, reply(BrokerCore("acme.caller", env).call(request("priv.shell", "exec"))).code)
        assertTrue(env.forwards.isEmpty())
        // The provider declares and is granted plugins.export_privileged.
        val allowed = added.copy(manifest = TestPlugins.manifest(id = "acme.shell", origin = "acme") {
            it.put("exports", added.manifest.v2.exports.let { e -> org.json.JSONArray(e.map { x -> x.toJson() }) })
            it.put("permissions", arr(obj("id" to "plugins.export_privileged")))
        })
        val env2 = FakeEnv(allowed, caller)
        env2.states["acme.caller"] = mutableMapOf("priv.shell.adb" to GrantState.GRANTED)
        env2.states["acme.shell"] = mutableMapOf("plugins.export_privileged" to GrantState.GRANTED)
        assertTrue(reply(BrokerCore("acme.caller", env2).call(request("priv.shell", "exec"))).ok)
    }

    @Test
    fun `a provider timeout reaches the caller as TIMEOUT and a crash as PROVIDER_CRASHED`() {
        val env = shellWorld()
        env.states["acme.caller"] = mutableMapOf("priv.shell.adb" to GrantState.GRANTED)
        env.forwardReply = PluginReply.error(PluginErrorCode.TIMEOUT, "timed out")
        assertEquals(PluginErrorCode.TIMEOUT, reply(shellCaller(env).call(request("priv.shell", "exec"))).code)
        env.forwardReply = PluginReply.error(PluginErrorCode.PROVIDER_CRASHED, "died")
        assertEquals(PluginErrorCode.PROVIDER_CRASHED, reply(shellCaller(env).call(request("priv.shell", "exec"))).code)
        assertTrue("the failure is in the audit result", env.audits.any { it.second.result == "PROVIDER_CRASHED" })
    }

    @Test
    fun `a job op starts a host-tracked job owned by the caller`() {
        val env = shellWorld()
        env.states["acme.caller"] = mutableMapOf("priv.shell.adb" to GrantState.GRANTED)
        val r = reply(shellCaller(env).call(request("priv.shell", "stream", obj("cmd" to "logcat"))))
        assertTrue(r.ok)
        assertEquals("job-1", r.data.getString("jobId"))
        assertTrue("a job op is not forwarded as a quick call", env.forwards.isEmpty())
        env.jobId = null
        assertEquals(PluginErrorCode.PROVIDER_UNAVAILABLE, reply(shellCaller(env).call(request("priv.shell", "stream"))).code)
    }

    @Test
    fun `a chain is limited to three plugins and refuses cycles`() {
        val env = shellWorld()
        env.states["acme.caller"] = mutableMapOf("priv.shell.adb" to GrantState.GRANTED)
        env.chain = listOf("acme.first")
        assertTrue("A to B to C is allowed", reply(shellCaller(env).call(request("priv.shell", "exec"))).ok)
        val via = env.forwards.single().second.caller.getJSONArray("via")
        assertEquals(listOf("acme.first", "acme.caller"), (0 until via.length()).map { via.getString(it) })
        env.chain = listOf("acme.first", "acme.second")
        assertEquals("a fourth plugin is too deep", PluginErrorCode.INVALID_ARGS, reply(shellCaller(env).call(request("priv.shell", "exec"))).code)
        env.chain = listOf("droidtop.shizuku")
        assertEquals("the provider is already in the chain", PluginErrorCode.INVALID_ARGS, reply(shellCaller(env).call(request("priv.shell", "exec"))).code)
    }

    @Test
    fun `calls beyond the burst are RATE_LIMITED and the limit refills`() {
        val env = FakeEnv(appsCaller())
        val core = BrokerCore("acme.caller", env)
        repeat(BrokerCore.BURST) { assertTrue(reply(core.call(request("host", "info"))).ok) }
        assertEquals(PluginErrorCode.RATE_LIMITED, reply(core.call(request("host", "info"))).code)
        env.now += 1_000
        assertTrue("10 per second sustained", reply(core.call(request("host", "info"))).ok)
    }

    @Test
    fun `a dangerous host op is audited with its outcome`() {
        val caller = TestPlugins.record(
            TestPlugins.manifest(id = "acme.caller") {
                it.put("permissions", arr(obj("id" to "apps.intents.out", "scope" to "any")))
            },
        )
        val env = FakeEnv(caller)
        env.installed += "com.any"
        env.states["acme.caller"] = mutableMapOf("apps.intents.out" to GrantState.GRANTED)
        val r = reply(BrokerCore("acme.caller", env).call(request("apps", "intent", obj("package" to "com.any"))))
        assertTrue(r.ok)
        val entry = env.audits.single().second
        assertEquals("apps.intents.out", entry.permission)
        assertEquals("com.any", entry.target)
        assertEquals("ok", entry.result)
        assertNotNull(env.audits.single().first)
    }

    @Test
    fun `a host API the user turned off at approval returns denied to the plugin`() {
        val env = FakeEnv(appsCaller())
        env.installed += "com.example"
        env.states["acme.caller"] = mutableMapOf("apps.check" to GrantState.GRANTED, "apps.launch" to GrantState.DENIED)
        env.user = true
        val core = BrokerCore("acme.caller", env)
        assertTrue("the ticked one still works", reply(core.call(request("apps", "check", obj("packages" to org.json.JSONArray(listOf("com.example")))))).ok)
        val denied = reply(core.call(request("apps", "launch", obj("package" to "com.example"))))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, denied.code)
        assertTrue(denied.message!!.contains("turned off"))
        assertTrue("a denied item is never asked about again", env.prompts.isEmpty())
        assertTrue(env.launched.isEmpty())
    }
}
