package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject

/**
 * Superseded keys (Droidtop/tracker#887). The owner, 2026-11-05: "Every key is superceded by the current key. It's a
 * simple check: if it's marked as "superceded", then trust the key it's superceded by, which'll be denoted. We still
 * tell the user the key is changing, of course, but we don't have to carry an entire history, and we can still mark
 * superceded per repo, per root, and etc, so different trust sources can mark them differently", and "the place that
 * provides the "superceded" notice has to be the same source the plugin came from for it to be trusted".
 *
 * A trust source's own signed metadata (a catalog's index `key` and `catalog.key`, a repository's
 * droidtop-plugin-key.json) carries `"superseded": [{"keySha256": "<old>", "by": "<current>"}]`. Every old key points
 * at the source's current key; there are no dates, chains or sunsets. [follows] is the whole check. Pure.
 */
object KeySupersession {
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /** A key block's markers, old key SHA-256 to the key that supersedes it; anything unreadable is none. */
    fun markers(block: JSONObject?): Map<String, String> {
        val array: JSONArray = block?.optJSONArray("superseded") ?: return emptyMap()
        return buildMap {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val old = o.optString("keySha256").lowercase().takeIf { HEX64.matches(it) } ?: continue
                val by = o.optString("by").lowercase().takeIf { HEX64.matches(it) } ?: continue
                if (old != by) put(old, by)
            }
        }
    }

    /**
     * Whether [markers] (from the source the trust came from, the caller's to make sure) say the trusted key
     * [trustedSha256] is superseded by exactly [currentSha256], the key the same source names now.
     */
    fun follows(markers: Map<String, String>, trustedSha256: String, currentSha256: String): Boolean =
        markers[trustedSha256.lowercase()] == currentSha256.lowercase()
}
