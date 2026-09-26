package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManifestTest {
    private fun manifestJson(
        id: String = "droidtop.sample-statustile",
        origin: String = "droidtop",
        kind: String = "native_bundle",
        capabilities: List<String> = listOf("status_tile"),
        contractVersion: Int = PLUGIN_CONTRACT_VERSION,
        abis: List<String> = listOf("arm64-v8a", "x86_64"),
        payload: List<Pair<String, String>> = listOf("classes.jar" to "a".repeat(64)),
        entryClass: String? = "dev.droidtop.samples.statustile.StatusTilePlugin",
        runtimeVersion: String? = null,
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("origin", origin)
        put("label", "Sample status tile")
        put("kind", kind)
        // JSONObject.put(String, Object) is what Kotlin resolves for a
        // List<T> argument (not the Collection overload that would wrap
        // it), so a bare `put("key", someList)` stores the raw
        // ArrayList itself rather than a JSONArray -- the production
        // parser's json.optJSONArray("...") then sees the wrong type and
        // silently treats the field as absent. Every list-valued field
        // below is wrapped in JSONArray(...) explicitly so this manifest
        // actually round-trips the way a real plugin bundle's does.
        put("capabilities", JSONArray(capabilities))
        put("contractVersion", contractVersion)
        put("abis", JSONArray(abis))
        put("entryClass", entryClass ?: JSONObject.NULL)
        put("runtimeVersion", runtimeVersion ?: JSONObject.NULL)
        put(
            "payload",
            JSONArray(payload.map { (path, sha) -> JSONObject().put("path", path).put("sha256", sha) }),
        )
    }

    @Test
    fun `parses a complete manifest`() {
        val manifest = PluginManifest.fromJson(manifestJson())
        assertNotNull(manifest)
        assertEquals(PluginKind.NATIVE_BUNDLE, manifest!!.kind)
        assertEquals(setOf(PluginCapability.STATUS_TILE), manifest.capabilities)
        assertTrue(manifest.structuralProblems().isEmpty())
    }

    @Test
    fun `rejects a manifest with no id`() {
        val json = manifestJson().apply { put("id", "") }
        assertNull(PluginManifest.fromJson(json))
    }

    @Test
    fun `rejects a manifest whose capability is not in the closed set`() {
        val json = manifestJson(capabilities = listOf("run_arbitrary_code"))
        assertNull(PluginManifest.fromJson(json))
    }

    @Test
    fun `rejects a payload entry with no hash`() {
        val json = manifestJson()
        val payload = json.getJSONArray("payload")
        payload.getJSONObject(0).remove("sha256")
        assertNull(PluginManifest.fromJson(json))
    }

    @Test
    fun `flags a contract version newer than this build understands`() {
        val manifest = PluginManifest.fromJson(manifestJson(contractVersion = PLUGIN_CONTRACT_VERSION + 1))!!
        assertTrue(manifest.structuralProblems().any { it.contains("contractVersion") })
    }

    @Test
    fun `flags a native bundle with a native library but incomplete abis`() {
        val json = manifestJson(
            abis = listOf("arm64-v8a"),
            payload = listOf("classes.jar" to "a".repeat(64), "lib/arm64-v8a/libsample.so" to "b".repeat(64)),
        )
        val manifest = PluginManifest.fromJson(json)!!
        assertTrue(manifest.structuralProblems().any { it.contains("abis") })
    }

    @Test
    fun `flags a native bundle with no entry class`() {
        val manifest = PluginManifest.fromJson(manifestJson(entryClass = null))!!
        assertTrue(manifest.structuralProblems().any { it.contains("entryClass") })
    }

    @Test
    fun `flags an id not namespaced under its origin`() {
        val manifest = PluginManifest.fromJson(manifestJson(id = "somethingelse.sample"))!!
        assertTrue(manifest.structuralProblems().any { it.contains("namespaced") })
    }

    @Test
    fun `flags a python plugin with no plugin py in its payload`() {
        val json = manifestJson(
            id = "droidtop.sample-py",
            kind = "python",
            abis = emptyList(),
            payload = listOf("not-the-right-file.py" to "a".repeat(64)),
            entryClass = null,
        )
        val manifest = PluginManifest.fromJson(json)!!
        assertTrue(manifest.structuralProblems().any { it.contains("plugin.py") })
    }

    @Test
    fun `accepts a python plugin that ships plugin py`() {
        val json = manifestJson(
            id = "droidtop.sample-py",
            kind = "python",
            abis = emptyList(),
            payload = listOf("plugin.py" to "a".repeat(64)),
            entryClass = null,
        )
        val manifest = PluginManifest.fromJson(json)!!
        assertEquals(PluginKind.PYTHON, manifest.kind)
        assertTrue(manifest.structuralProblems().isEmpty())
    }

    @Test
    fun `flags a flutter_embed plugin with no runtimeVersion`() {
        val json = manifestJson(
            id = "droidtop.sample-flutter",
            kind = "flutter_embed",
            abis = listOf("arm64-v8a", "x86_64"),
            payload = listOf(
                "lib/arm64-v8a/libapp.so" to "a".repeat(64),
                "lib/x86_64/libapp.so" to "b".repeat(64),
                "flutter_assets/kernel_blob.bin" to "c".repeat(64),
            ),
            entryClass = null,
            runtimeVersion = null,
        )
        val manifest = PluginManifest.fromJson(json)!!
        assertTrue(manifest.structuralProblems().any { it.contains("runtimeVersion") })
    }

    @Test
    fun `flags a flutter_embed plugin with no flutter_assets`() {
        val json = manifestJson(
            id = "droidtop.sample-flutter",
            kind = "flutter_embed",
            abis = listOf("arm64-v8a", "x86_64"),
            payload = listOf(
                "lib/arm64-v8a/libapp.so" to "a".repeat(64),
                "lib/x86_64/libapp.so" to "b".repeat(64),
            ),
            entryClass = null,
            runtimeVersion = "af7e796e161ae0bb1ff0758c71a7105418bd9ded",
        )
        val manifest = PluginManifest.fromJson(json)!!
        assertTrue(manifest.structuralProblems().any { it.contains("flutter_assets") })
    }

    @Test
    fun `accepts a complete flutter_embed plugin`() {
        val json = manifestJson(
            id = "droidtop.sample-flutter",
            kind = "flutter_embed",
            abis = listOf("arm64-v8a", "x86_64"),
            payload = listOf(
                "lib/arm64-v8a/libapp.so" to "a".repeat(64),
                "lib/x86_64/libapp.so" to "b".repeat(64),
                "flutter_assets/kernel_blob.bin" to "c".repeat(64),
            ),
            entryClass = null,
            runtimeVersion = "af7e796e161ae0bb1ff0758c71a7105418bd9ded",
        )
        val manifest = PluginManifest.fromJson(json)!!
        assertEquals(PluginKind.FLUTTER_EMBED, manifest.kind)
        assertTrue(manifest.structuralProblems().isEmpty())
    }
}
