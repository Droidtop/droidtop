package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject

/**
 * One installed plugin's persisted state -- what [PluginStore] keeps in
 * `filesDir/plugins/state.json`, alongside the plugin's own private
 * directory (`filesDir/plugins/<id>/`) holding its extracted payload.
 * Nothing here is the manifest itself; the manifest is re-read from the
 * installed copy so it can never drift from what was actually verified.
 */
data class PluginRecord(
    val manifest: PluginManifest,
    /** SHA-256 over the exact signed manifest bytes -- what approval is bound to (checklist point 4: "approval that never carries over to a new digest"). */
    val archiveDigest: String,
    val trust: PluginTrustState,
    val enabled: Boolean,
    /** True only once the user approved THIS plugin's root request on the approval screen. Independent of whether the device even has root -- PluginRunner checks device root separately at call time, so this bit alone never grants anything. */
    val rootApproved: Boolean,
    /** Set by [PluginCrashPolicy] when a plugin crashes; cleared by re-enabling from the approval screen, which is the one deliberate manual step (12a point 6: a crash disables, it doesn't silently retry forever). */
    val disabledReason: String?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", manifest.id)
        put("origin", manifest.origin)
        put("label", manifest.label)
        put("description", manifest.description ?: JSONObject.NULL)
        put("version", manifest.version)
        put("kind", manifest.kind.id)
        put("capabilities", JSONArray(manifest.capabilities.map { it.id }))
        put("contractVersion", manifest.contractVersion)
        put("requestsRoot", manifest.requestsRoot)
        put("abis", JSONArray(manifest.abis.toList()))
        put("entryClass", manifest.entryClass ?: JSONObject.NULL)
        put("boundServiceTargets", JSONArray(manifest.boundServiceTargets.toList()))
        put(
            "payload",
            JSONArray(
                manifest.payload.map { f -> JSONObject().put("path", f.path).put("sha256", f.sha256) },
            ),
        )
        put("archiveDigest", archiveDigest)
        put("trust", trust.name)
        put("enabled", enabled)
        put("rootApproved", rootApproved)
        put("disabledReason", disabledReason ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): PluginRecord? {
            val payloadJson = json.optJSONArray("payload") ?: JSONArray()
            val payload = buildList {
                for (i in 0 until payloadJson.length()) {
                    val entry = payloadJson.optJSONObject(i) ?: continue
                    add(PluginPayloadFile(entry.optString("path"), entry.optString("sha256")))
                }
            }
            val capsJson = json.optJSONArray("capabilities") ?: JSONArray()
            val capabilities = buildSet {
                for (i in 0 until capsJson.length()) PluginCapability.fromId(capsJson.optString(i))?.let { add(it) }
            }
            val kind = PluginKind.fromId(json.optString("kind")) ?: return null
            val abisJson = json.optJSONArray("abis") ?: JSONArray()
            val boundTargetsJson = json.optJSONArray("boundServiceTargets") ?: JSONArray()
            val boundServiceTargets = buildSet { for (i in 0 until boundTargetsJson.length()) add(boundTargetsJson.optString(i)) }
            val manifest = PluginManifest(
                id = json.optString("id"),
                origin = json.optString("origin"),
                label = json.optString("label"),
                description = json.optString("description").takeIf { it.isNotBlank() },
                version = json.optString("version"),
                kind = kind,
                capabilities = capabilities,
                contractVersion = json.optInt("contractVersion"),
                requestsRoot = json.optBoolean("requestsRoot"),
                abis = buildSet { for (i in 0 until abisJson.length()) add(abisJson.optString(i)) },
                entryClass = json.optString("entryClass").takeIf { it.isNotBlank() },
                payload = payload,
                boundServiceTargets = boundServiceTargets,
            )
            val trust = runCatching { PluginTrustState.valueOf(json.optString("trust")) }.getOrNull() ?: PluginTrustState.PENDING
            return PluginRecord(
                manifest = manifest,
                archiveDigest = json.optString("archiveDigest"),
                trust = trust,
                enabled = json.optBoolean("enabled", trust == PluginTrustState.APPROVED),
                rootApproved = json.optBoolean("rootApproved", false),
                disabledReason = json.optString("disabledReason").takeIf { it.isNotBlank() },
            )
        }
    }

    /** Whether this plugin is actually allowed to run right now, folding every gate into one check so a runner never has to re-derive it. */
    fun runnable(): Boolean = trust == PluginTrustState.APPROVED && enabled && disabledReason == null
}
