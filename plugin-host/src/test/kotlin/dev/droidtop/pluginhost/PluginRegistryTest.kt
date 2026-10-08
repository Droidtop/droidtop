package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The registries against docs/plugin-api.md, and the install-time rules that use them. */
class PluginRegistryTest {
    private val doc: String by lazy {
        // Gradle runs unit tests with the module directory as the working directory.
        listOf(File("../docs/plugin-api.md"), File("docs/plugin-api.md")).first { it.exists() }.readText()
    }

    @Test
    fun `every permission row in section 4_1 is in the registry with its tier and label`() {
        val table = doc.substring(doc.indexOf("| Permission | Tier | Plain-language label"))
        val rows = table.lines().drop(2).takeWhile { it.startsWith("|") }.map { line ->
            line.trim().trim('|').split("|").map { it.trim() }
        }
        assertEquals(64, rows.size)
        assertEquals(64, PluginPermissions.all.size)
        for (cells in rows) {
            val id = Regex("`([^`]+)`").find(cells[0])!!.groupValues[1]
            val entry = PluginPermissions.find(id)
            assertNotNull("missing $id", entry)
            entry!!
            assertEquals("tier of $id", cells[1].substringBefore(" ").uppercase(), entry.tier.name)
            assertEquals("label of $id", cells[2].replace("*", ""), entry.label)
            assertEquals("official-only of $id", cells[0].contains("†"), entry.officialOnly)
        }
    }

    @Test
    fun `extension point risks match the catalogue`() {
        val found = Regex("(?:EP|Hook)\\s+`([a-z_.]+)@\\d+`[^R]{0,80}?Risk\\s+(\\w+)", RegexOption.DOT_MATCHES_ALL)
            .findAll(doc).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue("expected the catalogue's entries, got $found", found.size >= 20)
        for ((id, risk) in found) {
            val point = ExtensionPoints.find(id)
            assertNotNull("missing $id", point)
            assertEquals("risk of $id", risk.uppercase(), point!!.risk.name)
        }
    }

    @Test
    fun `the points that need consent are exactly the ones section 4_2 lists`() {
        val consent = ExtensionPoints.all.filter { it.risk.needsConsent }.map { it.id }.toSet()
        assertEquals(
            setOf("library.sources", "saves.sync", "launch.provider", "files.handler", "onboarding.step", "intents.in", "containers.packages", "jobs.service"),
            consent,
        )
    }

    @Test
    fun `provide consent items resolve through the extension registry`() {
        assertTrue(PluginPermissions.isSupported("provide:library.sources"))
        assertFalse(PluginPermissions.isSupported("provide:nothing.here"))
        assertEquals(PermissionTier.DANGEROUS, PluginPermissions.tierFor("provide:library.sources"))
        assertEquals(PermissionTier.NORMAL, PluginPermissions.tierFor("provide:ui.status_tile"))
        assertEquals(PermissionTier.CRITICAL, PluginPermissions.tierFor("provide:containers.packages"))
        assertEquals(PermissionTier.NORMAL, PluginPermissions.tierFor("apps.intents.out"))
        assertEquals(PermissionTier.DANGEROUS, PluginPermissions.tierFor("apps.intents.out", scopeIsAny = true))
        assertNull(PluginPermissions.tierFor("made.up"))
    }

    private fun manifest(origin: String = "acme", build: (JSONObject) -> Unit = {}): PluginManifest {
        val json = JSONObject().apply {
            put("id", "$origin.thing")
            put("origin", origin)
            put("kind", "native_bundle")
            put("capabilities", JSONArray())
            put("contractVersion", 2)
            put("abis", JSONArray(listOf("arm64-v8a", "x86_64")))
            put("entryClass", "acme.Thing")
            put("payload", JSONArray(listOf(JSONObject().put("path", "classes.jar").put("sha256", "a".repeat(64)))))
            put("provides", JSONArray(listOf(JSONObject().put("point", "ui.status_tile"))))
            build(this)
        }
        return PluginManifest.fromJson(json)!!
    }

    @Test
    fun `unknown ids are reported as unsupported and do not fail the install`() {
        val m = manifest {
            it.put("provides", JSONArray(listOf(JSONObject().put("point", "ui.status_tile"), JSONObject().put("point", "future.point"), JSONObject().put("point", "ui.settings").put("version", 9))))
            it.put("permissions", JSONArray(listOf(JSONObject().put("id", "net.state"), JSONObject().put("id", "made.up"), JSONObject().put("id", "acme.thing.own"))))
            it.put("subscribes", JSONArray(listOf(JSONObject().put("event", "library.default_player_changed"), JSONObject().put("event", "no.such.event"))))
            it.put(
                "exports",
                JSONArray(listOf(JSONObject().put("api", "acme.thing.own").put("permissions", JSONArray(listOf(JSONObject().put("id", "acme.thing.own").put("risk", "low").put("label", "x")))))),
            )
        }
        assertEquals(
            listOf("extension point future.point", "extension point ui.settings version 9", "permission made.up", "event no.such.event"),
            m.unsupportedDeclarations(),
        )
        assertTrue(m.structuralProblems().isEmpty())
    }

    @Test
    fun `a required requires on a privileged api is a structural problem`() {
        fun problems(api: String, optional: Boolean) =
            manifest { it.put("requires", JSONArray(listOf(JSONObject().put("api", api).put("optional", optional)))) }.structuralProblems()
        assertTrue(problems("priv.shell", optional = false).any { it.contains("must be optional") })
        assertTrue(problems("root.modules", optional = false).any { it.contains("must be optional") })
        assertTrue(problems("priv.shell", optional = true).isEmpty())
        assertTrue(problems("sync.folders", optional = false).isEmpty())
    }

    @Test
    fun `an official-only permission or point from another origin is a structural problem`() {
        val m = manifest {
            it.put("permissions", JSONArray(listOf(JSONObject().put("id", "a11y.bridge"))))
            it.put("provides", JSONArray(listOf(JSONObject().put("point", "onboarding.step"))))
        }
        val problems = m.structuralProblems()
        assertTrue(problems.any { it.contains("a11y.bridge") })
        assertTrue(problems.any { it.contains("onboarding.step") })
        val official = manifest(origin = "droidtop") {
            it.put("permissions", JSONArray(listOf(JSONObject().put("id", "a11y.bridge"))))
        }
        assertTrue(official.structuralProblems().isEmpty())
    }

    @Test
    fun `a v1 manifest derives nothing the registries do not know`() {
        val json = JSONObject().apply {
            put("id", "acme.old"); put("origin", "acme"); put("kind", "native_bundle")
            put("capabilities", JSONArray(PluginCapability.entries.map { it.id }))
            put("contractVersion", 1); put("requestsRoot", true)
            put("boundServiceTargets", JSONArray(listOf("org.example")))
            put("subscribedEvents", JSONArray(listOf("default_player_changed")))
            put("abis", JSONArray(listOf("arm64-v8a", "x86_64"))); put("entryClass", "acme.Old")
            put("payload", JSONArray(listOf(JSONObject().put("path", "classes.jar").put("sha256", "a".repeat(64)))))
        }
        val m = PluginManifest.fromJson(json)!!
        assertEquals(emptyList<String>(), m.unsupportedDeclarations())
        assertEquals(emptyList<String>(), m.structuralProblems())
    }
}
