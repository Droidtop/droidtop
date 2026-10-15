package dev.droidtop.runtime.util

import java.security.cert.X509Certificate

/**
 * The Droidtop Core certificate (docs/SPEC.md 12a "Signatures: certificate
 * chains to Droidtop Core", Droidtop/tracker#550): droidtop's one pinned trust
 * anchor. It is the owner's RSA "Droidtop Core" certificate, issued by their
 * UCM CA, and it is pinned by itself: verification walks a signature's chain up
 * to it and stops ("We do NOT want to chain the signature past this key"), so
 * nothing above it, the UCM root included, is trusted, needed or read.
 *
 * It ships as the resource [RESOURCE] (public material). Until the owner
 * commits the real certificate there the file is a placeholder and
 * [certificate] is null: official bundles in the certificate format are
 * refused with a reason that says so, and the component catalog is accepted
 * unsigned ([CatalogSignature.Verdict.Unpinned]).
 */
object DroidtopCore {
    const val RESOURCE = "dev/droidtop/runtime/util/droidtop-core.pem"

    private val pinned: X509Certificate? by lazy { load() }

    @Volatile private var overridden = false
    @Volatile private var override: X509Certificate? = null

    /** The pinned Droidtop Core certificate, or null while this build pins none. */
    fun certificate(): X509Certificate? = if (overridden) override else pinned

    /** The anchor droidtop's own signatures must chain to, or null while none is pinned. */
    fun anchor(): ChainSignature.Anchor? = certificate()?.let { ChainSignature.Anchor(it.publicKey, it) }

    /** Lowercase hex SHA-256 of Droidtop Core's SubjectPublicKeyInfo, or null while none is pinned. */
    fun keySha256(): String? = certificate()?.let { Sha256.hex(it.publicKey.encoded) }

    /** Whether [cert] was issued by Droidtop Core: its issuer is Core's subject and Core's key signed it. */
    fun issued(cert: X509Certificate): Boolean {
        val core = certificate() ?: return false
        return cert.issuerX500Principal == core.subjectX500Principal && runCatching { cert.verify(core.publicKey) }.isSuccess
    }

    private fun load(): X509Certificate? {
        val text = runCatching {
            DroidtopCore::class.java.classLoader?.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.US_ASCII) }
        }.getOrNull() ?: return null
        if (!text.contains("-----BEGIN CERTIFICATE-----")) return null
        return ChainSignature.parseCertificate(text)?.takeIf { ChainSignature.acceptableKey(it.publicKey) }
    }

    /** Test hook: pins [certificate] (or, with null, none at all) for the duration of [block]. */
    fun <T> withPinned(certificate: X509Certificate?, block: () -> T): T {
        val previousOverridden = overridden
        val previous = override
        override = certificate
        overridden = true
        try {
            return block()
        } finally {
            override = previous
            overridden = previousOverridden
        }
    }
}
