package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginApiResolverTest {
    private val shizuku = TestPlugins.provider("droidtop.shizuku", level = "adb")
    private val caller = TestPlugins.caller(optional = false)

    @Test
    fun `a required API with a runnable provider resolves`() {
        val res = PluginApiResolver.resolve(listOf(shizuku, caller))
        assertFalse(res.isWaiting("acme.caller"))
        assertEquals(listOf("droidtop.shizuku"), res.providers["priv.shell"]!!.map { it.plugin.manifest.id })
    }

    @Test
    fun `a missing required API leaves the plugin waiting, and it resumes when a provider returns`() {
        val without = PluginApiResolver.resolve(listOf(caller))
        assertTrue(without.isWaiting("acme.caller"))
        assertEquals(listOf("priv.shell"), without.waiting["acme.caller"]!!.map { it.api })

        val removed = PluginApiResolver.resolve(listOf(shizuku.copy(enabled = false), caller))
        assertTrue("provider disabled: dependent waits", removed.isWaiting("acme.caller"))

        val back = PluginApiResolver.resolve(listOf(shizuku, caller))
        assertFalse("provider back: dependent runnable again", back.isWaiting("acme.caller"))
    }

    @Test
    fun `a crashed or unapproved provider does not provide`() {
        assertTrue(PluginApiResolver.resolve(listOf(shizuku.copy(disabledReason = "crashed"), caller)).isWaiting("acme.caller"))
        assertTrue(PluginApiResolver.resolve(listOf(shizuku.copy(trust = PluginTrustState.PENDING), caller)).isWaiting("acme.caller"))
    }

    @Test
    fun `an optional requirement never makes a plugin wait`() {
        assertFalse(PluginApiResolver.resolve(listOf(TestPlugins.caller(optional = true))).isWaiting("acme.caller"))
    }

    @Test
    fun `waiting cascades: a plugin that lost its provider stops providing`() {
        val middle = TestPlugins.record(
            TestPlugins.manifest(id = "acme.middle") {
                it.put("requires", TestPlugins.arr(TestPlugins.obj("api" to "priv.shell", "version" to "1.0")))
                it.put("exports", TestPlugins.arr(TestPlugins.obj("api" to "acme.middle.api", "version" to "1.0")))
            },
        )
        val top = TestPlugins.caller(id = "acme.top", api = "acme.middle.api", permission = "vibrate", optional = false)
        val res = PluginApiResolver.resolve(listOf(middle, top))
        assertTrue(res.isWaiting("acme.middle"))
        assertTrue("its dependent waits too", res.isWaiting("acme.top"))
        val ok = PluginApiResolver.resolve(listOf(shizuku, middle, top))
        assertTrue(ok.waiting.isEmpty())
    }

    @Test
    fun `a version needs the same major and at least the minor`() {
        assertTrue(PluginApiResolver.versionSatisfies("1.1", "1.2"))
        assertTrue(PluginApiResolver.versionSatisfies("1.0", "1.0"))
        assertFalse(PluginApiResolver.versionSatisfies("1.3", "1.2"))
        assertFalse(PluginApiResolver.versionSatisfies("2.0", "1.9"))
        assertFalse(PluginApiResolver.versionSatisfies("nonsense", "1.0"))
        val res = PluginApiResolver.resolve(listOf(shizuku, TestPlugins.caller(version = "1.4", optional = false)))
        assertTrue(res.isWaiting("acme.caller"))
    }

    @Test
    fun `minLevel excludes a weaker provider for that caller only`() {
        val wantsRoot = TestPlugins.caller(id = "acme.rooty", minLevel = "root", optional = false)
        val plain = TestPlugins.caller(id = "acme.plain", optional = false)
        val res = PluginApiResolver.resolve(listOf(shizuku, wantsRoot, plain))
        assertTrue(res.isWaiting("acme.rooty"))
        assertFalse(res.isWaiting("acme.plain"))
        val root = TestPlugins.provider("acme.root", level = "root")
        assertFalse(PluginApiResolver.resolve(listOf(shizuku, root, wantsRoot)).isWaiting("acme.rooty"))
        assertEquals("acme.root", PluginApiResolver.providerFor(PluginApiResolver.resolve(listOf(shizuku, root, wantsRoot)), "acme.rooty", wantsRoot.manifest.v2.requires.single())!!.plugin.manifest.id)
    }

    @Test
    fun `the user's choice picks between two providers, otherwise the first by id`() {
        val root = TestPlugins.provider("acme.root", level = "adb")
        val res = PluginApiResolver.resolve(listOf(root, shizuku, caller))
        val required = caller.manifest.v2.requires.single()
        assertEquals("acme.root", PluginApiResolver.providerFor(res, "acme.caller", required)!!.plugin.manifest.id)
        assertEquals("droidtop.shizuku", PluginApiResolver.providerFor(res, "acme.caller", required, chosen = "droidtop.shizuku")!!.plugin.manifest.id)
        assertEquals("a choice that no longer exists falls back", "acme.root", PluginApiResolver.providerFor(res, "acme.caller", required, chosen = "gone")!!.plugin.manifest.id)
    }

    @Test
    fun `a plugin is never its own provider`() {
        val both = TestPlugins.record(
            TestPlugins.manifest(id = "acme.both") {
                it.put("requires", TestPlugins.arr(TestPlugins.obj("api" to "acme.both.api", "version" to "1.0")))
                it.put("exports", TestPlugins.arr(TestPlugins.obj("api" to "acme.both.api", "version" to "1.0")))
            },
        )
        assertTrue(PluginApiResolver.resolve(listOf(both)).isWaiting("acme.both"))
        assertNull(PluginApiResolver.providerFor(PluginApiResolver.resolve(listOf(both)), "acme.both", both.manifest.v2.requires.single()))
    }

    @Test
    fun `an export the user has not allowed does not provide`() {
        val res = PluginApiResolver.resolve(listOf(shizuku, caller)) { id, api -> !(id == "droidtop.shizuku" && api == "priv.shell") }
        assertTrue(res.isWaiting("acme.caller"))
    }
}
