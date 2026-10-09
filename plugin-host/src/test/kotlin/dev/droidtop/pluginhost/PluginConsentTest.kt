package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginConsentTest {
    private fun manifest(id: String = "acme.vpn", origin: String = "acme", contract: Int = 2, build: (JSONObject) -> Unit): PluginManifest {
        val json = JSONObject().apply {
            put("id", id); put("origin", origin); put("label", "Acme VPN"); put("kind", "native_bundle")
            put("capabilities", JSONArray(if (contract == 1) listOf("status_tile") else emptyList<String>()))
            put("contractVersion", contract)
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
    fun `the graphics chip's approval line says it could reach past droidtop's checks`() {
        val m = TestPlugins.manifest { it.put("permissions", arr(obj("id" to "gpu.render", "reason" to "Smooth animations"))) }
        val ask = PluginConsent.of(m, listOf(record(m)), badgeFor = { "Added by you" }).asks.single()
        assertEquals("Use the graphics chip to draw its screen", ask.line.title)
        assertEquals(
            "A plugin with this could reach past droidtop's checks, so only allow it for a plugin from a source you trust - Smooth animations",
            ask.line.detail,
        )
        assertFalse("asks first, never pre-ticked", ask.line.ticked)
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
        assertEquals("VPN", view.adds[0].second.single().title)
        val sources = view.adds[1].second.single()
        assertEquals("Get games from a source", sources.title)
        assertEquals("provide:library.sources", sources.id)
        assertTrue("a high-risk point is marked and starts unticked", sources.highRisk && !sources.ticked)
        assertTrue("what the point lets the plugin do is the detail", sources.detail!!.startsWith("Lets it"))
        assertTrue(view.adds[0].second.single().let { it.ticked && !it.highRisk })
        assertEquals(
            listOf("See whether you are online", "Connect to: listed domains (api.acme.example)"),
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
        assertEquals("Reads the code you copy", clip.line.detail)
        assertEquals(listOf("extension point future.point", "permission made.up"), view.unsupported)

        val shell = view.uses[0]
        assertTrue(shell.optional)
        assertEquals("Acme VPN", shell.providerLabel)
        assertEquals("Official", shell.providerBadge)
        assertEquals("level adb - Restart the app", shell.line.detail)
        assertNull(view.uses[1].providerLabel)
        assertFalse(view.olderPluginFullAccess)
    }

    @Test
    fun `a v1 plugin is shown as full access with its derived asks`() {
        val m = manifest(contract = 1) {}
        val view = PluginConsent.of(m, listOf(record(m)), badgeFor = { "Official" })
        assertTrue(view.olderPluginFullAccess)
        assertEquals(listOf("Status tiles and widgets"), view.adds.single().second.map { it.title })
        // An older plugin's permissions are shown as full access, not as tick boxes.
        assertTrue(view.asks.all { it.line.id == null } && view.can.all { it.id == null })
        assertTrue(view.asks.any { it.line.title.startsWith("Send information to listed apps / to any app") })
        assertTrue(view.asks.any { it.line.title == "Run with droidtop's full access" })
        assertTrue(view.can.any { it.title.startsWith("Check whether listed apps are installed") })
        assertEquals(emptyList<String>(), view.unsupported)
    }
}
