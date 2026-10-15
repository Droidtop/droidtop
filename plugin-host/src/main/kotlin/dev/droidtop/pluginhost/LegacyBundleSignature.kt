package dev.droidtop.pluginhost

import dev.droidtop.runtime.util.Sha256
import java.io.File
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE LEGACY PATH, BOUNDED (docs/SPEC.md 12a "Migration from raw keys",
 * Droidtop/tracker#550). Before the certificate format, a bundle carried
 * `manifest.sig` (base64 DER ECDSA P-256 over manifest.json) and, when
 * certified, `origin.cert` (a JSON certificate from droidtop's P-256 plugin
 * master or an added catalog's organisation master). This object verifies
 * such a bundle ONLY when it is already installed, before each activation
 * ([PluginBundleInstaller.verifyInstalled]), so plugins installed before the
 * change keep running until they update. It is never used to install or
 * update: those need `manifest.jws` ([BundleSignature]).
 *
 * Revocation still reaches it: droidtop's Core-signed list stops a legacy
 * plugin by naming the legacy origin key's, the old master's or a certified
 * key's SPKI SHA-256, and the old stored lists ([OLD_REVOCATIONS],
 * [OLD_ORIGIN_REVOCATIONS]) are read as they are (nothing can extend them:
 * the old master seed is gone).
 *
 * To be deleted, with its call site, the test and the two old stored list
 * files, in the first droidtop release after every official plugin
 * repository has published a certificate-format release in the catalog.
 */
internal object LegacyBundleSignature {
    const val SIGNATURE_FILE = "manifest.sig"
    const val CERTIFICATE_FILE = "origin.cert"

    /** The old plugin master (P-256), sha256 c29e030ddd8615bb22ac706e0dcd3cfc58a248fb1adbd8e31a6fb7bf8159452f. */
    private const val OLD_MASTER =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAExkY++u48QalIMbG7izeEk7vPR95te22fYlgsWPvpoOI/X5DEi9w8EmCbFUjpfT6rIZSkGNF+Xp0Xp95pUsBg9A=="

    /** The legacy official origin key, which signed official bundles before per-plugin keys. */
    private const val LEGACY_ORIGIN_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEYs1dY+vQaHC/tj9XEfv2KxHfCzLtsgyIi/ANwdSuKyoGcp7kCNXoy0sYhOaOdFUTpV4tnkKsICv/EY8q+zM5Dw=="

    private const val OLD_REVOCATIONS = ".plugin-revocations.json"
    private const val OLD_ORIGIN_REVOCATIONS = ".origin-revocations.json"
    private const val CERT_PREFIX = "droidtop-plugin-cert-v1"
    private const val REVOCATIONS_PREFIX = "droidtop-plugin-revocations-v1"

    @Volatile private var testKeys: Pair<String, String>? = null

    private fun originKey(): String = testKeys?.first ?: LEGACY_ORIGIN_KEY

    private fun master(): String = testKeys?.second ?: OLD_MASTER

    /** The legacy official anchors' fingerprints: the legacy origin key's and the old master's. */
    fun officialFingerprints(): Set<String> = listOfNotNull(parse(originKey()), parse(master())).map { Sha256.hex(it.encoded) }.toSet()

    /**
     * An installed legacy bundle's verdict. [revocations] is droidtop's current list, consulted by key; the old
     * stored lists in [pluginsRoot] are consulted as well.
     */
    fun verifyInstalled(
        manifestBytes: ByteArray,
        signatureBase64: String,
        origin: String,
        pluginId: String,
        certificateText: String?,
        userKeys: Map<String, String>,
        pluginsRoot: File,
        revocations: PluginRevocationList,
    ): BundleVerdict {
        val official = PluginOriginKeys.isOfficial(origin)
        val anchorText = if (official) {
            if (certificateText != null) master() else originKey()
        } else {
            userKeys[origin] ?: return BundleVerdict.Refused("no key you trust for origin \"$origin\"")
        }
        val anchor = parse(anchorText) ?: return BundleVerdict.Refused("the key this plugin was signed under is not a P-256 key")
        val anchorSha = Sha256.hex(anchor.encoded)
        val old = if (official) oldOfficialList(pluginsRoot) else oldOriginLists(pluginsRoot)[origin] ?: OldList.NONE
        fun withdrawn(keySha: String): Boolean =
            (official && revocations.revokesKey(keySha)) || revocations.forOrigin(origin).revokesKey(keySha) || keySha in old.keys
        if (withdrawn(anchorSha)) return BundleVerdict.Refused("the key this plugin was signed under has been withdrawn; it needs a certified release")
        var signingKey = anchor
        if (certificateText != null) {
            val cert = parseCertificate(certificateText, anchor)
                ?: return BundleVerdict.Refused("$CERTIFICATE_FILE is not a certificate signed by the key it names")
            if (!cert.covers(pluginId)) return BundleVerdict.Refused("the certificate is for ${cert.pluginIds.joinToString()}, not \"$pluginId\"")
            if (cert.certId in old.certIds || withdrawn(cert.keySha256)) return BundleVerdict.Refused("the certificate \"${cert.certId}\" has been revoked")
            signingKey = cert.publicKey
        }
        if (!verify(signingKey, manifestBytes, signatureBase64)) {
            return BundleVerdict.Refused("signature doesn't verify against a trusted key for origin \"$origin\"")
        }
        return BundleVerdict.Verified(anchorSha, null)
    }

    /** Test hook: pins throwaway legacy keys for the duration of [block]. */
    fun <T> withKeys(originKeyBase64: String, masterBase64: String, block: () -> T): T {
        val previous = testKeys
        testKeys = originKeyBase64 to masterBase64
        try {
            return block()
        } finally {
            testKeys = previous
        }
    }

    // ----- The old formats, read-only -----

    private class OldCertificate(val certId: String, val pluginIds: List<String>, val publicKey: PublicKey, val keySha256: String) {
        fun covers(pluginId: String): Boolean = pluginIds.any { entry ->
            if (entry.endsWith(".*")) {
                val prefix = entry.dropLast(1)
                pluginId.startsWith(prefix) && pluginId.length > prefix.length
            } else {
                entry == pluginId
            }
        }
    }

    private class OldList(val certIds: Set<String>, val keys: Set<String>) {
        companion object {
            val NONE = OldList(emptySet(), emptySet())
        }
    }

    private fun parse(keyBase64: String): PublicKey? {
        val bytes = runCatching { Base64.getMimeDecoder().decode(keyBase64.trim()) }.getOrNull() ?: return null
        val key = runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes)) }.getOrNull() as? ECPublicKey ?: return null
        return key.takeIf { it.params?.curve?.field?.fieldSize == 256 }
    }

    private fun verify(key: PublicKey, data: ByteArray, signatureBase64: String): Boolean {
        val signature = runCatching { Base64.getMimeDecoder().decode(signatureBase64.trim()) }.getOrNull() ?: return false
        return runCatching {
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(key)
                update(data)
            }.verify(signature)
        }.getOrDefault(false)
    }

    private fun parseCertificate(text: String, master: PublicKey): OldCertificate? = runCatching {
        val json = JSONObject(text)
        if (json.optInt("formatVersion", -1) != 1) return null
        val certId = json.getString("certId")
        val idsJson = json.getJSONArray("pluginIds")
        val pluginIds = (0 until idsJson.length()).map { idsJson.getString(it) }
        val keyBase64 = json.getString("publicKeySpki").trim()
        val key = parse(keyBase64) ?: return null
        val keySha256 = Sha256.hex(key.encoded)
        if (!json.getString("keySha256").equals(keySha256, ignoreCase = true)) return null
        val notBefore = json.getLong("notBefore")
        val notAfter = json.getLong("notAfter")
        val issuer = json.getJSONObject("issuer")
        if (!issuer.getString("keySha256").equals(Sha256.hex(master.encoded), ignoreCase = true)) return null
        val signed = buildString {
            append(CERT_PREFIX).append('\n')
            append("id:").append(certId).append('\n')
            append("plugins:").append(pluginIds.joinToString(",")).append('\n')
            append("key:").append(keyBase64).append('\n')
            append("notBefore:").append(notBefore).append('\n')
            append("notAfter:").append(notAfter).append('\n')
        }.toByteArray(Charsets.UTF_8)
        if (!verify(master, signed, issuer.getString("signature"))) return null
        OldCertificate(certId, pluginIds, key, keySha256)
    }.getOrNull()

    private fun oldOfficialList(pluginsRoot: File): OldList = runCatching {
        val json = JSONObject(File(pluginsRoot, OLD_REVOCATIONS).readText())
        val sequence = json.getLong("sequence")
        val certIds = json.optJSONArray("certIds").strings()
        val keys = json.optJSONArray("keySha256").strings().map { it.lowercase() }
        val signed = buildString {
            append(REVOCATIONS_PREFIX).append('\n')
            append("sequence:").append(sequence).append('\n')
            certIds.sorted().forEach { append("cert:").append(it).append('\n') }
            keys.sorted().forEach { append("key:").append(it).append('\n') }
        }.toByteArray(Charsets.UTF_8)
        val master = parse(master())!!
        if (!verify(master, signed, json.getString("signature"))) OldList.NONE else OldList(certIds.toSet(), keys.toSet())
    }.getOrDefault(OldList.NONE)

    private fun oldOriginLists(pluginsRoot: File): Map<String, OldList> = runCatching {
        val json = JSONObject(File(pluginsRoot, OLD_ORIGIN_REVOCATIONS).readText())
        json.keys().asSequence().mapNotNull { origin ->
            val entry = json.optJSONObject(origin) ?: return@mapNotNull null
            origin to OldList(entry.optJSONArray("certIds").strings().toSet(), entry.optJSONArray("keySha256").strings().map { it.lowercase() }.toSet())
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
