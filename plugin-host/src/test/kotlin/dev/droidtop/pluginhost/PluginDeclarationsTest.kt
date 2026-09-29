package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract 2 manifest fields (docs/plugin-api.md 1.2) and the v1 translation (docs/plugin-api.md 6). */
class PluginDeclarationsTest {
    private fun base(contractVersion: Int, capabilities: List<String> = emptyList()): JSONObject = JSONObject().apply {
        put("id", "acme.vpn-tile")
        put("origin", "acme")
        put("label", "Acme VPN")
        put("kind", "native_bundle")
        put("capabilities", JSONArray(capabilities))
        put("contractVersion", contractVersion)
        put("abis", JSONArray(listOf("arm64-v8a", "x86_64")))
        put("entryClass", "acme.Vpn")
        put("payload", JSONArray(listOf(JSONObject().put("path", "classes.jar").put("sha256", "a".repeat(64)))))
    }

    private fun v2Json(): JSONObject = base(2).apply {
        put(
            "provides",
            JSONArray(
                listOf(
                    JSONObject().put("point", "ui.status_tile").put("version", 1).put("id", "state").put("label", "VPN")
                        .put("surfaces", JSONArray(listOf("gaming.quick_menu", "launcher.widget"))),
                    JSONObject().put("point", "future.point").put("version", 3),
                ),
            ),
        )
        put(
            "permissions",
            JSONArray(
                listOf(
                    JSONObject().put("id", "net.domains").put("domains", JSONArray(listOf("api.acme.example"))).put("reason", "Checks the tunnel").put("required", true),
                    JSONObject().put("id", "made.up.permission"),
                ),
            ),
        )
        put("subscribes", JSONArray(listOf(JSONObject().put("event", "net.changed").put("version", 1))))
        put(
            "exports",
            JSONArray(
                listOf(
                    JSONObject().put("api", "acme.vpn.status").put("version", "1.2")
                        .put("attributes", JSONObject().put("level", "adb"))
                        .put("ops", JSONArray(listOf(JSONObject().put("op", "get").put("permission", "acme.vpn.status.read").put("job", true))))
                        .put("permissions", JSONArray(listOf(JSONObject().put("id", "acme.vpn.status.read").put("risk", "low").put("label", "See VPN state")))),
                ),
            ),
        )
        put(
            "requires",
            JSONArray(listOf(JSONObject().put("api", "priv.shell").put("version", "1.0").put("optional", true).put("minLevel", "adb").put("reason", "Restart the app"))),
        )
        put("someFutureTopLevelField", "ignored")
    }

    @Test
    fun `parses every v2 field and keeps unknown ids`() {
        val m = PluginManifest.fromJson(v2Json())!!
        val v2 = m.v2
        assertEquals(listOf("ui.status_tile", "future.point"), v2.provides.map { it.point })
        assertEquals("state", v2.provides[0].id)
        assertEquals(listOf("gaming.quick_menu", "launcher.widget"), v2.provides[0].surfaces())
        assertEquals(3, v2.provides[1].version)
        assertEquals(listOf("net.domains", "made.up.permission"), v2.permissions.map { it.id })
        assertTrue(v2.permissions[0].required)
        assertEquals("Checks the tunnel", v2.permissions[0].reason)
        assertTrue(v2.permissions[0].extra.contains("api.acme.example"))
        assertEquals(listOf(EventSubscription("net.changed", 1)), v2.subscribes)
        val export = v2.exports.single()
        assertEquals("1.2", export.version)
        assertEquals(ExportedOp("get", "acme.vpn.status.read", job = true), export.ops.single())
        assertEquals(ProvidedPermission("acme.vpn.status.read", "low", "See VPN state"), export.permissions.single())
        val req = v2.requires.single()
        assertTrue(req.optional)
        assertEquals("adb", req.minLevel)
        assertEquals("Restart the app", req.reason)
        // The status tile point is also served as the v1 capability it replaces.
        assertEquals(setOf(PluginCapability.STATUS_TILE), m.capabilities)
    }

    @Test
    fun `a v2 manifest round trips through PluginRecord unchanged`() {
        val manifest = PluginManifest.fromJson(v2Json())!!
        val record = PluginRecord(manifest, "b".repeat(64), "", PluginTrustState.PENDING, enabled = false, rootApproved = false, disabledReason = null)
        val back = PluginRecord.fromJson(JSONObject(record.toJson().toString()))!!
        assertEquals(manifest.v2, back.manifest.v2)
        assertEquals(manifest, back.manifest)
    }

    @Test
    fun `a v2 plugin that maps to no capability is still installable`() {
        val json = base(2).put("provides", JSONArray(listOf(JSONObject().put("point", "ui.tray"))))
        val m = PluginManifest.fromJson(json)
        assertNotNull(m)
        assertTrue(m!!.capabilities.isEmpty())
    }

    @Test
    fun `a v2 manifest with no capability and no provides is refused`() {
        assertNull(PluginManifest.fromJson(base(2)))
    }

    @Test
    fun `a v1 manifest yields the implicit v2 set`() {
        val json = base(1, listOf("acquire_content", "app_status", "library_action")).apply {
            put("requestsRoot", true)
            put("boundServiceTargets", JSONArray(listOf("org.example.b", "org.example.a")))
            put("subscribedEvents", JSONArray(listOf("default_player_changed")))
        }
        val v2 = PluginManifest.fromJson(json)!!.v2
        assertEquals(listOf("library.sources", "ui.context_action", "apps.bridge"), v2.provides.map { it.point })
        assertTrue(v2.provides[1].extra.contains("game"))
        val ids = v2.permissions.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(
            setOf(
                "provide:library.sources", "library.folders.write", "net.any",
                "provide:ui.context_action", "library.read",
                "provide:apps.bridge",
                "host.full_trust", "apps.check", "apps.launch", "apps.intents.out",
                "priv.shell.root", "apps.bind",
            ),
            ids.toSet(),
        )
        assertTrue(v2.permissions.single { it.id == "apps.bind" }.extra.contains("org.example.a"))
        assertEquals(listOf(EventSubscription("library.default_player_changed", 2)), v2.subscribes)
        val root = v2.requires.single()
        assertEquals(RequiredApi("priv.shell", "1.0", optional = true, minLevel = "root"), root)
    }

    @Test
    fun `a plain v1 status tile gets only the always implied set`() {
        val v2 = PluginManifest.fromJson(base(1, listOf("status_tile")))!!.v2
        assertEquals(listOf("ui.status_tile"), v2.provides.map { it.point })
        assertEquals(setOf("provide:ui.status_tile", "host.full_trust", "apps.check", "apps.launch", "apps.intents.out"), v2.permissions.map { it.id }.toSet())
        assertTrue(v2.requires.isEmpty())
    }

    @Test
    fun `a v1 record re-derives its v2 set on read`() {
        val manifest = PluginManifest.fromJson(base(1, listOf("settings_rows")))!!
        val record = PluginRecord(manifest, "b".repeat(64), "", PluginTrustState.APPROVED, enabled = true, rootApproved = false, disabledReason = null)
        val back = PluginRecord.fromJson(JSONObject(record.toJson().toString()))!!
        assertEquals(manifest.v2, back.manifest.v2)
        val strippedJson = record.toJson().apply { remove("provides"); remove("permissions") }
        assertEquals(manifest.v2, PluginRecord.fromJson(strippedJson)!!.manifest.v2)
    }
}
