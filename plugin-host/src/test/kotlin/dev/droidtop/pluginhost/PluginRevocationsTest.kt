package dev.droidtop.pluginhost

import dev.droidtop.runtime.util.ChainSignature
import dev.droidtop.runtime.util.DroidtopCore
import dev.droidtop.runtime.util.Sha256
import dev.droidtop.testpki.TestPki
import java.io.File
import java.security.KeyPair
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Stored revocation lists (docs/SPEC.md 12a "Revocation") and the bounded legacy path for plugins installed
 * before the certificate format ("Migration from raw keys"). Every key is generated here.
 */
class PluginRevocationsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val coreKey = TestPki.rsa()
    private val core = TestPki.core(coreKey)
    private val now = System.currentTimeMillis() / 1000

    private fun signer(pair: KeyPair, catalog: String, issuer: X509Certificate = core, issuerKey: PrivateKey = coreKey.private) =
        TestPki.cert("revocations $catalog", pair.public).eku(TestPki.REVOCATIONS).uri(ChainSignature.CATALOG_URN + catalog).issuedBy(issuer, issuerKey).build()

    private fun list(sequence: Long, catalog: String = PluginRevocations.OFFICIAL_CATALOG_ID, certs: List<String> = emptyList(), keys: List<String> = emptyList(), signerCert: (KeyPair) -> X509Certificate = { signer(it, catalog) }): String {
        val pair = TestPki.ec()
        val body = JSONObject().put("type", "droidtop-revocations").put("version", 1).put("catalog", catalog).put("sequence", sequence)
            .put("issuedAt", now).put("certSha256", JSONArray(certs)).put("keySha256", JSONArray(keys))
        return TestPki.attached(ChainSignature.Purpose.REVOCATIONS.typ, listOf(signerCert(pair)), pair.private, body.toString().toByteArray())
    }

    @Test
    fun `droidtop's list is kept only under Core, newer, and re-verified on every read`() {
        val root = tmp.newFolder("plugins")
        DroidtopCore.withPinned(core) {
            assertTrue(PluginRevocations.accept(root, list(2, keys = listOf("a".repeat(64))), now))
            assertTrue(PluginRevocations.load(root).revokesKey("a".repeat(64)))
            // Not newer, another catalog's signer, or a chain to another key: nothing changes.
            assertFalse(PluginRevocations.accept(root, list(2, keys = listOf("b".repeat(64))), now))
            assertFalse(PluginRevocations.accept(root, list(3, catalog = "someone/else"), now))
            val otherKey = TestPki.rsa()
            val other = TestPki.core(otherKey)
            assertFalse(PluginRevocations.accept(root, list(4) { signer(it, "droidtop", other, otherKey.private) }, now))
            assertEquals(2L, PluginRevocations.load(root).sequence)
        }
        // Under another pinned Core (or none) the stored list no longer verifies, so it counts as none.
        DroidtopCore.withPinned(TestPki.core(TestPki.rsa())) { assertEquals(0L, PluginRevocations.load(root).sequence) }
        DroidtopCore.withPinned(null) { assertFalse(PluginRevocations.accept(root, list(5), now)) }
    }

    @Test
    fun `an added catalog's list is kept per origin under that catalog's anchor`() {
        val root = tmp.newFolder("plugins-origins")
        val orgKey = TestPki.rsa()
        val org = TestPki.cert("org", orgKey.public).ca(0).selfSigned(orgKey.private).build()
        val anchor = ChainSignature.Anchor(orgKey.public)
        val byOrg = { pair: KeyPair -> signer(pair, "acme/catalog", org, orgKey.private) }
        assertFalse(PluginRevocations.acceptForOrigin(root, "acme", list(2, catalog = "acme/catalog", keys = listOf("c".repeat(64))), anchor, "acme/catalog", now))
        assertTrue(PluginRevocations.acceptForOrigin(root, "acme", list(2, catalog = "acme/catalog", keys = listOf("c".repeat(64)), signerCert = byOrg), anchor, "acme/catalog", now))
        assertTrue(PluginRevocations.load(root).forOrigin("acme").revokesKey("c".repeat(64)))
        assertFalse(PluginRevocations.load(root).revokesKey("c".repeat(64)))
        assertEquals(PluginRevocationList.NONE, PluginRevocations.load(root).forOrigin("other"))
        assertFalse(PluginRevocations.acceptForOrigin(root, "acme", list(1, catalog = "acme/catalog", signerCert = byOrg), anchor, "acme/catalog", now))
    }

    // ----- The legacy path: installed plugins only -----

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun sign(key: PrivateKey, data: ByteArray) = b64(Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(data) }.sign())

    private val manifest = "official manifest bytes".toByteArray()

    /** The old JSON certificate from a P-256 master, built the way the old provisioning tool did. */
    private fun oldCertificate(master: KeyPair, subject: KeyPair, ids: List<String>, certId: String = "Droidtop/sample-plugin#0"): String {
        val spki = b64(subject.public.encoded)
        val signed = "droidtop-plugin-cert-v1\nid:$certId\nplugins:${ids.joinToString(",")}\nkey:$spki\nnotBefore:0\nnotAfter:1\n".toByteArray()
        return JSONObject().put("formatVersion", 1).put("certId", certId).put("pluginIds", JSONArray(ids)).put("publicKeySpki", spki)
            .put("keySha256", Sha256.hex(subject.public.encoded)).put("notBefore", 0).put("notAfter", 1)
            .put("issuer", JSONObject().put("keySha256", Sha256.hex(master.public.encoded)).put("signature", sign(master.private, signed))).toString()
    }

    private fun legacy(signer: KeyPair, cert: String?, origin: String = "droidtop", id: String = "droidtop.sample", userKeys: Map<String, String> = emptyMap(), root: File = tmp.root, revocations: PluginRevocationList = PluginRevocationList.NONE) =
        LegacyBundleSignature.verifyInstalled(manifest, sign(signer.private, manifest), origin, id, cert, userKeys, root, revocations)

    @Test
    fun `an installed legacy official plugin verifies by the legacy key or an old certificate, ignoring expiry`() {
        val legacyKey = TestPki.ec()
        val master = TestPki.ec()
        val repo = TestPki.ec()
        LegacyBundleSignature.withKeys(b64(legacyKey.public.encoded), b64(master.public.encoded)) {
            assertEquals(Sha256.hex(legacyKey.public.encoded), (legacy(legacyKey, null) as BundleVerdict.Verified).anchorSha256)
            val cert = oldCertificate(master, repo, listOf("droidtop.sample"))
            assertEquals(Sha256.hex(master.public.encoded), (legacy(repo, cert) as BundleVerdict.Verified).anchorSha256)
            assertTrue(legacy(repo, cert, id = "droidtop.other") is BundleVerdict.Refused)
            assertTrue(legacy(repo, null) is BundleVerdict.Refused)
            assertTrue(legacy(TestPki.ec(), cert) is BundleVerdict.Refused)
        }
    }

    @Test
    fun `droidtop's Core-signed list stops a legacy plugin by key`() {
        val legacyKey = TestPki.ec()
        val master = TestPki.ec()
        LegacyBundleSignature.withKeys(b64(legacyKey.public.encoded), b64(master.public.encoded)) {
            val withdrawn = PluginRevocationList(dev.droidtop.runtime.util.RevocationList(1, emptySet(), setOf(Sha256.hex(legacyKey.public.encoded))))
            assertTrue(legacy(legacyKey, null, revocations = withdrawn) is BundleVerdict.Refused)
        }
    }

    @Test
    fun `an installed legacy plugin of a user-trusted origin verifies by the key the person trusts`() {
        val acme = TestPki.ec()
        val keys = mapOf("acme" to b64(acme.public.encoded))
        assertTrue(legacy(acme, null, origin = "acme", id = "acme.tool", userKeys = keys) is BundleVerdict.Verified)
        assertTrue(legacy(acme, null, origin = "acme", id = "acme.tool") is BundleVerdict.Refused)
        val own = PluginRevocationList(dev.droidtop.runtime.util.RevocationList.NONE, mapOf("acme" to dev.droidtop.runtime.util.RevocationList(1, emptySet(), setOf(Sha256.hex(acme.public.encoded)))))
        assertTrue(legacy(acme, null, origin = "acme", id = "acme.tool", userKeys = keys, revocations = own) is BundleVerdict.Refused)
    }
}
