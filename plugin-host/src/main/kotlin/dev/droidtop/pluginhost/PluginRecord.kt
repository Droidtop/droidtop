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
    /** SHA-256 over the exact signed manifest bytes -- what this installed copy was verified against. */
    val archiveDigest: String,
    /**
     * The fingerprint ([BundleVerdict.Verified.anchorSha256]) of the trust
     * anchor this bundle verified under when it was (re)installed: the
     * plugin master for a certified official bundle, the legacy official
     * key, or the user-trusted key. What approval is bound to, alongside
     * the digest (docs/SPEC.md 12a checklist point 4, "Trust over
     * updates"): an update verified under the same anchor
     * ([PluginOriginKeys.sameTrustAnchor]) carries the APPROVED state over
     * to its new digest, and anything else starts PENDING.
     * Empty for records written by builds before the field existed: they
     * get no carry-over on their first update, one re-approval, the safe
     * direction.
     */
    val approvedKeySha256: String = "",
    val trust: PluginTrustState,
    val enabled: Boolean,
    /** True only once the user approved THIS plugin's root request on the approval screen. Independent of whether the device even has root -- PluginRunner checks device root separately at call time, so this bit alone never grants anything. */
    val rootApproved: Boolean,
    /** Set by [PluginCrashPolicy] when a plugin crashes; cleared by re-enabling from the approval screen, which is the one deliberate manual step (12a point 6: a crash disables, it doesn't silently retry forever). */
    val disabledReason: String?,
    /** The technical reason behind [disabledReason] (the raw load failure), for a "Technical details" line and a bug report; never the headline, which is the plain sentence. */
    val disabledDetail: String? = null,
    /**
     * The droidtop build (version code) that disabled the plugin, 0 for a record written before the field existed.
     * A disable is evidence about the plugin AND the droidtop that ran it, so a newer droidtop tries it once more
     * ([afterHostUpdate]); without that a plugin stopped by a bug in an old droidtop stays stopped for ever.
     */
    val disabledBuild: Int = 0,
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
        put("runtimeVersion", manifest.runtimeVersion ?: JSONObject.NULL)
        put("boundServiceTargets", JSONArray(manifest.boundServiceTargets.toList()))
        // Found and fixed 2026-09-27 (dq-pluginui-01): this field was
        // missing from both toJson() and fromJson() below, so
        // PluginManifest.subscribedEvents -- read correctly off the
        // real, signed manifest at install time -- was silently dropped
        // the moment droidtop persisted its OWN copy of this record
        // (record.json, written right after install and read back by
        // every later PluginStore.installed() call). A plugin's
        // subscribedEvents was never actually zero; PluginEventBus's own
        // "in it.manifest.subscribedEvents" filter was checking a set
        // that had already been reset to empty by this round trip, so
        // NO plugin's event hook could ever fire, regardless of what its
        // manifest declared.
        put("subscribedEvents", JSONArray(manifest.subscribedEvents.toList()))
        // Contract 2 fields: the same keys the manifest uses, so PluginManifest and the record share one parser (V2Declarations).
        manifest.v2.putInto(this)
        put(
            "payload",
            JSONArray(
                manifest.payload.map { f -> JSONObject().put("path", f.path).put("sha256", f.sha256) },
            ),
        )
        put("archiveDigest", archiveDigest)
        put("approvedKeySha256", approvedKeySha256)
        put("trust", trust.name)
        put("enabled", enabled)
        put("rootApproved", rootApproved)
        put("disabledReason", disabledReason ?: JSONObject.NULL)
        put("disabledDetail", disabledDetail ?: JSONObject.NULL)
        put("disabledBuild", disabledBuild)
    }

    companion object {
        /**
         * A field written as `value ?: JSONObject.NULL` (description,
         * entryClass, disabledReason) comes back from org.json's own
         * [JSONObject.optString] as the literal four-character string
         * "null", not a real null -- [JSONObject.NULL]'s own `toString()`
         * -- so `.takeIf { it.isNotBlank() }` alone kept that text
         * forever. Confirmed on the rig (dq-plugins-01): approving a
         * freshly-installed plugin wrote disabledReason as
         * [JSONObject.NULL], and the very next read back showed
         * "Disabled after a crash: null" with no crash having happened
         * at all, because [PluginRecord.runnable] never saw a real null.
         * [JSONObject.isNull] is org.json's own correct check for this
         * (true for a missing key OR one explicitly holding
         * [JSONObject.NULL]).
         */
        private fun optNullableString(json: JSONObject, key: String): String? =
            if (json.isNull(key)) null else json.optString(key).takeIf { it.isNotBlank() }

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
            val subscribedEventsJson = json.optJSONArray("subscribedEvents") ?: JSONArray()
            val subscribedEvents = buildSet { for (i in 0 until subscribedEventsJson.length()) add(subscribedEventsJson.optString(i)) }
            val manifest = PluginManifest(
                id = json.optString("id"),
                origin = json.optString("origin"),
                label = json.optString("label"),
                description = optNullableString(json, "description"),
                version = json.optString("version"),
                kind = kind,
                capabilities = capabilities,
                contractVersion = json.optInt("contractVersion"),
                requestsRoot = json.optBoolean("requestsRoot"),
                abis = buildSet { for (i in 0 until abisJson.length()) add(abisJson.optString(i)) },
                entryClass = optNullableString(json, "entryClass"),
                runtimeVersion = optNullableString(json, "runtimeVersion"),
                payload = payload,
                boundServiceTargets = boundServiceTargets,
                subscribedEvents = subscribedEvents,
                v2 = V2Declarations.fromJson(json),
            ).withDerivedV2()
            val trust = runCatching { PluginTrustState.valueOf(json.optString("trust")) }.getOrNull() ?: PluginTrustState.PENDING
            return PluginRecord(
                manifest = manifest,
                archiveDigest = json.optString("archiveDigest"),
                // Missing (a record a pre-carry-over build wrote) is "",
                // which is exactly "no carry-over", the safe default.
                approvedKeySha256 = json.optString("approvedKeySha256"),
                trust = trust,
                enabled = json.optBoolean("enabled", trust == PluginTrustState.APPROVED),
                rootApproved = json.optBoolean("rootApproved", false),
                disabledReason = optNullableString(json, "disabledReason"),
                disabledDetail = optNullableString(json, "disabledDetail"),
                disabledBuild = json.optInt("disabledBuild", 0),
            )
        }
    }

    /**
     * This record as a newer droidtop [hostBuild] finds it: an approved plugin that an older droidtop disabled after a
     * failure is switched back on, to be tried again and disabled again if it still fails; null when nothing changes.
     * A plugin the person turned off (no [disabledReason]) is never touched.
     */
    fun afterHostUpdate(hostBuild: Int): PluginRecord? {
        if (hostBuild <= 0 || disabledReason == null || disabledBuild == hostBuild || trust != PluginTrustState.APPROVED) return null
        return copy(enabled = true, disabledReason = null, disabledDetail = null, disabledBuild = 0)
    }

    /** Whether this plugin is actually allowed to run right now, folding every gate into one check so a runner never has to re-derive it. */
    fun runnable(): Boolean = trust == PluginTrustState.APPROVED && enabled && disabledReason == null
}
