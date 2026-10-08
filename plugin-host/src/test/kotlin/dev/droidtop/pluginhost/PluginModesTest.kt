package dev.droidtop.pluginhost

import dev.droidtop.net.SecretCipher
import dev.droidtop.pluginhost.TestPlugins.arr
import dev.droidtop.pluginhost.TestPlugins.obj
import dev.droidtop.pluginhost.TestPlugins.strings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plugins in every mode (docs/plugin-api.md 1.9): `modes`, the person's switches, the new points and the vault. */
class PluginModesTest {
    private fun entry(json: JSONObject): ProvidedPoint = ProvidedPoint.fromJson(json)!!

    @Test
    fun `an entry without modes is in every mode, one with modes only in those, android meaning standard`() {
        val any = entry(obj("point" to "ui.panel"))
        assertNull(PluginModes.declared(any))
        PluginModes.ALL.forEach { assertTrue(PluginModes.entryWants(any, it)) }
        val some = entry(obj("point" to "ui.panel", "modes" to strings("android", "Desktop")))
        assertEquals(setOf(PluginModes.STANDARD, PluginModes.DESKTOP), PluginModes.declared(some))
        assertFalse(PluginModes.entryWants(some, PluginModes.GAMING))
        val future = entry(obj("point" to "ui.panel", "modes" to strings("car")))
        assertTrue("an entry naming only an unknown mode shows nowhere", PluginModes.ALL.none { PluginModes.entryWants(future, it) })
    }

    @Test
    fun `the person's switch takes a plugin out of one mode only`() {
        val any = entry(obj("point" to "ui.panel"))
        val off = PluginGrants.Snapshot(states = mapOf(PluginModes.key(PluginModes.DESKTOP) to GrantState.DENIED))
        assertFalse(PluginModes.shows(off, any, PluginModes.DESKTOP))
        assertTrue(PluginModes.shows(off, any, PluginModes.GAMING))
        assertTrue(PluginModes.shows(PluginGrants.Snapshot(), any, PluginModes.DESKTOP))
    }

    @Test
    fun `modesOf lists only the modes where a point the plugin provides has a place`() {
        val shelf = TestPlugins.manifest { it.put("provides", arr(obj("point" to "gaming.rows"))) }
        assertEquals(listOf(PluginModes.GAMING, PluginModes.DESKTOP), PluginModes.modesOf(shelf))
        val panel = TestPlugins.manifest { it.put("provides", arr(obj("point" to "ui.panel", "modes" to strings("gaming")))) }
        assertEquals(listOf(PluginModes.GAMING), PluginModes.modesOf(panel))
        assertEquals(PluginModes.DESKTOP, PluginModes.ofSurface(PluginModes.Surfaces.DESKTOP_TASKBAR))
        assertNull(PluginModes.ofSurface(PluginModes.Surfaces.SETTINGS))
    }

    @Test
    fun `a same-key update keeps the person's mode and entry switches`() {
        val dir = kotlin.io.path.createTempDirectory("grants").toFile()
        val grants = PluginGrants(dir)
        val old = TestPlugins.record(TestPlugins.manifest(id = "acme.m") { it.put("provides", arr(obj("point" to "ui.panel"))) })
        grants.initialiseOnApproval(old)
        grants.set("acme.m", PluginModes.key(PluginModes.GAMING), GrantState.DENIED)
        grants.set("acme.m", "entry:jobs.schedule/hourly", GrantState.DENIED)
        val new = TestPlugins.record(TestPlugins.manifest(id = "acme.m") { it.put("provides", arr(obj("point" to "ui.panel"), obj("point" to "gaming.rows"))) })
        grants.applyUpdate(old, new)
        val states = grants.read("acme.m").states
        assertEquals(GrantState.DENIED, states[PluginModes.key(PluginModes.GAMING)])
        assertEquals(GrantState.DENIED, states["entry:jobs.schedule/hourly"])
    }

    @Test
    fun `services need consent, schedules do not, and both are points the boundary checks`() {
        assertTrue(ExtensionPoints.find("jobs.service")!!.risk.needsConsent)
        assertFalse(ExtensionPoints.find("jobs.schedule")!!.risk.needsConsent)
        assertNull("the old permission ids are gone: the point is the one consent", PluginPermissions.find("background.service"))
        assertNull(PluginPermissions.find("schedule.jobs"))
        val record = TestPlugins.record(TestPlugins.manifest { it.put("provides", arr(obj("point" to "jobs.service", "id" to "sync"))) })
        assertTrue("a service is not allowed until the person ticks it", PluginGrants.pointRefusal(record, PluginGrants.Snapshot(), "jobs.service") != null)
        assertNull(PluginGrants.pointRefusal(record, PluginGrants.Snapshot(states = mapOf("provide:jobs.service" to GrantState.GRANTED)), "jobs.service"))
    }

    @Test
    fun `schedules parse every, constraints and due, services their restart policy`() {
        assertEquals(6 * 3_600_000L, BackgroundProtocol.parseEvery("6h"))
        assertEquals(15 * 60_000L, BackgroundProtocol.parseEvery("15m"))
        assertNull("under 15 minutes is refused", BackgroundProtocol.parseEvery("5m"))
        assertNull(BackgroundProtocol.parseEvery("40d"))
        assertNull(BackgroundProtocol.parseEvery("soon"))
        val e = entry(obj("point" to "jobs.schedule", "id" to "check", "every" to "1d", "charging" to true))
        assertEquals(86_400_000L, BackgroundProtocol.everyMs(e))
        val c = BackgroundProtocol.constraints(e)
        assertFalse(BackgroundProtocol.allowedNow(c, charging = false, unmetered = true))
        assertTrue(BackgroundProtocol.allowedNow(c, charging = true, unmetered = false))
        assertTrue(BackgroundProtocol.due(null, 1000, 0))
        assertFalse(BackgroundProtocol.due(500, 1000, 1000))
        assertTrue(BackgroundProtocol.due(0, 1000, 1000))
        assertEquals("entry:jobs.schedule/check", BackgroundProtocol.entryKey(e))
        assertTrue(BackgroundProtocol.restartsOnFailure(entry(obj("point" to "jobs.service"))))
        assertFalse(BackgroundProtocol.restartsOnFailure(entry(obj("point" to "jobs.service", "restart" to "never"))))
        assertEquals(30_000L, BackgroundProtocol.restartDelayMs(1))
        assertNull(BackgroundProtocol.restartDelayMs(5))
    }

    private object Reversing : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
    }

    private val vaultUser = TestPlugins.record(
        TestPlugins.manifest(id = "acme.keys") { it.put("permissions", arr(obj("id" to "vault.own"))) },
    )

    private fun call(env: BrokerEnvironment, op: String, args: JSONObject = JSONObject()) =
        PluginReply.parse(BrokerCore("acme.keys", env).call(obj("api" to "vault", "version" to 1, "op" to op, "args" to args).toString()))

    @Test
    fun `the vault keeps a plugin's own values sealed, and refuses bad keys`() {
        val env = FakeEnv(vaultUser)
        val dropped = mutableListOf<String>()
        val vault = PluginVault(env.vaultDir, { Reversing }, { dropped += it })
        env.vaultStore = vault
        assertTrue(call(env, "put", obj("key" to "api_key", "value" to "s3cret")).ok)
        assertEquals("s3cret", call(env, "get", obj("key" to "api_key")).data.getString("value"))
        assertFalse("never stored in the clear", java.io.File(env.vaultDir, "acme.keys.json").readText().contains("s3cret"))
        val keys = call(env, "keys").data.getJSONArray("keys")
        assertEquals(listOf("api_key"), List(keys.length()) { keys.getString(it) })
        assertTrue(call(env, "get", obj("key" to "missing")).data.isNull("value"))
        assertEquals(PluginErrorCode.INVALID_ARGS, call(env, "put", obj("key" to "../etc", "value" to "x")).code)
        assertTrue(call(env, "delete", obj("key" to "api_key")).data.getBoolean("deleted"))
        vault.clear("acme.keys")
        assertEquals(listOf("acme.keys"), dropped)
    }

    @Test
    fun `the vault needs vault_own, and an environment without one says so`() {
        val none = TestPlugins.record(TestPlugins.manifest(id = "acme.keys"))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, call(FakeEnv(none), "keys").code)
        assertEquals(PluginErrorCode.UNSUPPORTED, call(FakeEnv(vaultUser), "keys").code)
    }

    @Test
    fun `host info says standard, never android`() {
        val fake = FakeEnv(vaultUser)
        val env = object : BrokerEnvironment by fake {
            override fun hostFacts(): JSONObject = obj("droidtopVersion" to "1", "mode" to "android")
        }
        val r = PluginReply.parse(BrokerCore("acme.keys", env).call(obj("api" to "host", "version" to 1, "op" to "info").toString()))
        assertEquals("standard", r.data.getString("mode"))
    }
}
