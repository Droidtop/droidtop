package dev.droidtop.runtime.util

import java.security.PublicKey

/**
 * droidtop's signing root (docs/SPEC.md 12a "Signatures: certificate chains to
 * droidtop's signing root", Droidtop/tracker#550): droidtop's one pinned trust
 * anchor, the public key of droidtop's own root. The owner's key hierarchy:
 * the owner's CA issues Droidtop Core, the master everything is DERIVED from
 * and never used directly; droidtop's signing root, Enginehost's and gamegrab's
 * are each derived from it separately; droidtop's official plugin repositories,
 * catalogs and revocation signer get leaf keys derived from droidtop's root,
 * whose certificates it issues (owner, 2026-10-15: "droidtop gets its key
 * derived from the master, then offical plugins are derived from that").
 *
 * Only this public key is pinned: neither Core nor anything above this root is
 * trusted, needed or read, and a chain is anchored by checking that its last
 * certificate verifies under this key. Verification stops there, so no chain
 * may carry a certificate holding this key.
 *
 * It ships as the resource [RESOURCE]: a PEM `PUBLIC KEY` block (or bare
 * base64 SubjectPublicKeyInfo), public material, which the coordinator
 * commits. While the file is a placeholder [key] is null: official bundles in
 * the certificate format are refused with a reason that says so, and the
 * component catalog is accepted unsigned ([CatalogSignature.Verdict.Unpinned]).
 */
object DroidtopSigningRoot {
    const val RESOURCE = "dev/droidtop/runtime/util/droidtop-signing-root.pub"

    private val pinned: PublicKey? by lazy { load() }

    @Volatile private var overridden = false
    @Volatile private var override: PublicKey? = null

    /** The pinned signing root's public key, or null while this build pins none. */
    fun key(): PublicKey? = if (overridden) override else pinned

    /** The anchor droidtop's own signatures must chain to, or null while none is pinned. */
    fun anchor(): ChainSignature.Anchor? = key()?.let { ChainSignature.Anchor(it, pinned = true) }

    /** Lowercase hex SHA-256 of the signing root's SubjectPublicKeyInfo, or null while none is pinned. */
    fun keySha256(): String? = key()?.let { Sha256.hex(it.encoded) }

    /** The key in [text]: a PEM `PUBLIC KEY` block or bare base64 SPKI; null for the placeholder (comment lines only). */
    internal fun parse(text: String): PublicKey? {
        val body = if (text.contains("-----BEGIN PUBLIC KEY-----")) {
            text.substringAfter("-----BEGIN PUBLIC KEY-----").substringBefore("-----END PUBLIC KEY-----")
        } else {
            text.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("")
        }
        if (body.isBlank()) return null
        return ChainSignature.parseSpki(body)
    }

    private fun load(): PublicKey? {
        val text = runCatching {
            DroidtopSigningRoot::class.java.classLoader?.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.US_ASCII) }
        }.getOrNull() ?: return null
        return parse(text)
    }

    /** Test hook: pins [key] (or, with null, none at all) for the duration of [block]. */
    fun <T> withPinned(key: PublicKey?, block: () -> T): T {
        val previousOverridden = overridden
        val previous = override
        override = key
        overridden = true
        try {
            return block()
        } finally {
            override = previous
            overridden = previousOverridden
        }
    }
}
