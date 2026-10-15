package dev.droidtop.pluginhost

import dev.droidtop.runtime.util.ChainSignature
import dev.droidtop.runtime.util.DroidtopCore
import java.security.PublicKey
import java.security.cert.X509Certificate

/**
 * Where plugin trust is anchored (docs/SPEC.md 12a "Signatures: certificate
 * chains to Droidtop Core"). Keys are public material; no private key ever
 * appears in this repo.
 *
 * - The official origin's anchor is the pinned Droidtop Core certificate
 *   ([DroidtopCore]). Every official plugin repository signs with its own key,
 *   derived from a master seed, whose certificate Core issued naming the
 *   origin; the bundle carries that chain in `manifest.jws`.
 * - Any other origin's anchor is the key the person confirmed for it in "Keys
 *   you trust" ([UserOriginKeys]), never this object's own: the person adds the
 *   source (or accepts a catalog) and droidtop reads the certificate it
 *   publishes. When that certificate was issued by Droidtop Core the stored
 *   anchor is Core's key, so an origin the owner certifies (gamegrab, romgi)
 *   verifies to Core while still being one the person chose to trust. Only the
 *   official origin ever yields "Official".
 */
object PluginOriginKeys {
    /**
     * The one origin droidtop itself certifies. Shown as "Official" on the
     * Plugins screen, and the one id a user-trusted origin may never claim
     * (docs/SPEC.md 12a "Keys you trust"): [UserOriginKeys.add] refuses it,
     * and [anchorFor] answers it from Droidtop Core before user keys are
     * looked at, so even a hand-edited store entry can never shadow it.
     */
    const val OFFICIAL_ORIGIN = "droidtop"

    /** True when [origin] is the official one -- the row the "Keys you trust" screen labels "Official" and never offers to remove. */
    fun isOfficial(origin: String): Boolean = origin == OFFICIAL_ORIGIN

    /**
     * The fingerprints official approval is bound to: Droidtop Core's and the
     * legacy anchors' ([LegacyBundleSignature.officialFingerprints]). An
     * official plugin approved under any of them keeps its approval across an
     * update verified under another, so moving it to the certificate format is
     * not a new trust decision for the person (docs/SPEC.md 12a "Trust over
     * updates").
     */
    fun officialAnchorFingerprints(): Set<String> =
        (listOfNotNull(DroidtopCore.keySha256()) + LegacyBundleSignature.officialFingerprints()).toSet()

    /** Whether approval under [approved] carries over to a bundle verified under [verified]: the same anchor, or both official anchors. */
    fun sameTrustAnchor(approved: String, verified: String): Boolean {
        if (approved.isEmpty() || verified.isEmpty()) return false
        if (approved.equals(verified, ignoreCase = true)) return true
        val anchors = officialAnchorFingerprints()
        return approved.lowercase() in anchors && verified.lowercase() in anchors
    }

    /**
     * The ONE resolution path for the anchor an origin's chains must end at:
     * Droidtop Core for the official origin, checked FIRST (null while this
     * build pins none), otherwise the user-trusted key `userKeys` carries
     * (origin -> SPKI base64, from [UserOriginKeys.loadBase64] -- nothing is
     * trusted from anywhere else) through [anchorForKey].
     */
    fun anchorFor(origin: String, userKeys: Map<String, String> = emptyMap()): ChainSignature.Anchor? {
        val core = DroidtopCore.anchor()
        if (isOfficial(origin)) return core
        return userKeys[origin]?.let(::anchorForKey)
    }

    /**
     * The anchor for a trusted key (an origin's, or an added catalog's master): Droidtop Core's pinned anchor when
     * it is Core's own key, so the chain must stop below Core; otherwise the key itself. Null when it is not a key
     * droidtop can use.
     */
    fun anchorForKey(keyBase64: String): ChainSignature.Anchor? {
        val key = parseSpki(keyBase64) ?: return null
        val core = DroidtopCore.anchor()
        return if (core != null && key.encoded.contentEquals(core.key.encoded)) core else ChainSignature.Anchor(key)
    }

    /**
     * Parses an SPKI base64 blob into a key, refusing anything that is not an
     * EC P-256 or RSA (2048 bits or more) public key. Shared by
     * [UserOriginKeys] (which stores and fingerprints anchors) and
     * [PluginSourceKeys], so "a usable anchor key" has one definition.
     */
    fun parseSpki(keyBase64: String): PublicKey? = ChainSignature.parseSpki(keyBase64)
}

/** What [BundleSignature.verifyBundle] decided. */
sealed interface BundleVerdict {
    /**
     * [anchorSha256] is the fingerprint approval is bound to
     * ([PluginRecord.approvedKeySha256]): the anchor the chain ended at.
     * [signer] is the signing certificate; null only for an installed bundle
     * verified on the legacy path.
     */
    data class Verified(val anchorSha256: String, val signer: X509Certificate?) : BundleVerdict

    data class Refused(val reason: String) : BundleVerdict
}

/**
 * Verifies a bundle's `manifest.jws`: a detached [ChainSignature] over the
 * exact manifest bytes. Mandatory, never downgraded (checklist points 1 and
 * 4): a bundle with no signature, an origin no anchor resolves for, or a
 * signature that does not verify is refused, not "unverified but installable".
 */
object BundleSignature {
    /** The bundle entry the signature travels in, beside manifest.json. */
    const val FILE_NAME = "manifest.jws"

    /**
     * The one bundle-trust decision (docs/SPEC.md 12a), used by install, by
     * the re-verification before every activation, and by the repository
     * update pass: the chain must end at [origin]'s anchor
     * ([PluginOriginKeys.anchorFor]), its leaf must be a plugin-signing
     * certificate naming [origin] (`urn:droidtop:origin:<origin>`) and, when it
     * names plugins, [pluginId]; no certificate in it may be revoked; and the
     * manifest signature must verify against the leaf. Validity is checked when
     * [nowEpochSeconds] is given (install and update); an installed plugin is
     * not stopped by its certificate expiring, only by revocation.
     *
     * Revocation: droidtop's own list ([PluginRevocationList] built from
     * Core-signed `revocations.jws`) applies to every chain that ends at
     * Droidtop Core; an origin's own catalog list
     * ([PluginRevocationList.forOrigin]) applies to that origin alone.
     */
    fun verifyBundle(
        manifestBytes: ByteArray,
        jws: String,
        origin: String,
        pluginId: String,
        userKeys: Map<String, String> = emptyMap(),
        revocations: PluginRevocationList = PluginRevocationList.NONE,
        nowEpochSeconds: Long? = null,
    ): BundleVerdict {
        val anchor = PluginOriginKeys.anchorFor(origin, userKeys) ?: return BundleVerdict.Refused(
            if (PluginOriginKeys.isOfficial(origin)) {
                "this build of droidtop pins no Droidtop Core certificate to check official plugins with"
            } else {
                "no key you trust for origin \"$origin\""
            },
        )
        val core = anchor.certificate != null
        val own = revocations.forOrigin(origin)
        val revoked = { cert: X509Certificate -> (core && revocations.revokes(cert)) || own.revokes(cert) }
        val result = ChainSignature.verify(jws, ChainSignature.Purpose.PLUGIN, anchor, manifestBytes, nowEpochSeconds, revoked)
        val verified = when (result) {
            is ChainSignature.Result.Refused -> return BundleVerdict.Refused("${result.reason} (origin \"$origin\")")
            is ChainSignature.Result.Verified -> result
        }
        if (!ChainSignature.names(verified.leaf, ChainSignature.ORIGIN_URN, origin)) {
            return BundleVerdict.Refused("the signing certificate is not for origin \"$origin\"")
        }
        if (!ChainSignature.coversPlugin(verified.leaf, pluginId)) {
            return BundleVerdict.Refused("the signing certificate is not for \"$pluginId\"")
        }
        return BundleVerdict.Verified(anchor.sha256, verified.leaf)
    }
}
