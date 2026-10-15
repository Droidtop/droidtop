package dev.droidtop.runtime.util

import java.security.PublicKey
import java.security.cert.X509Certificate

/**
 * Droidtop Core (docs/SPEC.md 12a "Signatures: certificate chains to Droidtop
 * Core", Droidtop/tracker#550): droidtop's one pinned trust anchor, the public
 * key of the owner's RSA "Droidtop Core" key, whose certificate their UCM CA
 * issued. Only the public key is pinned: the certificate stays on the owner's
 * machine, and none is needed here, because a chain is anchored by checking
 * that its last certificate verifies under this key. Verification stops there
 * ("We do NOT want to chain the signature past this key"), so nothing above
 * Core, the UCM root included, is trusted, needed or read.
 *
 * It ships as the resource [RESOURCE]: a PEM `PUBLIC KEY` block (or bare
 * base64 SubjectPublicKeyInfo), public material, which the coordinator
 * commits. Until then the file is a placeholder and [key] is null: official
 * bundles in the certificate format are refused with a reason that says so,
 * and the component catalog is accepted unsigned
 * ([CatalogSignature.Verdict.Unpinned]).
 */
object DroidtopCore {
    const val RESOURCE = "dev/droidtop/runtime/util/droidtop-core.pub"

    private val pinned: PublicKey? by lazy { load() }

    @Volatile private var overridden = false
    @Volatile private var override: PublicKey? = null

    /** The pinned Droidtop Core public key, or null while this build pins none. */
    fun key(): PublicKey? = if (overridden) override else pinned

    /** The anchor droidtop's own signatures must chain to, or null while none is pinned. */
    fun anchor(): ChainSignature.Anchor? = key()?.let { ChainSignature.Anchor(it, pinned = true) }

    /** Lowercase hex SHA-256 of Droidtop Core's SubjectPublicKeyInfo, or null while none is pinned. */
    fun keySha256(): String? = key()?.let { Sha256.hex(it.encoded) }

    /** Whether Droidtop Core's key signed [cert] (and [cert] is not Core's own). */
    fun issued(cert: X509Certificate): Boolean {
        val core = key() ?: return false
        if (cert.publicKey.encoded.contentEquals(core.encoded)) return false
        return runCatching { cert.verify(core) }.isSuccess
    }

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
            DroidtopCore::class.java.classLoader?.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.US_ASCII) }
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
