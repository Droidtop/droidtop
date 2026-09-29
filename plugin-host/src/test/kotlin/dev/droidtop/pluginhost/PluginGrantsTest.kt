package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.TestPlugins.arr
import dev.droidtop.pluginhost.TestPlugins.obj
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginGrantsTest {
    private val dir: File = Files.createTempDirectory("grants").toFile()
    private val grants = PluginGrants(dir)

    private fun plugin(contract: Int = 2, id: String = "acme.tool", vararg perms: org.json.JSONObject, build: (org.json.JSONObject) -> Unit = {}) =
        TestPlugins.record(
            TestPlugins.manifest(id = id, contract = contract) {
                if (contract >= 2) it.put("permissions", arr(*perms))
                build(it)
            },
        )

    @Test
    fun `a state round trips through the file`() {
        grants.set("acme.tool", "net.any", GrantState.DENIED)
        grants.set("acme.tool", "clipboard.read", GrantState.GRANTED)
        assertEquals(mapOf("net.any" to GrantState.DENIED, "clipboard.read" to GrantState.GRANTED), PluginGrants(dir).grantsFor("acme.tool"))
    }

    @Test
    fun `approval grants normal, asks for dangerous and grants what was ticked`() {
        val record = plugin(2, "acme.tool", obj("id" to "net.state"), obj("id" to "net.any"), obj("id" to "clipboard.read"), obj("id" to "containers.exec"))
        grants.initialiseOnApproval(record, ticked = setOf("clipboard.read"))
        val snap = grants.read("acme.tool")
        assertEquals(GrantState.GRANTED, PluginGrants.stateOf(record, snap, "net.state"))
        assertEquals(GrantState.ASK, PluginGrants.stateOf(record, snap, "net.any"))
        assertEquals(GrantState.GRANTED, PluginGrants.stateOf(record, snap, "clipboard.read"))
        assertEquals(GrantState.ASK, PluginGrants.stateOf(record, snap, "containers.exec"))
        assertNull("a permission the plugin never declared", PluginGrants.stateOf(record, snap, "vibrate"))
    }

    @Test
    fun `a provider's own permission is asked like a dangerous one`() {
        val record = plugin(2, "acme.tool", obj("id" to "acme.sync.status.read"))
        grants.initialiseOnApproval(record)
        assertEquals(GrantState.ASK, PluginGrants.stateOf(record, grants.read("acme.tool"), "acme.sync.status.read"))
    }

    @Test
    fun `a contract 1 plugin holds what it could already do and its root tick becomes the grant`() {
        val v1 = TestPlugins.record(TestPlugins.manifest(contract = 1) { it.put("requestsRoot", true) }, rootApproved = true)
        grants.initialiseOnApproval(v1)
        val snap = grants.read("acme.tool")
        assertEquals(GrantState.GRANTED, PluginGrants.stateOf(v1, snap, "host.full_trust"))
        assertEquals(GrantState.GRANTED, PluginGrants.stateOf(v1, snap, "apps.launch"))
        assertEquals(GrantState.GRANTED, PluginGrants.stateOf(v1, snap, "priv.shell.root"))
        val noRoot = TestPlugins.record(TestPlugins.manifest(contract = 1) { it.put("requestsRoot", true) }, rootApproved = false)
        assertEquals(GrantState.ASK, PluginGrants.stateOf(noRoot, PluginGrants.Snapshot(), "priv.shell.root"))
    }

    @Test
    fun `an approved contract 1 plugin with no file is granted by default`() {
        val v1 = TestPlugins.record(TestPlugins.manifest(contract = 1))
        assertEquals(GrantState.GRANTED, PluginGrants.stateOf(v1, grants.read("acme.tool"), "apps.check"))
    }

    @Test
    fun `a high-risk point waits for a grant, a medium one does not`() {
        val record = plugin(2, "acme.tool") { it.put("provides", arr(obj("point" to "library.sources"), obj("point" to "ui.status_tile"))) }
        grants.initialiseOnApproval(record)
        val snap = grants.read("acme.tool")
        assertEquals(GrantState.ASK, PluginGrants.provideState(record, snap, "library.sources"))
        assertEquals(GrantState.GRANTED, PluginGrants.provideState(record, snap, "ui.status_tile"))
        grants.initialiseOnApproval(record, ticked = setOf("provide:library.sources"))
        assertEquals(GrantState.GRANTED, PluginGrants.provideState(record, grants.read("acme.tool"), "library.sources"))
    }

    @Test
    fun `delete removes the grants and the install id`() {
        grants.set("acme.tool", "net.any", GrantState.GRANTED)
        val id = grants.installIdFor("acme.tool")
        assertEquals("the id is stable", id, grants.installIdFor("acme.tool"))
        grants.delete("acme.tool")
        assertTrue(grants.grantsFor("acme.tool").isEmpty())
        assertFalse(File(dir, "acme.tool.json").exists())
        assertFalse("a new install gets a new id", id == grants.installIdFor("acme.tool"))
    }

    @Test
    fun `a corrupt file reads as ask for everything, never granted`() {
        val record = plugin(2, "acme.tool", obj("id" to "net.state"), obj("id" to "net.any"))
        dir.mkdirs()
        File(dir, "acme.tool.json").writeText("{ this is not json")
        val snap = grants.read("acme.tool")
        assertTrue(snap.corrupt)
        assertEquals(GrantState.ASK, PluginGrants.stateOf(record, snap, "net.state"))
        assertEquals(GrantState.ASK, PluginGrants.stateOf(record, snap, "net.any"))
        val v1 = TestPlugins.record(TestPlugins.manifest(contract = 1))
        assertEquals(GrantState.ASK, PluginGrants.stateOf(v1, snap, "apps.launch"))
        assertEquals(GrantState.ASK, PluginGrants.provideState(v1, snap, "ui.status_tile"))
    }

    @Test
    fun `an update keeps the old grants and the new dangerous items wait at ask`() {
        val old = plugin(2, "acme.tool", obj("id" to "net.state"), obj("id" to "net.any"))
        grants.initialiseOnApproval(old, ticked = setOf("net.any"))
        val new = plugin(2, "acme.tool", obj("id" to "net.state"), obj("id" to "net.any"), obj("id" to "clipboard.read"), obj("id" to "vibrate")) {
            it.put("provides", arr(obj("point" to "ui.status_tile"), obj("point" to "library.sources")))
            it.put("exports", arr(obj("api" to "acme.tool.status", "version" to "1.0")))
        }
        val diff = grants.applyUpdate(old, new)
        assertEquals(listOf("clipboard.read"), diff.permissions)
        assertEquals(listOf("library.sources"), diff.points)
        assertEquals(listOf("acme.tool.status"), diff.exports)
        val snap = grants.read("acme.tool")
        assertEquals("old dangerous grant kept", GrantState.GRANTED, PluginGrants.stateOf(new, snap, "net.any"))
        assertEquals(GrantState.ASK, PluginGrants.stateOf(new, snap, "clipboard.read"))
        assertEquals("a new normal permission is just granted", GrantState.GRANTED, PluginGrants.stateOf(new, snap, "vibrate"))
        assertEquals(GrantState.ASK, PluginGrants.provideState(new, snap, "library.sources"))
        assertEquals(GrantState.ASK, PluginGrants.exportState(snap, "acme.tool.status"))
        assertTrue(snap.wantsNewAccess)

        grants.set("acme.tool", "clipboard.read", GrantState.GRANTED)
        grants.set("acme.tool", "provide:library.sources", GrantState.DENIED)
        assertTrue("still waiting on the export", grants.read("acme.tool").wantsNewAccess)
        grants.set("acme.tool", "export:acme.tool.status", GrantState.GRANTED)
        assertFalse(grants.read("acme.tool").wantsNewAccess)
    }

    @Test
    fun `an update that adds nothing dangerous changes nothing`() {
        val old = plugin(2, "acme.tool", obj("id" to "net.any"))
        grants.initialiseOnApproval(old)
        val new = plugin(2, "acme.tool", obj("id" to "net.any"), obj("id" to "vibrate"))
        assertTrue(grants.applyUpdate(old, new).isEmpty)
        val snap = grants.read("acme.tool")
        assertEquals("still ask, not silently granted", GrantState.ASK, PluginGrants.stateOf(new, snap, "net.any"))
        assertFalse(snap.wantsNewAccess)
    }

    @Test
    fun `an old plugin with no grants file is written out before the update so defaults cannot change under it`() {
        val v1 = TestPlugins.record(TestPlugins.manifest(contract = 1))
        val v2 = plugin(2, "acme.tool", obj("id" to "apps.launch"), obj("id" to "containers.exec"))
        val diff = grants.applyUpdate(v1, v2)
        assertEquals(listOf("containers.exec"), diff.permissions)
        val snap = grants.read("acme.tool")
        assertEquals(GrantState.GRANTED, snap.states["apps.launch"])
        assertEquals(GrantState.ASK, snap.states["containers.exec"])
    }

    @Test
    fun `a wanted note is kept until the user answers`() {
        grants.set("acme.tool", "net.any", GrantState.ASK)
        grants.noteWanted("acme.tool", "net.any")
        assertEquals(setOf("net.any"), grants.read("acme.tool").wanted)
        grants.set("acme.tool", "net.any", GrantState.GRANTED)
        assertTrue(grants.read("acme.tool").wanted.isEmpty())
    }
}
