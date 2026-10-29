package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginConsentTest {
    private fun manifest(id: String = "acme.vpn", origin: String = "acme", build: (JSONObject) -> Unit): PluginManifest {
        val json = JSONObject().apply {
            put("id", id); put("origin", origin); put("label", "Acme VPN"); put("kind", "native_bundle")
            put("contractVersion", 2)
            put("abis", JSONArray(listOf("arm64-v8a", "x86_64"))); put("entryClass", "acme.Vpn")
            put("payload", JSONArray(listOf(JSONObject().put("path", "classes.jar").put("sha256", "a".repeat(64)))))
            build(this)
        }
        return PluginManifest.fromJson(json)!!
    }

    private fun record(m: PluginManifest) =
        PluginRecord(m, "b".repeat(64), "", PluginTrustState.APPROVED, enabled = true, rootApproved = false, disabledReason = null)

    private fun obj(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }

    private fun arr(vararg o: JSONObject) = JSONArray(o.toList())

    @Test
    fun `the retired graphics chip permission is not asked for, and a plugin declaring it still installs`() {
        val m = TestPlugins.manifest { it.put("permissions", arr(obj("id" to "gpu.render", "reason" to "Smooth animations"))) }
        val consent = PluginConsent.of(m, listOf(record(m)), badgeFor = { "Added by you" })
        assertTrue("plugins never get the GPU (docs/plugin-api.md 5.6)", consent.asks.isEmpty())
        assertTrue(consent.unsupported.isNotEmpty())
    }

    @Test
    fun `a v2 manifest shows each section`() {
        val provider = manifest(id = "droidtop.shizuku", origin = "droidtop") {
            it.put("exports", arr(obj("api" to "priv.shell", "version" to "1.0")))
            it.put("provides", arr(obj("point" to "ui.status_tile")))
        }
        val m = manifest {
            it.put(
                "provides",
                arr(
                    obj("point" to "ui.status_tile", "label" to "VPN", "surfaces" to JSONArray(listOf("gaming.quick_menu", "desktop.tray"))),
                    obj("point" to "library.sources"),
                    obj("point" to "future.point"),
                ),
            )
            it.put(
                "permissions",
                arr(
                    obj("id" to "net.state"),
                    obj("id" to "net.domains", "domains" to JSONArray(listOf("api.acme.example"))),
                    obj("id" to "clipboard.read", "reason" to "Reads the code you copy", "required" to true),
                    obj("id" to "containers.exec"),
                    obj("id" to "made.up"),
                ),
            )
            it.put("requires", arr(obj("api" to "priv.shell", "optional" to true, "minLevel" to "adb", "reason" to "Restart the app"), obj("api" to "sync.folders")))
        }
        val view = PluginConsent.of(m, listOf(record(provider), record(m)), badgeFor = { o -> if (o == "droidtop") "Official" else "Added by you" })

        // One tick box per point, under the first mode it shows in.
        assertEquals(listOf("Gaming", "Wherever it fits"), view.adds.map { it.first })
        // The point's name leads and the entry's own title follows it (Droidtop/tracker#521 item 3).
        assertEquals("Status tiles and widgets: VPN", view.adds[0].second.single().title)
        val sources = view.adds[1].second.single()
        assertEquals("Get games from a source", sources.title)
        assertEquals("provide:library.sources", sources.id)
        assertTrue("a high-risk point is marked and starts unticked", sources.highRisk && !sources.ticked)
        assertTrue("what the point lets the plugin do is the detail", sources.detail!!.startsWith("Lets it"))
        assertTrue(view.adds[0].second.single().let { it.ticked && !it.highRisk })
        assertEquals(
            listOf("See whether you are online", "Connect to: listed domains"),
            view.can.map { it.title },
        )
        // Critical first, then dangerous; the high-risk point is a tick box under "Adds", not a second line here.
        assertEquals(PermissionTier.CRITICAL, view.asks.first().tier)
        assertEquals(
            setOf("Run programs inside your containers, with access to everything in them", "Read the clipboard"),
            view.asks.map { it.line.title }.toSet(),
        )
        val clip = view.asks.single { it.line.title == "Read the clipboard" }
        assertTrue(clip.needed)
        assertTrue(clip.line.highRisk && !clip.line.ticked)
        assertEquals("clipboard.read", clip.line.id)
        assertTrue(view.can.all { it.ticked && it.id != null })
        assertEquals(setOf("provide:ui.status_tile", "provide:library.sources", "net.state", "net.domains", "clipboard.read", "containers.exec"), view.items.map { it.id }.toSet())
        assertEquals("Reads the code you copy.", clip.line.detail)
        // #516: which sites is a sentence under the title, never part of it.
        assertEquals("Only the site api.acme.example.", view.can.single { it.id == "net.domains" }.detail)
        assertEquals(listOf("extension point future.point", "permission made.up"), view.unsupported)

        val shell = view.uses[0]
        assertTrue(shell.optional)
        assertEquals("Acme VPN", shell.providerLabel)
        assertEquals("Official", shell.providerBadge)
        assertEquals("Needs ADB-level access. Restart the app.", shell.line.detail)
        assertFalse("never the interface's id (#516)", shell.line.title.contains("priv."))
        assertNull(view.uses[1].providerLabel)
    }
}
