package dev.droidtop.runtime.util

import dev.droidtop.shared.trust.ChainSignature

/**
 * A catalog index's signature (docs/SPEC.md 9 "What droidtop-components may
 * re-host, and the catalog's signature", and 12a "Added catalogs"): a detached
 * [ChainSignature] over the exact index bytes, published beside it
 * (`catalog.json.jws` for the component catalog, `index.json.jws` for an added
 * plugin catalog), whose catalog-signing leaf names the catalog
 * (`urn:droidtop:catalog:<id>`). The component catalog's chain ends at
 * droidtop's signing root ([DroidtopSigningRoot]); an added catalog's at the key the person
 * accepted for it. One format and one decision for every signed catalog.
 *
 * While this build pins no signing root the component catalog is accepted
 * unsigned ([Verdict.Unpinned]); once one is pinned a catalog without a valid
 * signature is refused, whatever the catalog says about itself, so removing
 * the file cannot downgrade a device to unsigned.
 */
object CatalogSignature {
    const val SIGNATURE_FILE = "catalog.json.jws"

    /** The component catalog's id, the one its signing certificate must name by default: the repository that publishes it. */
    const val CATALOG_ID = "Droidtop/droidtop-components"

    sealed interface Verdict {
        /** No anchor is pinned: the catalog is taken as fetched. */
        data object Unpinned : Verdict

        data class Verified(val anchorSha256: String) : Verdict

        data class Refused(val reason: String) : Verdict
    }

    /**
     * The one decision on a fetched catalog: [catalogBytes] exactly as downloaded, [jws] as fetched beside it (null
     * when missing), [anchor] the chain's end or null when none is pinned, [catalogId] the catalog the signing
     * certificate must name.
     */
    fun verify(
        catalogBytes: ByteArray,
        jws: String?,
        anchor: ChainSignature.Anchor?,
        catalogId: String = CATALOG_ID,
    ): Verdict {
        if (anchor == null) return Verdict.Unpinned
        if (jws.isNullOrBlank()) return Verdict.Refused("the catalog has no signature beside it, and one is required")
        return when (val result = ChainSignature.verify(jws, ChainSignature.Purpose.CATALOG, anchor, catalogBytes)) {
            is ChainSignature.Result.Refused -> Verdict.Refused(result.reason)
            is ChainSignature.Result.Verified ->
                if (ChainSignature.names(result.leaf, ChainSignature.CATALOG_URN, catalogId)) {
                    Verdict.Verified(anchor.sha256)
                } else {
                    Verdict.Refused("the catalog's signing certificate is not for $catalogId")
                }
        }
    }
}
