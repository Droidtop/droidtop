package dev.droidtop.pluginhost

import java.io.File
import java.security.KeyPairGenerator
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Application interface plugins: their manifest, and droidtop's trust in their app's signature (docs/plugin-api.md 1.3). */
class AppInterfaceTrustTest {
    @get:Rule val tmp = TemporaryFolder()

    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    private fun apk(vararg signers: String, verified: Boolean = true, v2OrV3: Boolean = true) =
        AppInterfaceTrust.ApkCheck(verified, v2OrV3, signers.toSet())

    @Test
    fun `an app signed one way, the way Android reports, and trusted by the person is trusted`() {
        assertEquals(AppInterfaceTrust.Verdict.TrustedByYou(a), AppInterfaceTrust.decide("com.example.app", listOf(apk(a), apk(a)), setOf(a), setOf(a)))
        assertEquals(AppInterfaceTrust.Verdict.NotTrusted(a), AppInterfaceTrust.decide("com.example.app", listOf(apk(a)), setOf(a), emptySet()))
    }

    @Test
    fun `Enginehost signed by its release certificate is Official, and by any other key is not`() {
        val release = "56ad873eefebacfbbd5662b3b8bd0b094b6ac2d86f27933b257080d04951bf35"
        assertEquals(setOf(release), AppInterfaceTrust.OFFICIAL["dev.enginehost"])
        assertEquals(AppInterfaceTrust.Verdict.Official(release), AppInterfaceTrust.decide("dev.enginehost", listOf(apk(release)), setOf(release), emptySet()))
        assertEquals(AppInterfaceTrust.Verdict.NotTrusted(a), AppInterfaceTrust.decide("dev.enginehost", listOf(apk(a)), setOf(a), emptySet()))
        // The pin is per package: another app signed by Enginehost's key is not Official by it.
        assertEquals(AppInterfaceTrust.Verdict.NotTrusted(release), AppInterfaceTrust.decide("com.example.app", listOf(apk(release)), setOf(release), emptySet()))
    }

    @Test
    fun `a signer Android reports but the APK does not carry is refused, as on a device with its checks patched out`() {
        assertEquals(
            AppInterfaceTrust.Verdict.Refused(AppInterfaceTrust.NOT_ITS_SIGNER),
            AppInterfaceTrust.decide("com.example.app", listOf(apk(a)), setOf(b), setOf(a, b)),
        )
    }

    @Test
    fun `a failed check, an old-style signature, a split signed by another key or two signers are refused`() {
        assertEquals(AppInterfaceTrust.Verdict.Refused(AppInterfaceTrust.UNCHECKABLE), AppInterfaceTrust.decide("p.q", listOf(apk(a, verified = false)), setOf(a), setOf(a)))
        assertEquals(AppInterfaceTrust.Verdict.Refused(AppInterfaceTrust.UNCHECKABLE), AppInterfaceTrust.decide("p.q", emptyList(), setOf(a), setOf(a)))
        assertEquals(AppInterfaceTrust.Verdict.Refused(AppInterfaceTrust.SIGNED_OLD_WAY), AppInterfaceTrust.decide("p.q", listOf(apk(a, v2OrV3 = false)), setOf(a), setOf(a)))
        assertEquals(AppInterfaceTrust.Verdict.Refused(AppInterfaceTrust.MANY_SIGNERS), AppInterfaceTrust.decide("p.q", listOf(apk(a), apk(b)), setOf(a), setOf(a)))
        assertEquals(AppInterfaceTrust.Verdict.Refused(AppInterfaceTrust.MANY_SIGNERS), AppInterfaceTrust.decide("p.q", listOf(apk(a, b)), setOf(a, b), setOf(a)))
    }

    private fun manifest(extra: String = "", kind: String = "app_interface", origin: String = "enginehost", id: String = "dev.enginehost") = PluginManifest.fromJson(
        JSONObject(
            """{"id": "$id", "origin": "$origin", "label": "Enginehost", "version": "1.4.0", "kind": "$kind", "contractVersion": 3,
               "app": {"package": "dev.enginehost", "service": "dev.enginehost.droidtop.DroidtopInterfaceService"},
               "provides": [{"point": "saves.roots", "version": 1, "id": "enginehost"}],
               "permissions": [{"id": "saves.exchange", "reason": "x"}$extra]}""",
        ),
    )

    @Test
    fun `an app interface manifest names its app and keeps it through the record`() {
        val m = manifest()!!
        assertEquals(AppTarget("dev.enginehost", "dev.enginehost.droidtop.DroidtopInterfaceService"), m.app)
        assertEquals(PluginKind.APP_INTERFACE, m.kind)
        assertNull(AppInterfaceTrust.manifestProblem(m, "dev.enginehost", "dev.enginehost.droidtop.DroidtopInterfaceService"))
        val record = PluginRecord(m, "d".repeat(64), a, PluginTrustState.PENDING, enabled = false, rootApproved = false, disabledReason = null)
        assertEquals(m.app, PluginRecord.fromJson(record.toJson())!!.manifest.app)
    }

    @Test
    fun `an app interface's id is an Android package name under its app's, any number of segments and any case`() {
        assertNull(AppInterfaceTrust.manifestProblem(manifest(id = "dev.enginehost"), "dev.enginehost", null))
        assertNull(AppInterfaceTrust.manifestProblem(manifest(id = "dev.enginehost.app"), "dev.enginehost", null))
        assertNull(AppInterfaceTrust.manifestProblem(manifest(id = "dev.enginehost.Droidtop_Plugin"), "dev.enginehost", null))
        assertNotNull("never another plugin's id", AppInterfaceTrust.manifestProblem(manifest(id = "f95.updates"), "dev.enginehost", null))
        assertNotNull(AppInterfaceTrust.manifestProblem(manifest(id = "dev.enginehostx"), "dev.enginehost", null))
        assertNotNull(AppInterfaceTrust.manifestProblem(manifest(id = "enginehost"), "dev.enginehost", null))
        assertNotNull(AppInterfaceTrust.manifestProblem(manifest(id = "dev.1enginehost"), "dev.enginehost", null))
        assertNotNull(AppInterfaceTrust.manifestProblem(manifest(id = "dev.engine-host"), "dev.enginehost", null))
        // A bundle keeps "<origin>.<name>", lowercase.
        assertTrue(manifest(kind = "python", id = "dev.enginehost.app")!!.structuralProblems().any { "namespaced" in it })
    }

    @Test
    fun `it must be this app's, never droidtop's origin, and never full access`() {
        assertNotNull(AppInterfaceTrust.manifestProblem(manifest(), "com.other.app", null))
        assertNotNull(AppInterfaceTrust.manifestProblem(manifest(), "dev.enginehost", "dev.enginehost.Other"))
        assertNotNull(AppInterfaceTrust.manifestProblem(manifest(origin = "droidtop"), "dev.enginehost", null))
        assertTrue(manifest(""", {"id": "host.full_trust", "reason": "x"}""")!!.structuralProblems().any { "host.full_trust" in it })
        assertTrue(manifest(""", {"id": "gpu.render", "reason": "x"}""")!!.structuralProblems().any { "gpu.render" in it })
        assertTrue(manifest(kind = "python")!!.structuralProblems().any { "only an app_interface plugin names an app" in it })
    }

    @Test
    fun `an approval carries over an update signed by the same certificate, never another`() {
        val approved = PluginRecord(manifest()!!, "d".repeat(64), a, PluginTrustState.APPROVED, enabled = true, rootApproved = false, disabledReason = null)
        assertTrue(AppInterfaceTrust.carriesApproval(approved, "enginehost", a))
        assertTrue(!AppInterfaceTrust.carriesApproval(approved, "enginehost", b))
        assertTrue(!AppInterfaceTrust.carriesApproval(approved.copy(trust = PluginTrustState.PENDING), "enginehost", a))
    }

    @Test
    fun `app certificates share the store with origin keys and survive its other writes`() {
        val file: File = tmp.newFile("plugin-user-keys.json")
        UserOriginKeys.trustApp(file, "dev.enginehost", a.uppercase())
        assertEquals(setOf(a), UserOriginKeys.appCerts(file, "dev.enginehost"))
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        UserOriginKeys.add(file, "acme", Base64.getEncoder().encodeToString(pair.public.encoded), source = null)
        assertEquals(setOf(a), UserOriginKeys.appCerts(file, "dev.enginehost"))
        assertEquals(setOf("acme"), UserOriginKeys.load(file).keys)
        UserOriginKeys.forgetApp(file, "dev.enginehost")
        assertEquals(emptySet<String>(), UserOriginKeys.appCerts(file, "dev.enginehost"))
        assertEquals(mapOf<String, String>(), UserOriginKeys.appEntries(file))
    }
    @Test
    fun `an app whose plugin this droidtop cannot speak says which side needs updating`() {
        // Enginehost was missing from Plugins altogether (Droidtop/tracker#854).
        fun bytes(json: String) = json.toByteArray(Charsets.UTF_8)
        val newer = AppInterfaceTrust.mismatch("Enginehost", bytes("""{"kind":"app_interface","contractVersion":4}"""), null)
        assertEquals(
            "Enginehost is made for a newer droidtop (its plugin is contract 4; this droidtop speaks 2 to $PLUGIN_MANIFEST_CONTRACT_VERSION). Update droidtop to use it",
            newer,
        )
        val older = AppInterfaceTrust.mismatch("Enginehost", bytes("""{"kind":"app_interface","contractVersion":1}"""), null)
        assertTrue(older!!, older.startsWith("Enginehost needs an update to work with this droidtop (its plugin is contract 1;"))
        assertEquals(
            "Enginehost needs an update to work with this droidtop (droidtop can't read its plugin's manifest)",
            AppInterfaceTrust.mismatch("Enginehost", bytes("""{"kind":"some_new_kind"}"""), null),
        )
        assertEquals(
            "Enginehost needs an update to work with this droidtop (droidtop can't read its plugin's manifest)",
            AppInterfaceTrust.mismatch("Enginehost", bytes("not json"), null),
        )
        // No manifest asset at all is a different refusal, and a readable one in range is no mismatch.
        assertNull(AppInterfaceTrust.mismatch("Enginehost", null, null))
        assertNull(AppInterfaceTrust.mismatch("Enginehost", bytes("""{"kind":"app_interface","contractVersion":3}"""), manifest()!!))
    }
}
