package dev.droidtop.pluginhost

import dev.droidtop.runtime.util.Sha256
import java.io.File
import java.security.PublicKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * A plugin certificate (docs/SPEC.md 12a "Per-plugin keys and the plugin
 * master"): droidtop's plugin master key, pinned in the binary
 * ([PluginOriginKeys.master]), states that [publicKey] may sign bundles for
 * the plugin ids in [pluginIds] between [notBefore] and [notAfter] (epoch
 * seconds). Every official plugin repository has its own key and its own
 * certificate; the certificate ships in the bundle as `origin.cert`, put
 * there by the repository's CI from its `PLUGIN_SIGNING_CERT` secret.
 */
data class PluginCertificate(
    /** What a revocation names ([PluginRevocationList.certIds]). */
    val certId: String,
    /** Exact plugin ids, or a namespace written `<prefix>.*`. */
    val pluginIds: List<String>,
    val publicKeyBase64: String,
    val publicKey: PublicKey,
    /** Lowercase hex SHA-256 of the certified key's SubjectPublicKeyInfo DER. */
    val keySha256: String,
    val notBefore: Long,
    val notAfter: Long,
) {
    fun covers(pluginId: String): Boolean = pluginIds.any { entry ->
        if (entry.endsWith(".*")) {
            val prefix = entry.dropLast(1)
            pluginId.startsWith(prefix) && pluginId.length > prefix.length
        } else {
            entry == pluginId
        }
    }

    fun validAt(epochSeconds: Long): Boolean = epochSeconds in notBefore..notAfter
}

/**
 * Reads and checks [PluginCertificate]s. The format is a small JSON document;
 * what the master signs is [signedBytes], a line-per-field text, so the
 * signature never depends on how a JSON writer orders or spaces anything.
 * The owner-run provisioning script that issues certificates builds the same
 * bytes, and both sides pin the same literal vector in their tests.
 *
 *     {
 *       "formatVersion": 1,
 *       "certId": "Droidtop/example-plugin#0",
 *       "pluginIds": ["droidtop.example"],
 *       "publicKeySpki": "<P-256 SubjectPublicKeyInfo, base64>",
 *       "keySha256": "<hex SHA-256 of that DER>",
 *       "notBefore": 1790000000,
 *       "notAfter": 1885000000,
 *       "issuer": { "keySha256": "<master key's hex SHA-256>", "signature": "<base64 DER ECDSA over signedBytes>" }
 *     }
 */
object PluginCertificates {
    /** The bundle entry a certificate travels in, next to manifest.json and manifest.sig. */
    const val FILE_NAME = "origin.cert"

    private const val PREFIX = "droidtop-plugin-cert-v1"
    private const val MAX_CHARS = 16 * 1024
    private val CERT_ID = Regex("[A-Za-z0-9._/#@-]{1,200}")
    private val PLUGIN_ID = Regex("[a-z0-9][a-z0-9._-]*(\\.\\*)?")

    fun signedBytes(certId: String, pluginIds: List<String>, publicKeyBase64: String, notBefore: Long, notAfter: Long): ByteArray =
        buildString {
            append(PREFIX).append('\n')
            append("id:").append(certId).append('\n')
            append("plugins:").append(pluginIds.joinToString(",")).append('\n')
            append("key:").append(publicKeyBase64).append('\n')
            append("notBefore:").append(notBefore).append('\n')
            append("notAfter:").append(notAfter).append('\n')
        }.toByteArray(Charsets.UTF_8)

    /**
     * The certificate in [text], or null when it is not one [master] signed:
     * any missing or malformed field, a key that is not P-256, a `keySha256`
     * that is not the key's own, an issuer fingerprint that is not
     * [master]'s, or a signature that does not verify.
     */
    fun parse(text: String, master: PublicKey): PluginCertificate? = runCatching {
        if (text.length > MAX_CHARS) return null
        val json = JSONObject(text)
        if (json.optInt("formatVersion", -1) != 1) return null
        val certId = json.getString("certId").takeIf { CERT_ID.matches(it) } ?: return null
        val idsJson = json.getJSONArray("pluginIds")
        val pluginIds = (0 until idsJson.length()).map { idsJson.getString(it) }
        if (pluginIds.isEmpty() || pluginIds.any { !PLUGIN_ID.matches(it) }) return null
        val keyBase64 = json.getString("publicKeySpki").trim()
        val key = PluginOriginKeys.parseSpki(keyBase64) ?: return null
        val keySha256 = Sha256.hex(key.encoded)
        if (!json.getString("keySha256").equals(keySha256, ignoreCase = true)) return null
        val notBefore = json.getLong("notBefore")
        val notAfter = json.getLong("notAfter")
        if (notAfter < notBefore) return null
        val issuer = json.getJSONObject("issuer")
        if (!issuer.getString("keySha256").equals(Sha256.hex(master.encoded), ignoreCase = true)) return null
        val signed = signedBytes(certId, pluginIds, keyBase64, notBefore, notAfter)
        if (!BundleSignature.verifyWith(master, signed, issuer.getString("signature"))) return null
        PluginCertificate(certId, pluginIds, keyBase64, key, keySha256, notBefore, notAfter)
    }.getOrNull()
}

/** The master-signed list of withdrawn certificates and keys (docs/SPEC.md 12a "Revocation"). */
data class PluginRevocationList(
    /** Only a list with a higher sequence replaces the stored one, so an old list cannot be replayed to un-revoke anything. */
    val sequence: Long,
    val certIds: Set<String>,
    /** Lowercase hex SHA-256 fingerprints of revoked keys: a plugin key, or the legacy official origin key. */
    val keySha256: Set<String>,
    /**
     * The lists of user-trusted origins whose key is an added catalog's master, by origin (SPEC 12a
     * "Added catalogs"): each signed by that origin's own master and applied to that origin alone.
     */
    val byOrigin: Map<String, PluginRevocationList> = emptyMap(),
) {
    fun revokesCert(certId: String): Boolean = certId in certIds

    fun revokesKey(sha256: String): Boolean = sha256.lowercase() in keySha256

    /** The list that applies to the user-trusted [origin], or [NONE]: the plugin master's list never reaches outside the official origin. */
    fun forOrigin(origin: String): PluginRevocationList = byOrigin[origin] ?: NONE

    companion object {
        val NONE = PluginRevocationList(0, emptySet(), emptySet())
    }
}

/**
 * Revocation by certificate id or key fingerprint, signed by the plugin
 * master and published beside the catalog index
 * (`droidtop-plugins/revocations.json`, fetched with it). The accepted list
 * lives in the plugins root as [FILE_NAME] and is verified again every time
 * it is read: the master's signature is what makes it count, not the fact
 * that it was stored.
 *
 *     {
 *       "formatVersion": 1,
 *       "sequence": 2,
 *       "certIds": ["Droidtop/example-plugin#0"],
 *       "keySha256": ["<hex>"],
 *       "signature": "<base64 DER ECDSA by the master over signedBytes>"
 *     }
 */
object PluginRevocations {
    /** In the plugins root; the leading dot keeps it apart from every plugin id's directory. */
    const val FILE_NAME = ".plugin-revocations.json"

    /** The accepted lists of user-trusted origins, by origin, beside [FILE_NAME]. */
    const val ORIGINS_FILE_NAME = ".origin-revocations.json"

    private const val PREFIX = "droidtop-plugin-revocations-v1"
    private const val MAX_CHARS = 256 * 1024
    private val KEY_SHA256 = Regex("[0-9a-f]{64}")

    /** Entries sorted, one per line, so the signature does not depend on JSON order. */
    fun signedBytes(sequence: Long, certIds: Collection<String>, keySha256: Collection<String>): ByteArray =
        buildString {
            append(PREFIX).append('\n')
            append("sequence:").append(sequence).append('\n')
            certIds.sorted().forEach { append("cert:").append(it).append('\n') }
            keySha256.map { it.lowercase() }.sorted().forEach { append("key:").append(it).append('\n') }
        }.toByteArray(Charsets.UTF_8)

    fun parse(text: String, master: PublicKey): PluginRevocationList? = runCatching {
        if (text.length > MAX_CHARS) return null
        val json = JSONObject(text)
        if (json.optInt("formatVersion", -1) != 1) return null
        val sequence = json.getLong("sequence")
        if (sequence < 1) return null
        val certIds = json.optJSONArray("certIds").strings()
        val keys = json.optJSONArray("keySha256").strings().map { it.lowercase() }
        if (certIds.any { it.isBlank() || it.contains('\n') } || keys.any { !KEY_SHA256.matches(it) }) return null
        if (!BundleSignature.verifyWith(master, signedBytes(sequence, certIds, keys), json.getString("signature"))) return null
        PluginRevocationList(sequence, certIds.toSet(), keys.toSet())
    }.getOrNull()

    /**
     * The accepted list ([PluginRevocationList.NONE] when there is none, no master is pinned, or the
     * stored file no longer verifies), carrying the user-trusted origins' own lists
     * ([PluginRevocationList.byOrigin]).
     */
    fun load(pluginsRoot: File): PluginRevocationList = official(pluginsRoot).copy(byOrigin = originLists(pluginsRoot))

    private fun official(pluginsRoot: File): PluginRevocationList {
        val master = PluginOriginKeys.master() ?: return PluginRevocationList.NONE
        val file = File(pluginsRoot, FILE_NAME)
        if (!file.isFile) return PluginRevocationList.NONE
        val text = runCatching { file.readText() }.getOrNull() ?: return PluginRevocationList.NONE
        return parse(text, master) ?: PluginRevocationList.NONE
    }

    /**
     * Stores [text] as [origin]'s list when [master] (the key the person trusts for that origin, an added
     * catalog's organisation master) signed it, in the same format and over the same bytes as the plugin
     * master's list, and its sequence is higher than the one stored for [origin]. Returns whether it did.
     * Kept as the parsed list in app-private storage, like the trusted key it was checked against.
     */
    fun acceptForOrigin(pluginsRoot: File, origin: String, text: String, master: PublicKey): Boolean {
        val offered = parse(text, master) ?: return false
        val stored = originLists(pluginsRoot)
        if (offered.sequence <= (stored[origin]?.sequence ?: 0)) return false
        val json = JSONObject()
        (stored + (origin to offered)).forEach { (id, list) ->
            json.put(
                id,
                JSONObject()
                    .put("sequence", list.sequence)
                    .put("certIds", JSONArray(list.certIds.sorted()))
                    .put("keySha256", JSONArray(list.keySha256.sorted())),
            )
        }
        pluginsRoot.mkdirs()
        val temp = File(pluginsRoot, "$ORIGINS_FILE_NAME.tmp")
        temp.writeText(json.toString())
        return temp.renameTo(File(pluginsRoot, ORIGINS_FILE_NAME))
    }

    private fun originLists(pluginsRoot: File): Map<String, PluginRevocationList> {
        val file = File(pluginsRoot, ORIGINS_FILE_NAME)
        if (!file.isFile) return emptyMap()
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyMap()
        return json.keys().asSequence().mapNotNull { origin ->
            val entry = json.optJSONObject(origin) ?: return@mapNotNull null
            origin to PluginRevocationList(
                entry.optLong("sequence"),
                entry.optJSONArray("certIds").strings().toSet(),
                entry.optJSONArray("keySha256").strings().map { it.lowercase() }.toSet(),
            )
        }.toMap()
    }

    /**
     * Stores [text] as the accepted list when it verifies against the master
     * and its sequence is higher than the stored one's. Returns whether it
     * replaced the stored list. Written via a temp file and rename, so a
     * failed write leaves the previous list in force.
     */
    fun accept(pluginsRoot: File, text: String): Boolean {
        val master = PluginOriginKeys.master() ?: return false
        val offered = parse(text, master) ?: return false
        if (offered.sequence <= load(pluginsRoot).sequence) return false
        pluginsRoot.mkdirs()
        val temp = File(pluginsRoot, "$FILE_NAME.tmp")
        temp.writeText(text)
        return temp.renameTo(File(pluginsRoot, FILE_NAME))
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
