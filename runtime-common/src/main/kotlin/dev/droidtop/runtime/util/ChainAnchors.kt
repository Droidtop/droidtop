package dev.droidtop.runtime.util

import dev.droidtop.shared.trust.ChainSignature
import java.security.cert.X509Certificate

/**
 * The one way droidtop checks a signature against several anchors (the roots in force, [DroidtopSigningRoot.anchors]):
 * [ChainSignature] itself, shared with other projects, takes one anchor, so the list lives here and not in it.
 */
object ChainAnchors {
    /**
     * [ChainSignature.verify] against each of [anchors] in turn: the first that verifies wins, and its anchor is in the
     * result. When none does, the refusal of the first anchor (the current root) is returned; with no anchors, a refusal
     * that says so.
     */
    fun verify(
        jws: String,
        purpose: ChainSignature.Purpose,
        anchors: List<ChainSignature.Anchor>,
        detached: ByteArray?,
        revoked: (X509Certificate) -> Boolean = { false },
    ): ChainSignature.Result {
        var first: ChainSignature.Result.Refused? = null
        for (anchor in anchors) {
            when (val result = ChainSignature.verify(jws, purpose, anchor, detached, revoked)) {
                is ChainSignature.Result.Verified -> return result
                is ChainSignature.Result.Refused -> if (first == null) first = result
            }
        }
        return first ?: ChainSignature.Result.Refused("no anchor is pinned to check the signature against")
    }
}
