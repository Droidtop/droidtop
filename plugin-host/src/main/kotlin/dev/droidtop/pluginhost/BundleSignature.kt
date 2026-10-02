package dev.droidtop.pluginhost

import java.security.KeyFactory
import dev.droidtop.runtime.util.Sha256
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Per-origin P-256 keys, certified under one root -- the same
 * strength enginehost's engine bundles use (docs/SPEC.md 7d, and 12a
 * point 4, which requires plugins be "at least as strict"). Keys are
 * public material (verification only lives on-device); the private
 * signing keys never appear in this repo.
 *
 * "droidtop" is the one shipped origin ([OFFICIAL_ORIGIN]), matching
 * the sample plugin (samples/plugin-sample-statustile). Any other
 * origin's key reaches a device through "Keys you trust" (docs/SPEC.md
 * 12a, [UserOriginKeys]): the user adds the source, droidtop fetches
 * its published key, and the user confirms it once -- never through
 * this object's pinned set, which stays what droidtop itself shipped.
 */
object PluginOriginKeys {
    /**
     * The one origin droidtop itself certifies and ships pinned in the
     * binary. Shown as "Official" on the Plugins screen, and the one id
     * a user-trusted origin may never claim (docs/SPEC.md 12a "Keys you
     * trust"): [UserOriginKeys.add] refuses it, and [resolve] checks
     * [PINNED] before user keys so even a hand-edited store entry can
     * never shadow it.
     */
    const val OFFICIAL_ORIGIN = "droidtop"

    /**
     * origin id -> SubjectPublicKeyInfo (X.509), base64, P-256. This is a
     * REAL generated key pair (`openssl ecparam -name prime256v1
     * -genkey`), not a placeholder string: the private half is on the
     * coordination machine (`/root/coordination/keys/droidtop-plugins/`,
     * never in this repo) and signs the sample plugin bundle
     * (samples/plugin-sample-statustile). It is a first working origin
     * key, not a certified production root -- §8's root-key derivation
     * covers enginehost's engine bundles; a droidtop plugin catalog root
     * is real follow-up work once more than one origin needs pinning.
     */
    private val PINNED: Map<String, String> = mapOf(
        OFFICIAL_ORIGIN to "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEYs1dY+vQaHC/tj9XEfv2KxHfCzLtsgyIi/AN" +
            "wdSuKyoGcp7kCNXoy0sYhOaOdFUTpV4tnkKsICv/EY8q+zM5Dw==",
    )

    /** The official origin's SPKI, base64 -- the shape [UserOriginKeys] stores and the "Keys you trust" screen shows a fingerprint of. */
    fun officialKeyBase64(): String = PINNED.getValue(OFFICIAL_ORIGIN)

    /** True when [origin] is the official one certified inside droidtop's own binary -- the row the "Keys you trust" screen labels "Official" and never offers to remove. */
    fun isOfficial(origin: String): Boolean = origin == OFFICIAL_ORIGIN

    /**
     * The fingerprint [PluginRecord.approvedKeySha256] stores -- the hex
     * SHA-256 of the pinned public key's own DER (SubjectPublicKeyInfo,
     * the same bytes [PINNED] base64-encodes) -- or null when no key is
     * pinned for [origin]. This is what makes "signed by the same key"
     * decidable for the carry-over rule (docs/SPEC.md 12a, "Trust over
     * updates"): the install path verifies the signature against this
     * exact pinned key, so two bundles that both pass it are, by
     * construction, signed by the same key.
     */
    fun keyFingerprintFor(origin: String, userKeys: Map<String, String> = emptyMap()): String? {
        val key = resolve(origin, userKeys) ?: return null
        return Sha256.hex(key.encoded)
    }

    /** Test/tooling hook -- lets unit tests and the sample-bundle packager pin a throwaway key without touching [PINNED]. */
    fun withOrigin(origin: String, publicKeyBase64: String, block: () -> Unit) {
        val previous = overrides[origin]
        overrides[origin] = publicKeyBase64
        try {
            block()
        } finally {
            if (previous == null) overrides.remove(origin) else overrides[origin] = previous
        }
    }

    private val overrides = mutableMapOf<String, String>()

    /**
     * The ONE resolution path for an origin's verification key
     * (docs/SPEC.md 12a "Keys you trust"): the official pinned key
     * FIRST, then the user-trusted keys `userKeys` carries (origin ->
     * SPKI base64, from [UserOriginKeys.loadBase64] -- nothing is
     * trusted from anywhere else). Test [overrides] sit above both.
     * Official-first is the whole shadowing defence: an origin may not
     * claim the official id, and even if a user key for "droidtop" were
     * somehow in the map, the pinned key wins and the impostor's
     * signatures never verify.
     */
    fun resolve(origin: String, userKeys: Map<String, String> = emptyMap()): PublicKey? {
        val encoded = overrides[origin] ?: PINNED[origin] ?: userKeys[origin] ?: return null
        return runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))
        }.getOrNull()
    }

    /**
     * Parses an SPKI base64 blob into a key, refusing anything that is
     * not an elliptic-curve P-256 public key -- the exact shape
     * [PINNED] and `droidtop-plugin-key.json` both use. Shared by
     * [UserOriginKeys] (which stores and fingerprints keys) and
     * [PluginSourceKeys] (which validates a fetched key before ever
     * showing it), so "a valid plugin origin key" has one definition.
     */
    fun parseSpki(keyBase64: String): PublicKey? {
        val bytes = runCatching { Base64.getMimeDecoder().decode(keyBase64) }.getOrNull() ?: return null
        val key = runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        }.getOrNull() as? ECPublicKey ?: return null
        // P-256 exactly: a P-384/P-521 SPKI parses fine as "EC", so the
        // curve's own field size is the check that pins the algorithm.
        if (key.params?.curve?.field?.fieldSize != 256) return null
        return key
    }
}

/**
 * Verifies a bundle's manifest against its origin's key -- the official
 * pinned one first, then any key the user trusted (docs/SPEC.md 12a
 * "Keys you trust") -- via ECDSA/SHA-256 over the exact manifest bytes,
 * and every payload file's declared SHA-256 against the real bytes on
 * disk. Both checks are mandatory (checklist points 1 and 4): a bundle
 * with no signature block, an origin no key resolves for, or a signature
 * that doesn't verify is refused, not downgraded to "unverified but
 * installable".
 */
object BundleSignature {
    fun verifyManifest(manifestBytes: ByteArray, signatureBase64: String, origin: String, userKeys: Map<String, String> = emptyMap()): Boolean {
        val key = PluginOriginKeys.resolve(origin, userKeys) ?: return false
        val signatureBytes = runCatching { Base64.getDecoder().decode(signatureBase64) }.getOrNull() ?: return false
        return runCatching {
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(key)
                update(manifestBytes)
            }.verify(signatureBytes)
        }.getOrDefault(false)
    }

}
