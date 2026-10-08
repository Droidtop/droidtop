package dev.droidtop.pluginhost

import dev.droidtop.runtime.util.Sha256

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Per-plugin keys certified by the plugin master (docs/SPEC.md 12a). Every key
 * here is a throwaway pair generated inside the test; the test plays the
 * master's part itself through [PluginOriginKeys.withMaster].
 */
class PluginCertificatesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val master = freshKeyPair()
    private val pluginKey = freshKeyPair()
    private val legacy = freshKeyPair()
    private val now = 1_800_000_000L

    private fun freshKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun sign(key: PrivateKey, data: ByteArray): String =
        b64(Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(data) }.sign())

    private fun certificate(
        ids: List<String> = listOf("droidtop.sample"),
        subject: KeyPair = pluginKey,
        issuer: KeyPair = master,
        certId: String = "Droidtop/sample-plugin#0",
        notBefore: Long = now - 86_400,
        notAfter: Long = now + 86_400 * 365,
    ): String {
        val spki = b64(subject.public.encoded)
        val signed = PluginCertificates.signedBytes(certId, ids, spki, notBefore, notAfter)
        return JSONObject()
            .put("formatVersion", 1)
            .put("certId", certId)
            .put("pluginIds", JSONArray(ids))
            .put("publicKeySpki", spki)
            .put("keySha256", Sha256.hex(subject.public.encoded))
            .put("notBefore", notBefore)
            .put("notAfter", notAfter)
            .put("issuer", JSONObject().put("keySha256", Sha256.hex(issuer.public.encoded)).put("signature", sign(issuer.private, signed)))
            .toString()
    }

    private fun revocations(sequence: Long, certIds: List<String> = emptyList(), keys: List<String> = emptyList(), issuer: KeyPair = master): String =
        JSONObject()
            .put("formatVersion", 1)
            .put("sequence", sequence)
            .put("certIds", JSONArray(certIds))
            .put("keySha256", JSONArray(keys))
            .put("signature", sign(issuer.private, PluginRevocations.signedBytes(sequence, certIds, keys)))
            .toString()

    private fun withKeys(block: () -> Unit) {
        PluginOriginKeys.withMaster(b64(master.public.encoded)) {
            PluginOriginKeys.withOrigin(PluginOriginKeys.OFFICIAL_ORIGIN, b64(legacy.public.encoded), block)
        }
    }

    private val manifest = "official manifest bytes".toByteArray()

    private fun verify(
        signer: KeyPair,
        cert: String?,
        id: String = "droidtop.sample",
        origin: String = PluginOriginKeys.OFFICIAL_ORIGIN,
        revoked: PluginRevocationList = PluginRevocationList.NONE,
        at: Long? = now,
        userKeys: Map<String, String> = emptyMap(),
    ) = BundleSignature.verifyBundle(manifest, sign(signer.private, manifest), origin, id, cert, userKeys, revoked, at)

    // ----- The signed bytes: the provisioning script pins the same two vectors -----

    @Test
    fun `certificate signed bytes match the shared vector`() {
        val bytes = PluginCertificates.signedBytes(
            "Droidtop/example-plugin#0", listOf("droidtop.example", "droidtop.example.*"), "SPKI", 1790000000, 1885000000,
        )
        assertEquals(
            "droidtop-plugin-cert-v1\nid:Droidtop/example-plugin#0\nplugins:droidtop.example,droidtop.example.*\n" +
                "key:SPKI\nnotBefore:1790000000\nnotAfter:1885000000\n",
            bytes.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `revocation signed bytes match the shared vector`() {
        val bytes = PluginRevocations.signedBytes(3, listOf("b#1", "a#0"), listOf("AB".repeat(32)))
        assertEquals(
            "droidtop-plugin-revocations-v1\nsequence:3\ncert:a#0\ncert:b#1\nkey:${"ab".repeat(32)}\n",
            bytes.toString(Charsets.UTF_8),
        )
    }

    // ----- verifyBundle -----

    @Test
    fun `a certified official bundle verifies and binds approval to the master`() = withKeys {
        val verdict = verify(pluginKey, certificate())
        assertTrue(verdict is BundleVerdict.Verified)
        verdict as BundleVerdict.Verified
        assertEquals(Sha256.hex(master.public.encoded), verdict.anchorSha256)
        assertEquals("Droidtop/sample-plugin#0", verdict.certificate?.certId)
    }

    @Test
    fun `a certificate for another plugin id is refused`() = withKeys {
        val verdict = verify(pluginKey, certificate(ids = listOf("droidtop.other")))
        assertTrue((verdict as BundleVerdict.Refused).reason.contains("not \"droidtop.sample\""))
    }

    @Test
    fun `a namespace entry covers ids under it but not the bare prefix`() = withKeys {
        val cert = certificate(ids = listOf("droidtop.sample.*"))
        assertTrue(verify(pluginKey, cert, id = "droidtop.sample.tile") is BundleVerdict.Verified)
        assertTrue(verify(pluginKey, cert, id = "droidtop.sampler") is BundleVerdict.Refused)
        assertTrue(verify(pluginKey, cert, id = "droidtop.sample") is BundleVerdict.Refused)
    }

    @Test
    fun `a certificate not signed by the master is refused`() = withKeys {
        val impostor = freshKeyPair()
        assertTrue(verify(pluginKey, certificate(issuer = impostor)) is BundleVerdict.Refused)
    }

    @Test
    fun `a manifest signed by a key other than the certified one is refused`() = withKeys {
        val other = freshKeyPair()
        val verdict = verify(other, certificate())
        assertTrue((verdict as BundleVerdict.Refused).reason.contains("key certified for"))
    }

    @Test
    fun `a tampered certificate field breaks the master's signature`() = withKeys {
        val tampered = JSONObject(certificate()).put("pluginIds", JSONArray(listOf("droidtop.sample", "droidtop.other"))).toString()
        assertTrue(verify(pluginKey, tampered, id = "droidtop.other") is BundleVerdict.Refused)
    }

    @Test
    fun `without a pinned master a certified bundle is refused, saying why`() {
        PluginOriginKeys.withOrigin(PluginOriginKeys.OFFICIAL_ORIGIN, b64(legacy.public.encoded)) {
            val verdict = verify(pluginKey, certificate())
            assertTrue((verdict as BundleVerdict.Refused).reason.contains("pins no plugin master key"))
        }
    }

    @Test
    fun `expiry refuses a new install but not the re-verification of an installed plugin`() = withKeys {
        val cert = certificate(notBefore = now - 1000, notAfter = now - 1)
        assertTrue(verify(pluginKey, cert, at = now) is BundleVerdict.Refused)
        assertTrue(verify(pluginKey, cert, at = null) is BundleVerdict.Verified)
    }

    @Test
    fun `revocation by certificate id or by key refuses`() = withKeys {
        val byId = PluginRevocationList(1, setOf("Droidtop/sample-plugin#0"), emptySet())
        val byKey = PluginRevocationList(1, emptySet(), setOf(Sha256.hex(pluginKey.public.encoded)))
        assertTrue(verify(pluginKey, certificate(), revoked = byId) is BundleVerdict.Refused)
        assertTrue(verify(pluginKey, certificate(), revoked = byKey) is BundleVerdict.Refused)
        assertTrue(verify(pluginKey, certificate(), revoked = PluginRevocationList(1, setOf("Droidtop/other#0"), emptySet())) is BundleVerdict.Verified)
    }

    @Test
    fun `a legacy official bundle without a certificate still verifies until its key is revoked`() = withKeys {
        val verdict = verify(legacy, null)
        assertEquals(Sha256.hex(legacy.public.encoded), (verdict as BundleVerdict.Verified).anchorSha256)
        val withdrawn = PluginRevocationList(1, emptySet(), setOf(Sha256.hex(legacy.public.encoded)))
        assertTrue(verify(legacy, null, revoked = withdrawn) is BundleVerdict.Refused)
    }

    @Test
    fun `a plugin key cannot sign an official bundle without its certificate`() = withKeys {
        assertTrue(verify(pluginKey, null) is BundleVerdict.Refused)
    }

    @Test
    fun `a user-trusted origin verifies by its own key and ignores a certificate`() = withKeys {
        val acme = freshKeyPair()
        val userKeys = mapOf("acme" to b64(acme.public.encoded))
        val verdict = verify(acme, certificate(), id = "acme.tool", origin = "acme", userKeys = userKeys)
        assertEquals(Sha256.hex(acme.public.encoded), (verdict as BundleVerdict.Verified).anchorSha256)
        assertNull(verdict.certificate)
        // A master-certified key gives no standing for another origin.
        assertTrue(verify(pluginKey, certificate(ids = listOf("acme.tool")), id = "acme.tool", origin = "acme", userKeys = userKeys) is BundleVerdict.Refused)
    }

    @Test
    fun `the official anchors are the legacy key and the master`() = withKeys {
        val legacyFp = Sha256.hex(legacy.public.encoded)
        val masterFp = Sha256.hex(master.public.encoded)
        assertEquals(setOf(legacyFp, masterFp), PluginOriginKeys.officialAnchorFingerprints())
        assertTrue(PluginOriginKeys.sameTrustAnchor(legacyFp, masterFp))
        assertFalse(PluginOriginKeys.sameTrustAnchor(legacyFp, Sha256.hex(pluginKey.public.encoded)))
        assertFalse(PluginOriginKeys.sameTrustAnchor("", masterFp))
    }

    // ----- The revocation list -----

    @Test
    fun `a revocation list is kept only when the master signed it and its sequence is newer`() = withKeys {
        val root = tmp.newFolder("plugins")
        assertEquals(PluginRevocationList.NONE, PluginRevocations.load(root))
        assertTrue(PluginRevocations.accept(root, revocations(2, certIds = listOf("Droidtop/sample-plugin#0"))))
        assertTrue(PluginRevocations.load(root).revokesCert("Droidtop/sample-plugin#0"))
        // Replaying an older list cannot un-revoke anything.
        assertFalse(PluginRevocations.accept(root, revocations(1)))
        assertFalse(PluginRevocations.accept(root, revocations(2)))
        // A list the master did not sign is ignored.
        assertFalse(PluginRevocations.accept(root, revocations(5, issuer = freshKeyPair())))
        assertEquals(2L, PluginRevocations.load(root).sequence)
        assertTrue(PluginRevocations.accept(root, revocations(3)))
        assertFalse(PluginRevocations.load(root).revokesCert("Droidtop/sample-plugin#0"))
    }

    @Test
    fun `a stored revocation list that no longer verifies counts as none`() = withKeys {
        val root = tmp.newFolder("plugins")
        assertTrue(PluginRevocations.accept(root, revocations(2, certIds = listOf("x#0"))))
        val file = File(root, PluginRevocations.FILE_NAME)
        file.writeText(JSONObject(file.readText()).put("certIds", JSONArray(listOf("y#0"))).toString())
        assertEquals(PluginRevocationList.NONE, PluginRevocations.load(root))
    }

    // ----- End to end through the installer -----

    private val payload = "payload bytes".toByteArray()

    private fun bundle(signer: KeyPair, cert: String?, payloadBytes: ByteArray = payload, id: String = "droidtop.sample"): File {
        val manifestBytes = JSONObject().apply {
            put("id", id)
            put("origin", PluginOriginKeys.OFFICIAL_ORIGIN)
            put("label", "Sample")
            put("kind", "native_bundle")
            put("capabilities", JSONArray(listOf("status_tile")))
            put("contractVersion", PLUGIN_CONTRACT_VERSION)
            put("abis", JSONArray(listOf("arm64-v8a", "x86_64")))
            put("entryClass", "dev.droidtop.samples.statustile.StatusTilePlugin")
            put("payload", JSONArray(listOf(JSONObject().put("path", "classes.jar").put("sha256", Sha256.hex(payloadBytes)))))
        }.toString().toByteArray()
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(XZCompressorOutputStream(out)).use { tar ->
            fun put(name: String, bytes: ByteArray) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
            put("classes.jar", payloadBytes)
            put("manifest.json", manifestBytes)
            put("manifest.sig", sign(signer.private, manifestBytes).toByteArray())
            if (cert != null) put(PluginCertificates.FILE_NAME, cert.toByteArray())
        }
        return tmp.newFile("bundle-${System.nanoTime()}.droidplugin.tar.xz").apply { writeBytes(out.toByteArray()) }
    }

    private fun installed(result: PluginInstallResult): PluginRecord {
        assertTrue("expected Installed, got $result", result is PluginInstallResult.Installed)
        return (result as PluginInstallResult.Installed).record
    }

    @Test
    fun `a certified bundle installs, keeps its certificate, and stops running once revoked`() = withKeys {
        val root = tmp.newFolder("plugins")
        val record = installed(PluginBundleInstaller.install(bundle(pluginKey, certificate()), root, nowEpochSeconds = now))
        assertEquals(Sha256.hex(master.public.encoded), record.approvedKeySha256)
        assertTrue(File(root, "droidtop.sample/${PluginCertificates.FILE_NAME}").isFile)
        assertNull(PluginBundleInstaller.verifyInstalled(root, record))

        assertTrue(PluginRevocations.accept(root, revocations(1, certIds = listOf("Droidtop/sample-plugin#0"))))
        assertNotNull(PluginBundleInstaller.verifyInstalled(root, record))
        val again = PluginBundleInstaller.install(bundle(pluginKey, certificate()), root, nowEpochSeconds = now)
        assertTrue((again as PluginInstallResult.Refused).error.reason.contains("revoked"))
    }

    @Test
    fun `an installer refuses a certified bundle for an id its certificate does not name`() = withKeys {
        val root = tmp.newFolder("plugins")
        val result = PluginBundleInstaller.install(bundle(pluginKey, certificate(ids = listOf("droidtop.sample")), id = "droidtop.other"), root, nowEpochSeconds = now)
        assertTrue(result is PluginInstallResult.Refused)
    }

    @Test
    fun `moving from the legacy key to a certified key keeps approval`() = withKeys {
        val root = tmp.newFolder("plugins")
        val first = installed(PluginBundleInstaller.install(bundle(legacy, null), root, nowEpochSeconds = now))
        PluginBundleInstaller.writeRecord(root, first.copy(trust = PluginTrustState.APPROVED, enabled = true))
        val updated = installed(PluginBundleInstaller.install(bundle(pluginKey, certificate(), payloadBytes = "version two".toByteArray()), root, nowEpochSeconds = now))
        assertEquals(PluginTrustState.APPROVED, updated.trust)
        assertTrue(updated.enabled)
        assertNull(PluginBundleInstaller.verifyInstalled(root, updated))
    }

    @Test
    fun `a rotated plugin key with a new certificate keeps approval`() = withKeys {
        val root = tmp.newFolder("plugins")
        val first = installed(PluginBundleInstaller.install(bundle(pluginKey, certificate()), root, nowEpochSeconds = now))
        PluginBundleInstaller.writeRecord(root, first.copy(trust = PluginTrustState.APPROVED, enabled = true))
        val rotated = freshKeyPair()
        val cert = certificate(subject = rotated, certId = "Droidtop/sample-plugin#1")
        val updated = installed(PluginBundleInstaller.install(bundle(rotated, cert, payloadBytes = "version two".toByteArray()), root, nowEpochSeconds = now))
        assertEquals(PluginTrustState.APPROVED, updated.trust)
    }
}
