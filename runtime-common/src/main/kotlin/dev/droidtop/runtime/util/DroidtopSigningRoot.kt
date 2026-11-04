package dev.droidtop.runtime.util

import dev.droidtop.shared.trust.ChainSignature
import dev.droidtop.shared.trust.Sha256

import java.security.PublicKey
import java.time.Instant

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
 *
 * Rotation (2026-11-04, Droidtop/tracker#876 and #550; owner: "Pin the old key
 * for 7 days, then expire it. That should give time for users to organically
 * move off the old cores"): the root above is the new one, trusted with no end.
 * The one it replaced ships as [PREVIOUS_RESOURCE] and is trusted only until
 * [OLD_ROOT_EXPIRES_AT]; after that instant a chain that ends at it is refused
 * everywhere a chain to droidtop's root is accepted, because every caller takes
 * its anchors from [anchors], which asks the clock. No other place names a
 * droidtop root.
 */
object DroidtopSigningRoot {
    const val RESOURCE = "dev/droidtop/runtime/util/droidtop-signing-root.pub"

    /** The root the rotation replaced (SPKI sha256 45734d88...), kept only until [OLD_ROOT_EXPIRES_AT]. */
    const val PREVIOUS_RESOURCE = "dev/droidtop/runtime/util/droidtop-signing-root-previous.pub"

    /** The last instant a chain to the previous root is accepted: 7 days after the rotation. The one constant. */
    val OLD_ROOT_EXPIRES_AT: Instant = Instant.parse("2026-11-11T23:59:59Z")

    private val pinned: PublicKey? by lazy { load(RESOURCE) }
    private val pinnedPrevious: PublicKey? by lazy { load(PREVIOUS_RESOURCE) }

    @Volatile private var overridden = false
    @Volatile private var override: PublicKey? = null
    @Volatile private var overridePrevious: PublicKey? = null
    @Volatile private var overrideNow: Instant? = null

    private fun now(): Instant = overrideNow ?: Instant.now()

    /** The pinned signing root's public key (the new one, never expiring), or null while this build pins none. */
    fun key(): PublicKey? = if (overridden) override else pinned

    /** The previous root's key, whatever the time; [keys] and [anchors] apply the expiry. */
    private fun previousKey(): PublicKey? = if (overridden) overridePrevious else pinnedPrevious

    /** The roots in force at [at]: the current one, then the previous one until [OLD_ROOT_EXPIRES_AT] inclusive. Empty while none is pinned. */
    fun keys(at: Instant = now()): List<PublicKey> {
        val current = key() ?: return emptyList()
        val previous = previousKey()?.takeIf { !at.isAfter(OLD_ROOT_EXPIRES_AT) && !it.encoded.contentEquals(current.encoded) }
        return listOfNotNull(current, previous)
    }

    /** The anchors droidtop's own signatures must chain to at [at], current first; empty while none is pinned. */
    fun anchors(at: Instant = now()): List<ChainSignature.Anchor> = keys(at).map { ChainSignature.Anchor(it, pinned = true) }

    /** Lowercase hex SHA-256 of the roots in force at [at] (SubjectPublicKeyInfo), current first. */
    fun keySha256s(at: Instant = now()): List<String> = keys(at).map { Sha256.hex(it.encoded) }

    /** Lowercase hex SHA-256 of the current root's SubjectPublicKeyInfo, or null while none is pinned. */
    fun keySha256(): String? = key()?.let { Sha256.hex(it.encoded) }

    /** Whether [key] is one of the roots in force at [at]. */
    fun isRoot(key: PublicKey, at: Instant = now()): Boolean = keys(at).any { it.encoded.contentEquals(key.encoded) }

    /** Whether [key] is a droidtop root whose time has passed (the previous one after [OLD_ROOT_EXPIRES_AT]): never an anchor, pinned or not. */
    fun isExpiredRoot(key: PublicKey, at: Instant = now()): Boolean =
        previousKey()?.encoded?.contentEquals(key.encoded) == true && !isRoot(key, at)

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

    private fun load(resource: String): PublicKey? {
        val text = runCatching {
            DroidtopSigningRoot::class.java.classLoader?.getResourceAsStream(resource)?.use { it.readBytes().toString(Charsets.US_ASCII) }
        }.getOrNull() ?: return null
        return parse(text)
    }

    /**
     * Test hook: pins [key] (or, with null, none at all) and [previous] (the root still trusted until
     * [OLD_ROOT_EXPIRES_AT]) for the duration of [block], with the clock at [at] when given.
     */
    fun <T> withPinned(key: PublicKey?, previous: PublicKey? = null, at: Instant? = null, block: () -> T): T {
        val previousOverridden = overridden
        val savedKey = override
        val savedPrevious = overridePrevious
        val savedNow = overrideNow
        override = key
        overridePrevious = previous
        overrideNow = at
        overridden = true
        try {
            return block()
        } finally {
            override = savedKey
            overridePrevious = savedPrevious
            overrideNow = savedNow
            overridden = previousOverridden
        }
    }
}
