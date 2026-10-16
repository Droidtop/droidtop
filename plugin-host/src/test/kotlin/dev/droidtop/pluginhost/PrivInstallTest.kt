package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.TestPlugins.arr
import dev.droidtop.pluginhost.TestPlugins.obj
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** `priv.packages install`'s droidtop half (docs/plugin-api.md 2.7, Droidtop/tracker#130): read, refuse, confirm, stage. */
class PrivInstallTest {
    private fun provider(fullTrust: Boolean = true) = TestPlugins.record(
        TestPlugins.manifest(id = "droidtop.shizuku-bridge", origin = "droidtop", label = "Shizuku") {
            it.put(
                "exports",
                arr(obj("api" to "priv.packages", "version" to "1.0", "attributes" to obj("level" to "adb"), "ops" to JSONArray(listOf(obj("op" to "install", "permission" to "priv.packages", "job" to true))))),
            )
            if (fullTrust) it.put("permissions", arr(obj("id" to PluginTiers.FULL_TRUST, "required" to true)))
        },
    )

    private val caller = TestPlugins.caller(api = "priv.packages", permission = "priv.packages", optional = true)

    private fun world(fullTrust: Boolean = true): FakeEnv {
        val provider = provider(fullTrust)
        val env = FakeEnv(provider, caller)
        env.states["acme.caller"] = mutableMapOf("priv.packages" to GrantState.GRANTED)
        if (fullTrust) env.states[provider.manifest.id] = mutableMapOf(PluginTiers.FULL_TRUST to GrantState.GRANTED)
        env.user = true
        env.confirmAnswer = true
        env.apk = ApkInfo("com.example.throwaway", "Throwaway", "1.0", 1)
        env.dataStore("acme.caller").write("apps/throwaway.apk", "not really an apk".toByteArray(), append = false)
        return env
    }

    private fun install(env: FakeEnv, args: org.json.JSONObject = obj("name" to "apps/throwaway.apk")) =
        PluginReply.parse(BrokerCore("acme.caller", env).call(obj("api" to "priv.packages", "version" to 1, "op" to "install", "args" to args).toString()))

    @Test
    fun theProviderGetsOnlyTheCopyDroidtopStagedAfterThePersonSaidYes() {
        val env = world()
        val reply = install(env, obj("name" to "apps/throwaway.apk", "staged" to obj("path" to "/data/data/dev.droidtop/files/secret")))
        assertTrue(reply.message, reply.ok)
        assertEquals("job-1", reply.data.getString("jobId"))
        assertEquals("Install Throwaway 1.0?", env.confirms.single().title)
        val staged = env.brokeredJobs.single().second.args.getJSONObject("staged")
        assertTrue("a caller's own staged is dropped", staged.getString("path").startsWith(env.stagingDir.absolutePath))
        assertEquals("com.example.throwaway", staged.getString("package"))
        val copy = File(staged.getString("path"))
        assertEquals(copy.length(), staged.getLong("size"))
        assertEquals(PrivInstall.sha256(copy), staged.getString("sha256"))
        assertFalse("the caller's name is not passed on", env.brokeredJobs.single().second.args.has("name"))
    }

    @Test
    fun noIsCancelledAndNothingIsStaged() {
        val env = world()
        env.confirmAnswer = false
        assertEquals(PluginErrorCode.CANCELLED, install(env).code)
        assertTrue(env.brokeredJobs.isEmpty())
        assertTrue(env.stagingDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun refusalsComeBeforeThePersonIsAsked() {
        var env = world()
        env.installRefusalText = "com.example.throwaway is the home screen in use."
        assertEquals(PluginErrorCode.PERMISSION_DENIED, install(env).code)
        assertTrue(env.confirms.isEmpty())

        env = world()
        env.apk = null
        assertEquals(PluginErrorCode.INVALID_ARGS, install(env).code)

        env = world()
        assertEquals(PluginErrorCode.NOT_FOUND, install(env, obj("name" to "apps/missing.apk")).code)
        assertEquals(PluginErrorCode.INVALID_ARGS, install(env, obj("name" to "../escape.apk")).code)

        env = world()
        env.user = false
        assertEquals("only a call the person started installs", PluginErrorCode.PERMISSION_DENIED, install(env).code)
        assertTrue(env.confirms.isEmpty())
    }

    @Test
    fun aProviderWithoutFullAccessCannotInstall() {
        val env = world(fullTrust = false)
        assertEquals(PluginErrorCode.UNSUPPORTED, install(env).code)
        assertTrue(env.brokeredJobs.isEmpty())
    }

    @Test
    fun aStaleStagedCopyIsRemovedOnTheNextInstall() {
        val dir = kotlin.io.path.createTempDirectory("stage").toFile()
        val old = File(dir, "old.apk").apply { writeText("x"); setLastModified(0) }
        val src = File(dir.parentFile, "src-${System.nanoTime()}.apk").apply { writeText("apk") }
        val staged = PrivInstall.stage(src, dir, PrivInstall.STALE_MS + 1_000)
        assertFalse(old.exists())
        assertTrue(staged.exists())
        assertEquals("apk", staged.readText())
    }
}
