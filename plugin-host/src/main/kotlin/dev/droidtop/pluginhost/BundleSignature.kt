package dev.droidtop.pluginhost

import dev.droidtop.runtime.util.EcP256
import dev.droidtop.runtime.util.MasterKey
import dev.droidtop.runtime.util.Sha256
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The keys droidtop verifies plugin bundles with (docs/SPEC.md 12a "Per-plugin
 * keys and the plugin master"), all P-256 -- the same strength enginehost's
 * engine bundles use (docs/SPEC.md 7d, and 12a point 4, which requires
 * plugins be "at least as strict"). Keys are public material; the private
 * signing keys never appear in this repo.
 *
 * - The plugin MASTER ([master]) is pinned in the binary. It signs nothing
 *   but certificates ([PluginCertificates]) and revocation lists
 *   ([PluginRevocations]). Every official plugin repository has its own key,
 *   derived from the master seed, and a master-signed certificate that binds
 *   that key to the repository's plugin ids; the bundle carries it as
 *   `origin.cert`.
 * - The LEGACY official origin key ([PINNED], origin "droidtop") signed every
 *   official bundle before per-plugin keys. It is still accepted for a bundle
 *   of origin "droidtop" that carries no certificate, so plugins installed
 *   from it keep running and updating until their repositories ship
 *   certified releases; the transition ends as SPEC 12a describes.
 * - Any other origin's key reaches a device through "Keys you trust"
 *   ([UserOriginKeys]): the user adds the source (or accepts a catalog),
 *   droidtop fetches its published key, and the user confirms it once --
 *   never through this object's pinned set, which stays what droidtop
 *   itself shipped and the only thing that yields "Official". Such a key
 *   is either the origin's own signing key or, for an organisation's
 *   catalog, that organisation's plugin master, which certifies each of its
 *   repositories' keys the way droidtop's master certifies official ones.
 */
object PluginOriginKeys {
    /**
     * The one origin droidtop itself certifies. Shown as "Official" on the
     * Plugins screen, and the one id a user-trusted origin may never claim
     * (docs/SPEC.md 12a "Keys you trust"): [UserOriginKeys.add] refuses it,
     * and [resolve] checks [PINNED] before user keys so even a hand-edited
     * store entry can never shadow it.
     */
    const val OFFICIAL_ORIGIN = "droidtop"

    /**
     * origin id -> SubjectPublicKeyInfo (X.509), base64, P-256: the legacy
     * official key, a real generated key pair whose private half is on the
     * coordination machine, never in this repo. Accepted only for official
     * bundles without a certificate (see the object's comment).
     */
    private val PINNED: Map<String, String> = mapOf(
        OFFICIAL_ORIGIN to "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEYs1dY+vQaHC/tj9XEfv2KxHfCzLtsgyIi/AN" +
            "wdSuKyoGcp7kCNXoy0sYhOaOdFUTpV4tnkKsICv/EY8q+zM5Dw==",
    )

    /** The legacy official origin key's SPKI, base64 -- the shape [UserOriginKeys] stores and the "Keys you trust" screen shows a fingerprint of. */
    fun officialKeyBase64(): String = overrides[OFFICIAL_ORIGIN] ?: PINNED.getValue(OFFICIAL_ORIGIN)

    /**
     * The plugin master's SPKI, base64, or null while none is pinned. It is
     * droidtop's one master ([MasterKey], pinned in `:runtime-common`, which
     * also certifies the component catalog's key); until it is pinned every
     * certified bundle is refused with a reason that says so, and official
     * bundles verify through the legacy key alone.
     */
    fun masterKeyBase64(): String? = MasterKey.base64()

    /** The plugin master key, or null while none is pinned. */
    fun master(): PublicKey? = MasterKey.key()

    /** True when [origin] is the official one certified inside droidtop's own binary -- the row the "Keys you trust" screen labels "Official" and never offers to remove. */
    fun isOfficial(origin: String): Boolean = origin == OFFICIAL_ORIGIN

    /**
     * The fingerprints official approval is bound to: the legacy origin key's
     * and, once pinned, the master's. An official plugin approved under either
     * keeps its approval across an update verified under the other, so moving
     * a plugin from the legacy key to its own certified key is not a new trust
     * decision for the person (docs/SPEC.md 12a "Trust over updates").
     */
    fun officialAnchorFingerprints(): Set<String> = listOfNotNull(
        parseSpki(officialKeyBase64())?.let { Sha256.hex(it.encoded) },
        master()?.let { Sha256.hex(it.encoded) },
    ).toSet()

    /** Whether approval under [approved] carries over to a bundle verified under [verified]: the same key, or both official anchors. */
    fun sameTrustAnchor(approved: String, verified: String): Boolean {
        if (approved.isEmpty() || verified.isEmpty()) return false
        if (approved.equals(verified, ignoreCase = true)) return true
        val anchors = officialAnchorFingerprints()
        return approved.lowercase() in anchors && verified.lowercase() in anchors
    }

    /** Test/tooling hook -- lets unit tests pin a throwaway origin key without touching [PINNED]. */
    fun withOrigin(origin: String, publicKeyBase64: String, block: () -> Unit) {
        val previous = overrides[origin]
        overrides[origin] = publicKeyBase64
        try {
            block()
        } finally {
            if (previous == null) overrides.remove(origin) else overrides[origin] = previous
        }
    }

    /** Test hook -- pins a throwaway master key for the duration of [block]. */
    fun withMaster(publicKeyBase64: String, block: () -> Unit) = MasterKey.withPinned(publicKeyBase64, block)

    private val overrides = mutableMapOf<String, String>()

    /**
     * The ONE resolution path for an origin's own verification key
     * (docs/SPEC.md 12a "Keys you trust"): the official pinned key FIRST,
     * then the user-trusted keys `userKeys` carries (origin -> SPKI base64,
     * from [UserOriginKeys.loadBase64] -- nothing is trusted from anywhere
     * else). Test [overrides] sit above both. Official-first is the whole
     * shadowing defence: an origin may not claim the official id, and even if
     * a user key for "droidtop" were somehow in the map, the pinned key wins
     * and the impostor's signatures never verify. Certified keys do not
     * resolve here: they are reached only through a bundle's certificate
     * ([BundleSignature.verifyBundle]).
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
     * [PINNED], certificates and `droidtop-plugin-key.json` all use. Shared
     * by [UserOriginKeys] (which stores and fingerprints keys),
     * [PluginSourceKeys] (which validates a fetched key before ever showing
     * it) and [PluginCertificates], so "a valid plugin key" has one
     * definition.
     */
    fun parseSpki(keyBase64: String): PublicKey? = EcP256.parseSpki(keyBase64)
}

/** What [BundleSignature.verifyBundle] decided. */
sealed interface BundleVerdict {
    /**
     * [anchorSha256] is the fingerprint approval is bound to
     * ([PluginRecord.approvedKeySha256]): the master's for a certified
     * bundle, otherwise the key the signature verified against.
     * [certificate] is set for a certified bundle.
     */
    data class Verified(val anchorSha256: String, val certificate: PluginCertificate?) : BundleVerdict

    data class Refused(val reason: String) : BundleVerdict
}

/**
 * Verifies a bundle's manifest signature (ECDSA/SHA-256 over the exact
 * manifest bytes). Mandatory, never downgraded (checklist points 1 and 4): a
 * bundle with no signature, an origin no key resolves for, or a signature
 * that does not verify is refused, not "unverified but installable".
 */
object BundleSignature {
    /**
     * The one bundle-trust decision (docs/SPEC.md 12a "Per-plugin keys and
     * the plugin master"), used by install, by the re-verification before
     * every activation, and by the repository update pass.
     *
     * An official bundle WITH a certificate: the certificate must be signed
     * by the pinned master, cover [pluginId], not be revoked (by id or key),
     * be within its validity when [nowEpochSeconds] is given (install time;
     * an installed plugin is not stopped by its certificate expiring, only
     * by revocation), and the manifest signature must verify against the
     * certified key. An official bundle WITHOUT one: the legacy origin key,
     * unless the revocation list withdrew it. Any other origin: its
     * user-trusted key, which the person's own trust decision vouches for.
     * When such a bundle carries a certificate, the user-trusted key is that
     * origin's master (an added catalog's organisation master, SPEC 12a
     * "Added catalogs") and the same chain applies against it: the
     * certificate must be signed by it, cover [pluginId], not be revoked by
     * that origin's own revocation list ([PluginRevocationList.forOrigin]),
     * be valid at install, and certify the key the manifest is signed with.
     * Approval binds to the master's fingerprint, so a repository's key
     * rotation under the same master keeps it. A certificate never makes a
     * bundle official.
     */
    fun verifyBundle(
        manifestBytes: ByteArray,
        signatureBase64: String,
        origin: String,
        pluginId: String,
        certificateText: String?,
        userKeys: Map<String, String> = emptyMap(),
        revocations: PluginRevocationList = PluginRevocationList.NONE,
        nowEpochSeconds: Long? = null,
    ): BundleVerdict {
        if (PluginOriginKeys.isOfficial(origin) && certificateText != null) {
            val master = PluginOriginKeys.master()
                ?: return BundleVerdict.Refused("the bundle carries a plugin certificate, but this build of droidtop pins no plugin master key to check it with")
            val certificate = PluginCertificates.parse(certificateText, master)
                ?: return BundleVerdict.Refused("${PluginCertificates.FILE_NAME} is not a certificate signed by droidtop's plugin master key")
            if (!certificate.covers(pluginId)) {
                return BundleVerdict.Refused("the certificate is for ${certificate.pluginIds.joinToString()}, not \"$pluginId\"")
            }
            if (revocations.revokesCert(certificate.certId) || revocations.revokesKey(certificate.keySha256)) {
                return BundleVerdict.Refused("the certificate \"${certificate.certId}\" has been revoked")
            }
            if (nowEpochSeconds != null && !certificate.validAt(nowEpochSeconds)) {
                return BundleVerdict.Refused("the certificate \"${certificate.certId}\" is not valid now (check the device's date)")
            }
            if (!verifyWith(certificate.publicKey, manifestBytes, signatureBase64)) {
                return BundleVerdict.Refused("signature doesn't verify against the key certified for \"$pluginId\"")
            }
            return BundleVerdict.Verified(Sha256.hex(master.encoded), certificate)
        }
        val key = PluginOriginKeys.resolve(origin, userKeys)
            ?: return BundleVerdict.Refused("signature doesn't verify against a trusted key for origin \"$origin\"")
        val fingerprint = Sha256.hex(key.encoded)
        if (PluginOriginKeys.isOfficial(origin) && revocations.revokesKey(fingerprint)) {
            return BundleVerdict.Refused("the official key this bundle is signed with has been withdrawn; it needs a certified release")
        }
        if (!PluginOriginKeys.isOfficial(origin)) {
            val own = revocations.forOrigin(origin)
            if (own.revokesKey(fingerprint)) {
                return BundleVerdict.Refused("the key you trust for origin \"$origin\" has been revoked by its catalog")
            }
            if (certificateText != null) {
                val certificate = PluginCertificates.parse(certificateText, key)
                    ?: return BundleVerdict.Refused("${PluginCertificates.FILE_NAME} is not a certificate signed by the key you trust for origin \"$origin\"")
                if (!certificate.covers(pluginId)) {
                    return BundleVerdict.Refused("the certificate is for ${certificate.pluginIds.joinToString()}, not \"$pluginId\"")
                }
                if (own.revokesCert(certificate.certId) || own.revokesKey(certificate.keySha256)) {
                    return BundleVerdict.Refused("the certificate \"${certificate.certId}\" has been revoked by its catalog")
                }
                if (nowEpochSeconds != null && !certificate.validAt(nowEpochSeconds)) {
                    return BundleVerdict.Refused("the certificate \"${certificate.certId}\" is not valid now (check the device's date)")
                }
                if (!verifyWith(certificate.publicKey, manifestBytes, signatureBase64)) {
                    return BundleVerdict.Refused("signature doesn't verify against the key certified for \"$pluginId\"")
                }
                return BundleVerdict.Verified(fingerprint, certificate)
            }
        }
        if (!verifyWith(key, manifestBytes, signatureBase64)) {
            return BundleVerdict.Refused("signature doesn't verify against a trusted key for origin \"$origin\"")
        }
        return BundleVerdict.Verified(fingerprint, null)
    }

    /** ECDSA P-256/SHA-256 of [data] by [key]; [signatureBase64] is the DER signature, base64. */
    fun verifyWith(key: PublicKey, data: ByteArray, signatureBase64: String): Boolean =
        EcP256.verify(key, data, signatureBase64)
}
