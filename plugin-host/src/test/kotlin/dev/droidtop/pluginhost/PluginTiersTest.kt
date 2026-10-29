package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.TestPlugins.arr
import dev.droidtop.pluginhost.TestPlugins.manifest
import dev.droidtop.pluginhost.TestPlugins.obj
import dev.droidtop.pluginhost.TestPlugins.record
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTiersTest {
    private val fullTrust = obj("id" to PluginTiers.FULL_TRUST, "reason" to "Talks to Shizuku")
    private val gpu = obj("id" to PluginTiers.GPU_RENDER, "reason" to "Draws with hardware")
    private val net = obj("id" to PluginTiers.NET_SOCKETS, "reason" to "Syncs with your other devices")

    private fun grants(vararg states: Pair<String, GrantState>) = PluginGrants.Snapshot(states = states.toMap())

    @Test
    fun `a contract 2 plugin runs contained by default`() {
        val r = record(manifest())
        assertEquals(PluginTier.CONTAINED, PluginTiers.of(r, grants()))
        assertNull(PluginTiers.containmentBlocker(r.manifest))
        assertNull(PluginTiers.refusal(r, grants()))
        assertNull(PluginTiers.badge(r, grants()))
    }

    @Test
    fun `full access runs isolated only with its switch, from Android 11`() {
        assertFalse("the switch is off by default", PluginTiers.fullAccessRunsIsolated(switchOn = false, sdk = 34))
        assertTrue(PluginTiers.fullAccessRunsIsolated(switchOn = true, sdk = 30))
        assertFalse("its sockets need API 30", PluginTiers.fullAccessRunsIsolated(switchOn = true, sdk = 29))
        assertFalse("no device property in a unit test", PluginTiers.fullAccessIsolated())
    }

    @Test
    fun `the Shizuku relay needs os shizuku declared and granted`() {
        val shizuku = obj("id" to PluginTiers.OS_SHIZUKU, "reason" to "Runs commands through Shizuku")
        val r = record(manifest { it.put("permissions", arr(fullTrust, shizuku)) })
        assertTrue(ShizukuRelays.allowed(r, grants(PluginTiers.OS_SHIZUKU to GrantState.GRANTED)))
        assertFalse(ShizukuRelays.allowed(r, grants()))
        assertFalse("undeclared", ShizukuRelays.allowed(record(manifest { it.put("permissions", arr(fullTrust)) }), grants(PluginTiers.OS_SHIZUKU to GrantState.GRANTED)))
    }

    @Test
    fun `an installed contract 1 plugin is never run and needs an update`() {
        val r = record(manifest(contract = 1, label = "Old Tile") { it.put("capabilities", JSONArray(listOf("status_tile"))) })
        assertEquals("Old Tile was written for an older droidtop and needs an update", PluginTiers.refusal(r, grants()))
        assertEquals("not full access", PluginTier.CONTAINED, PluginTiers.of(r, grants()))
        assertNull(PluginTiers.badge(r, grants()))
    }

    @Test
    fun `full access is the host full trust grant and nothing else`() {
        val r = record(manifest { it.put("permissions", arr(fullTrust)) })
        assertEquals("unanswered, it is contained", PluginTier.CONTAINED, PluginTiers.of(r, grants()))
        assertEquals(PluginTier.CONTAINED, PluginTiers.of(r, grants(PluginTiers.FULL_TRUST to GrantState.DENIED)))
        assertEquals(PluginTier.FULL_TRUST, PluginTiers.of(r, grants(PluginTiers.FULL_TRUST to GrantState.GRANTED)))
        assertEquals("Full access", PluginTiers.badge(r, grants(PluginTiers.FULL_TRUST to GrantState.GRANTED)))
        // A grant file that cannot be read is never full access.
        assertEquals(PluginTier.CONTAINED, PluginTiers.of(r, PluginGrants.Snapshot(corrupt = true)))
    }

    @Test
    fun `a grant for a permission the plugin never declared gives nothing`() {
        val r = record(manifest())
        assertEquals(PluginTier.CONTAINED, PluginTiers.of(r, grants(PluginTiers.FULL_TRUST to GrantState.GRANTED)))
    }

    @Test
    fun `every kind runs contained, only privilege an isolated uid never has does not`() {
        // A Flutter engine and native libraries load through the guarded hooks (docs/plugin-api.md 5.3).
        val flutter = manifest {
            it.put("kind", "flutter_embed")
            it.put("runtimeVersion", "abc")
            it.put("payload", JSONArray(listOf(JSONObject().put("path", "lib/x86_64/libapp.so").put("sha256", "a".repeat(64)))))
        }
        assertNull(PluginTiers.containmentBlocker(flutter))
        val withSo = manifest {
            it.put(
                "payload",
                JSONArray(
                    listOf(
                        JSONObject().put("path", "classes.jar").put("sha256", "a".repeat(64)),
                        JSONObject().put("path", "lib/x86_64/libfoo.so").put("sha256", "b".repeat(64)),
                    ),
                ),
            )
        }
        assertNull(PluginTiers.containmentBlocker(withSo))
        assertNotNull(PluginTiers.containmentBlocker(manifest { it.put("permissions", arr(obj("id" to "apps.bind"))) }))
        assertNotNull(PluginTiers.containmentBlocker(TestPlugins.provider("acme.shell").manifest))
    }

    @Test
    fun `a plugin that cannot run contained must declare full trust to install`() {
        val bind = manifest { it.put("permissions", arr(obj("id" to "apps.bind"))) }
        assertTrue(bind.structuralProblems().any { it.contains(PluginTiers.FULL_TRUST) })
        val declared = manifest { it.put("permissions", arr(obj("id" to "apps.bind"), fullTrust)) }
        assertTrue(declared.structuralProblems().none { it.contains(PluginTiers.FULL_TRUST) })
        assertTrue("a containable plugin needs no such declaration", manifest().structuralProblems().none { it.contains(PluginTiers.FULL_TRUST) })
    }

    @Test
    fun `one that cannot run contained is refused until allowed, and says how`() {
        val r = record(manifest { it.put("permissions", arr(obj("id" to "apps.bind"), fullTrust)) })
        assertTrue(PluginTiers.refusal(r, grants())!!.contains("Permissions screen"))
        assertNull(PluginTiers.refusal(r, grants(PluginTiers.FULL_TRUST to GrantState.GRANTED)))
        // An already installed version that never asked for it cannot be allowed it: it needs an update.
        val old = record(manifest { it.put("permissions", arr(obj("id" to "apps.bind"))) })
        assertTrue(PluginTiers.refusal(old, grants())!!.contains("update"))
    }

    @Test
    fun `gpu render is retired, so granting it opens nothing and the plugin stays contained`() {
        // The owner, 2026-10-16: "Plugins shouldn't be drawing stuff themselves anyway" (docs/plugin-api.md 5.6).
        val r = record(manifest { it.put("permissions", arr(gpu)) })
        assertEquals(PluginTier.CONTAINED, PluginTiers.of(r, grants(PluginTiers.GPU_RENDER to GrantState.GRANTED)))
        assertTrue(PluginTiers.holes(r, grants(PluginTiers.GPU_RENDER to GrantState.GRANTED)).isEmpty())
        assertNull(PluginTiers.badge(r, grants(PluginTiers.GPU_RENDER to GrantState.GRANTED)))
        assertNull(PluginTiers.refusal(r, grants(PluginTiers.GPU_RENDER to GrantState.GRANTED)))
        assertNull("no permission, so no warning", PluginTiers.caution(r, grants(PluginTiers.GPU_RENDER to GrantState.GRANTED)))
    }

    @Test
    fun `the network hole runs isolated from Android 11, its sockets the broker's`() {
        assertTrue(PluginTiers.runsIsolated(setOf(PluginTiers.NET_SOCKETS), sdk = 30))
        assertTrue(PluginTiers.runsIsolated(setOf(PluginTiers.NET_SOCKETS), sdk = 35))
        // Below 30 an isolated process may not use a socket it was handed: it waits for the broker's byte relay.
        assertTrue(!PluginTiers.runsIsolated(setOf(PluginTiers.NET_SOCKETS), sdk = 29))
        assertTrue(!PluginTiers.runsIsolated(emptySet(), sdk = 34))
    }

    @Test
    fun `gpu render never substitutes for the privilege a non-containable plugin needs`() {
        // apps.bind cannot run contained; granting gpu.render does not let it dodge the full-access requirement.
        val r = record(manifest { it.put("permissions", arr(obj("id" to "apps.bind"), gpu)) })
        assertEquals(PluginTier.CONTAINED, PluginTiers.of(r, grants(PluginTiers.GPU_RENDER to GrantState.GRANTED)))
        assertNotNull(PluginTiers.refusal(r, grants(PluginTiers.GPU_RENDER to GrantState.GRANTED)))
    }

    @Test
    fun `full access still wins when both are granted`() {
        val r = record(manifest { it.put("permissions", arr(fullTrust, gpu)) })
        assertEquals(
            PluginTier.FULL_TRUST,
            PluginTiers.of(r, grants(PluginTiers.FULL_TRUST to GrantState.GRANTED, PluginTiers.GPU_RENDER to GrantState.GRANTED)),
        )
    }

    @Test
    fun `each granted hole permission opens only its own hole`() {
        // Owner's decision (Droidtop/tracker#553): one open process type, each grant opening only its own hole.
        val r = record(manifest { it.put("permissions", arr(gpu, net)) })
        assertEquals(PluginTier.CONTAINED, PluginTiers.of(r, grants()))
        assertTrue(PluginTiers.holes(r, grants()).isEmpty())
        assertEquals(setOf(PluginTiers.NET_SOCKETS), PluginTiers.holes(r, grants(PluginTiers.NET_SOCKETS to GrantState.GRANTED)))
        assertEquals(PluginTier.OPEN, PluginTiers.of(r, grants(PluginTiers.NET_SOCKETS to GrantState.GRANTED)))
        assertTrue("the retired graphics hole opens nothing", PluginTiers.holes(r, grants(PluginTiers.GPU_RENDER to GrantState.GRANTED, PluginTiers.NET_SOCKETS to GrantState.DENIED)).isEmpty())
        assertEquals(
            setOf(PluginTiers.NET_SOCKETS),
            PluginTiers.holes(r, grants(PluginTiers.GPU_RENDER to GrantState.GRANTED, PluginTiers.NET_SOCKETS to GrantState.GRANTED)),
        )
        assertNull(PluginTiers.badge(r, grants(PluginTiers.NET_SOCKETS to GrantState.GRANTED)))
        // A hole the plugin did not declare is never opened, whatever the store says.
        val undeclared = record(manifest { it.put("permissions", arr(gpu)) })
        assertTrue(PluginTiers.holes(undeclared, grants(PluginTiers.GPU_RENDER to GrantState.GRANTED, PluginTiers.NET_SOCKETS to GrantState.GRANTED)).isEmpty())
        // Full access, and a plugin that cannot run contained, get no holes.
        val full = record(manifest { it.put("permissions", arr(fullTrust, net)) })
        assertTrue(PluginTiers.holes(full, grants(PluginTiers.FULL_TRUST to GrantState.GRANTED, PluginTiers.NET_SOCKETS to GrantState.GRANTED)).isEmpty())
        val binds = record(manifest { it.put("permissions", arr(obj("id" to "apps.bind"), net)) })
        assertTrue(PluginTiers.holes(binds, grants(PluginTiers.NET_SOCKETS to GrantState.GRANTED)).isEmpty())
    }

    @Test
    fun `the network hole warns in plain words, and the retired graphics hole adds nothing`() {
        val r = record(manifest { it.put("permissions", arr(gpu, net)) })
        val network = PluginTiers.caution(r, grants(PluginTiers.NET_SOCKETS to GrantState.GRANTED))!!
        assertTrue(network.contains("other apps on this device"))
        listOf("tier", "socket", "sandbox", "seccomp").forEach { assertTrue(it, !network.contains(it, ignoreCase = true)) }
        val both = PluginTiers.caution(r, grants(PluginTiers.GPU_RENDER to GrantState.GRANTED, PluginTiers.NET_SOCKETS to GrantState.GRANTED))!!
        assertTrue(!both.contains("reach past droidtop's checks") && both.contains("other apps on this device"))
    }
}
