package dev.droidtop.pluginhost

import dev.droidtop.net.AtomicWrite
import dev.droidtop.shared.trust.ChainSignature
import dev.droidtop.runtime.util.DroidtopSigningRoot
import dev.droidtop.runtime.util.RevocationList
import java.io.File
import java.security.cert.X509Certificate
import org.json.JSONArray
import org.json.JSONObject

/**
 * The revocation lists in force (docs/SPEC.md 12a "Revocation"): droidtop's
 * own, signed under droidtop's signing root, which applies to every chain that
 * ends at that root, and per user-trusted origin the list of the catalog the person
 * trusted it through ([byOrigin]), which applies to that origin alone.
 */
data class PluginRevocationList(
    val list: RevocationList,
    val byOrigin: Map<String, RevocationList> = emptyMap(),
) {
    val sequence: Long get() = list.sequence

    fun revokes(cert: X509Certificate): Boolean = list.revokes(cert)

    /** Lowercase hex SHA-256 of an SPKI: a certified key, or a legacy key ([LegacyBundleSignature]). */
    fun revokesKey(sha256: String): Boolean = list.revokesKey(sha256)

    /** The list that applies to the user-trusted [origin], or [NONE]: droidtop's own list never reaches outside chains anchored at its signing root. */
    fun forOrigin(origin: String): PluginRevocationList = byOrigin[origin]?.let { PluginRevocationList(it) } ?: NONE

    companion object {
        val NONE = PluginRevocationList(RevocationList.NONE)
    }
}

/**
 * Stores and reads the accepted revocation lists. droidtop's list is
 * `droidtop-plugins/revocations.jws` beside the plugin index (fetched with it);
 * it is kept in the plugins root as [FILE_NAME] only when it verifies under
 * droidtop's signing root, its signer names droidtop's catalog ([OFFICIAL_CATALOG_ID]),
 * and its sequence is higher than the one on file, and it is verified again
 * every time it is read: the signature is what makes it count, not the fact
 * that it was stored. An added catalog's lists are kept per origin in
 * [ORIGINS_FILE_NAME], parsed, after the same checks against the catalog's
 * anchor.
 */
object PluginRevocations {
    /** In the plugins root; the leading dot keeps it apart from every plugin id's directory. */
    const val FILE_NAME = ".plugin-revocations.jws"

    /** The accepted lists of user-trusted origins, by origin, beside [FILE_NAME]. */
    const val ORIGINS_FILE_NAME = ".origin-revocations-v2.json"

    /** The catalog id droidtop's own list must name: its plugin catalog's ([PluginCatalogSources] in :library-core). */
    const val OFFICIAL_CATALOG_ID = "droidtop"

    /**
     * The lists in force ([PluginRevocationList.NONE] parts when there is none, no signing root is pinned, or the stored
     * file no longer verifies).
     */
    fun load(pluginsRoot: File): PluginRevocationList = PluginRevocationList(official(pluginsRoot), originLists(pluginsRoot))

    private fun official(pluginsRoot: File): RevocationList {
        val anchor = DroidtopSigningRoot.anchor() ?: return RevocationList.NONE
        val file = File(pluginsRoot, FILE_NAME)
        if (!file.isFile) return RevocationList.NONE
        val text = runCatching { file.readText() }.getOrNull() ?: return RevocationList.NONE
        return RevocationList.parse(text, anchor, OFFICIAL_CATALOG_ID) ?: RevocationList.NONE
    }

    /**
     * Stores [jws] as droidtop's list when it verifies under droidtop's signing root and its sequence is higher than the
     * stored one's. Returns whether it replaced the stored list. Written whole through AtomicWrite, so a failed
     * write leaves the previous list in force.
     */
    fun accept(pluginsRoot: File, jws: String): Boolean {
        val anchor = DroidtopSigningRoot.anchor() ?: return false
        val offered = RevocationList.parse(jws, anchor, OFFICIAL_CATALOG_ID) ?: return false
        if (offered.sequence <= official(pluginsRoot).sequence) return false
        return runCatching { AtomicWrite.text(File(pluginsRoot, FILE_NAME), jws); true }.getOrDefault(false)
    }

    /**
     * Stores [jws] as [origin]'s list when it verifies under [anchor] (the key the person accepted for the added
     * catalog [catalogId]) and names that catalog, and its sequence is higher than the one stored for [origin].
     * Returns whether it did. Kept as the parsed list in app-private storage, like the trusted key it was checked
     * against.
     */
    fun acceptForOrigin(pluginsRoot: File, origin: String, jws: String, anchor: ChainSignature.Anchor, catalogId: String): Boolean {
        val offered = RevocationList.parse(jws, anchor, catalogId) ?: return false
        val stored = originLists(pluginsRoot)
        if (offered.sequence <= (stored[origin]?.sequence ?: 0)) return false
        val json = JSONObject()
        (stored + (origin to offered)).forEach { (id, list) ->
            json.put(
                id,
                JSONObject()
                    .put("sequence", list.sequence)
                    .put("certSha256", JSONArray(list.certSha256.sorted()))
                    .put("keySha256", JSONArray(list.keySha256.sorted())),
            )
        }
        return runCatching { AtomicWrite.text(File(pluginsRoot, ORIGINS_FILE_NAME), json.toString()); true }.getOrDefault(false)
    }

    private fun originLists(pluginsRoot: File): Map<String, RevocationList> {
        val file = File(pluginsRoot, ORIGINS_FILE_NAME)
        if (!file.isFile) return emptyMap()
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyMap()
        return json.keys().asSequence().mapNotNull { origin ->
            val entry = json.optJSONObject(origin) ?: return@mapNotNull null
            origin to RevocationList(
                entry.optLong("sequence"),
                entry.optJSONArray("certSha256").strings().map { it.lowercase() }.toSet(),
                entry.optJSONArray("keySha256").strings().map { it.lowercase() }.toSet(),
            )
        }.toMap()
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
