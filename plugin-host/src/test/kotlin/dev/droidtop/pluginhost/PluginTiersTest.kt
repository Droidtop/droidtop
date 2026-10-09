package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.TestPlugins.arr
import dev.droidtop.pluginhost.TestPlugins.manifest
import dev.droidtop.pluginhost.TestPlugins.obj
import dev.droidtop.pluginhost.TestPlugins.record
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTiersTest {
    private val fullTrust = obj("id" to PluginTiers.FULL_TRUST, "reason" to "Talks to Shizuku")

    private fun grants(vararg states: Pair<String, GrantState>) = PluginGrants.Snapshot(states = states.toMap())

    @Test
    fun `a contract 2 plugin runs contained by default`() {
        val r = record(manifest())
        assertEquals(PluginTier.CONTAINED, PluginTiers.of(r, grants()))
        assertNull(PluginTiers.containmentBlocker(r.manifest))
        assertNull(PluginTiers.refusal(r, grants()))
        assertEquals("Contained", PluginTiers.badge(r, grants()))
    }

    @Test
    fun `a contract 1 plugin keeps full access`() {
        val r = record(manifest(contract = 1))
        assertEquals(PluginTier.FULL_TRUST, PluginTiers.of(r, grants()))
        assertEquals("Full access (older plugin)", PluginTiers.badge(r, grants()))
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
}
