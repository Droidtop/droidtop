package dev.droidtop.pluginhost

import dev.droidtop.runtime.util.CatalogSignature
import dev.droidtop.runtime.util.DroidtopSigningRoot
import dev.droidtop.shared.trust.ChainSignature
import dev.droidtop.shared.trust.Sha256
import dev.droidtop.testpki.TestPki
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
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
 * The signing-key rotation of 2026-11-04 (docs/SPEC.md 12a "Signatures: certificate chains to droidtop's signing root",
 * Droidtop/tracker#876 and #550): the new root is trusted with no end, the old one until
 * [DroidtopSigningRoot.OLD_ROOT_EXPIRES_AT] and not a second longer, at every place a chain to droidtop's root is
 * accepted. Both roots here are throwaway test keys.
 */
class SigningRootRotationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val newKey = TestPki.rsa()
    private val newRoot = TestPki.core(newKey)
    private val oldKey = TestPki.rsa()
    private val oldRoot = TestPki.core(oldKey)

    private val lastGood: Instant = Instant.parse("2026-11-11T23:59:59Z")
    private val tooLate: Instant = lastGood.plusSeconds(1)
    private val duringWindow: Instant = Instant.parse("2026-11-05T00:00:00Z")
    private val manifest = "rotation manifest".toByteArray()
    private val catalogBytes = "{\"catalog\":true}".toByteArray()

    private fun <T> at(instant: Instant, block: () -> T): T =
        DroidtopSigningRoot.withPinned(newRoot.publicKey, oldRoot.publicKey, instant, block)

    private fun bundle(root: X509Certificate, rootKey: KeyPair, origin: String = "droidtop"): Pair<String, X509Certificate> {
        val leafKey = TestPki.ec()
        val leaf = TestPki.pluginLeaf(leafKey, origin, root, rootKey.private)
        return TestPki.detached(ChainSignature.Purpose.PLUGIN.typ, listOf(leaf), leafKey.private, manifest) to leaf
    }

    private fun verify(jws: String, revocations: PluginRevocationList = PluginRevocationList.NONE) =
        BundleSignature.verifyBundle(manifest, jws, "droidtop", "droidtop.sample", emptyMap(), revocations)

    private fun revocationList(root: X509Certificate, rootKey: KeyPair, sequence: Long, keys: List<String>): String {
        val signerKey = TestPki.ec()
        val signer = TestPki.cert("revocations", signerKey.public).eku(TestPki.REVOCATIONS)
            .uri(ChainSignature.CATALOG_URN + PluginRevocations.OFFICIAL_CATALOG_ID).issuedBy(root, rootKey.private).build()
        val body = JSONObject().put("type", "droidtop-revocations").put("version", 1).put("catalog", PluginRevocations.OFFICIAL_CATALOG_ID)
            .put("sequence", sequence).put("issuedAt", 1_800_000_000L).put("certSha256", JSONArray()).put("keySha256", JSONArray(keys))
        return TestPki.attached(ChainSignature.Purpose.REVOCATIONS.typ, listOf(signer), signerKey.private, body.toString().toByteArray())
    }

    @Test
    fun `the expiry constant is the owner's seven days`() {
        assertEquals(Instant.parse("2026-11-11T23:59:59Z"), DroidtopSigningRoot.OLD_ROOT_EXPIRES_AT)
    }

    @Test
    fun `an old-root bundle verifies up to the expiry instant and is refused one second later`() {
        val (jws, _) = bundle(oldRoot, oldKey)
        at(duringWindow) { assertEquals(Sha256.hex(oldRoot.publicKey.encoded), (verify(jws) as BundleVerdict.Verified).anchorSha256) }
        at(lastGood) { assertTrue(verify(jws) is BundleVerdict.Verified) }
        at(tooLate) { assertTrue(verify(jws) is BundleVerdict.Refused) }
    }

    @Test
    fun `a new-root bundle verifies before and after the old root expires`() {
        val (jws, _) = bundle(newRoot, newKey)
        for (instant in listOf(duringWindow, lastGood, tooLate, tooLate.plusSeconds(86_400L * 400))) {
            at(instant) { assertEquals(Sha256.hex(newRoot.publicKey.encoded), (verify(jws) as BundleVerdict.Verified).anchorSha256) }
        }
    }

    @Test
    fun `a catalog signed under the old root is accepted until the expiry and refused after`() {
        val leafKey = TestPki.ec()
        val leaf = TestPki.cert("catalog", leafKey.public).eku(TestPki.CATALOG).uri(ChainSignature.CATALOG_URN + CatalogSignature.CATALOG_ID)
            .issuedBy(oldRoot, oldKey.private).build()
        val jws = TestPki.detached(ChainSignature.Purpose.CATALOG.typ, listOf(leaf), leafKey.private, catalogBytes)
        at(lastGood) { assertTrue(CatalogSignature.verify(catalogBytes, jws, DroidtopSigningRoot.anchors()) is CatalogSignature.Verdict.Verified) }
        at(tooLate) { assertTrue(CatalogSignature.verify(catalogBytes, jws, DroidtopSigningRoot.anchors()) is CatalogSignature.Verdict.Refused) }

        val newLeaf = TestPki.cert("catalog", leafKey.public).eku(TestPki.CATALOG).uri(ChainSignature.CATALOG_URN + CatalogSignature.CATALOG_ID)
            .issuedBy(newRoot, newKey.private).build()
        val newJws = TestPki.detached(ChainSignature.Purpose.CATALOG.typ, listOf(newLeaf), leafKey.private, catalogBytes)
        at(tooLate) { assertTrue(CatalogSignature.verify(catalogBytes, newJws, DroidtopSigningRoot.anchors()) is CatalogSignature.Verdict.Verified) }
    }

    @Test
    fun `a revocation list under the new root revokes an old-root leaf while both are in force`() {
        val (jws, leaf) = bundle(oldRoot, oldKey)
        val pluginsRoot = tmp.newFolder("plugins")
        at(duringWindow) {
            assertTrue(verify(jws) is BundleVerdict.Verified)
            val list = revocationList(newRoot, newKey, 1, listOf(Sha256.hex(leaf.publicKey.encoded)))
            assertTrue(PluginRevocations.accept(pluginsRoot, list))
            assertTrue(verify(jws, PluginRevocations.load(pluginsRoot)) is BundleVerdict.Refused)
        }
    }

    @Test
    fun `a revocation list under the old root counts in the window and not after it`() {
        val pluginsRoot = tmp.newFolder("plugins-old")
        val oldList = revocationList(oldRoot, oldKey, 1, listOf("a".repeat(64)))
        at(tooLate) { assertFalse(PluginRevocations.accept(pluginsRoot, oldList)) }
        at(lastGood) {
            assertTrue(PluginRevocations.accept(pluginsRoot, oldList))
            assertTrue(PluginRevocations.load(pluginsRoot).revokesKey("a".repeat(64)))
        }
        at(tooLate) { assertEquals(0L, PluginRevocations.load(pluginsRoot).sequence) }
    }

    @Test
    fun `approval carries over from the old root to the new while the old is in force, and the old fingerprint verifies nothing after`() {
        val oldSha = Sha256.hex(oldRoot.publicKey.encoded)
        val newSha = Sha256.hex(newRoot.publicKey.encoded)
        at(lastGood) {
            assertTrue(PluginOriginKeys.officialAnchorFingerprints().containsAll(listOf(oldSha, newSha)))
            assertTrue(PluginOriginKeys.sameTrustAnchor(oldSha, newSha))
            assertTrue(PluginOriginKeys.sameTrustAnchor(newSha, oldSha))
            assertFalse(PluginOriginKeys.sameTrustAnchor("b".repeat(64), newSha))
        }
        at(tooLate) {
            assertFalse(oldSha in PluginOriginKeys.officialAnchorFingerprints())
            assertTrue(newSha in PluginOriginKeys.officialAnchorFingerprints())
            assertFalse(PluginOriginKeys.sameTrustAnchor(oldSha, newSha))
        }
    }

    @Test
    fun `the expired root cannot be revived by trusting its key as a user key`() {
        val spki = Base64.getEncoder().encodeToString(oldRoot.publicKey.encoded)
        val (jws, _) = bundle(oldRoot, oldKey, origin = "acme")
        at(lastGood) {
            assertTrue(PluginOriginKeys.anchorForKey(spki)!!.pinned)
        }
        at(tooLate) {
            assertNull(PluginOriginKeys.anchorForKey(spki))
            assertTrue(BundleSignature.verifyBundle(manifest, jws, "acme", "acme.sample", mapOf("acme" to spki)) is BundleVerdict.Refused)
            assertNotNull(PluginOriginKeys.anchorForKey(Base64.getEncoder().encodeToString(newRoot.publicKey.encoded)))
        }
    }
}
