package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject

/** The plugin API/ABI contract version this build of droidtop speaks. Bump when [PluginApi] changes in a way an old plugin couldn't safely run against. */
const val PLUGIN_CONTRACT_VERSION = 1

/** One payload file's declared hash (trust-boundary checklist point 1: every file, always, no "no hash recorded" skip). */
data class PluginPayloadFile(val path: String, val sha256: String)

/**
 * The signed manifest inside a plugin bundle (docs/SPEC.md 12a). Parsing
 * a JSON blob into this type is NOT validation -- [PluginBundleInstaller]
 * runs the real checks (signature, every hash, ABI coverage, the
 * contract version, capability ids against the closed set) before any of
 * this is trusted. [fromJson] only rejects a manifest that is structurally
 * incomplete, the same "can't even ask what this is" floor
 * [dev.droidtop.library.integrations.Integration.fromJson] uses.
 */
data class PluginManifest(
    /** Stable id, namespaced by origin (checklist point 2): "<origin>.<name>", e.g. "droidtop.sample-statustile". */
    val id: String,
    val origin: String,
    val label: String,
    val description: String?,
    /** Free-form, shown to the user; not compared for anything -- [archiveDigest] is what approval binds to. */
    val version: String,
    val kind: PluginKind,
    val capabilities: Set<PluginCapability>,
    val contractVersion: Int,
    /**
     * Declared, never implied. A plugin that doesn't declare root never
     * gets it, full stop. One that does declare it still gets a
     * no-root device and must keep working there -- root is an
     * enhancement a plugin may use WHEN PRESENT and the user approved
     * it, never something its core function requires (owner directive
     * 2026-09-25: "we don't want anything RELIANT on it"). See
     * [PluginRecord.rootApproved].
     */
    val requestsRoot: Boolean,
    /** The manifest's own claim, corroborated by PluginBundleInstaller reading the real payload paths before this is trusted. */
    val abis: Set<String>,
    /** Fully-qualified class implementing [DroidtopPlugin], loaded by [PluginKind.NATIVE_BUNDLE]'s runner. Unused/absent for kinds without one yet. */
    val entryClass: String?,
    val payload: List<PluginPayloadFile>,
    /**
     * Package names this plugin may hold a LIVE bound-service/binder
     * connection to, declared up front like [requestsRoot] rather than
     * left implicit (cross-cutting need surfaced 2026-09-25: a plugin
     * managing another app sometimes needs an ongoing connection to it,
     * not just a one-shot call through [PluginContext]). Declaring a
     * target here is not itself a grant of anything droidtop can
     * enforce technically -- the isolation is process-crash containment,
     * not a permission system (12a's own framing) -- but it is what the
     * approval screen shows the user before they approve, exactly like
     * [requestsRoot], so "this plugin talks to app X in the background"
     * is never a silent surprise. Empty for a plugin that only ever
     * calls through [PluginContext].
     */
    val boundServiceTargets: Set<String> = emptySet(),
) {
    companion object {
        private val REQUIRED_ABIS = setOf("arm64-v8a", "x86_64")

        fun fromJson(json: JSONObject): PluginManifest? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val origin = json.optString("origin").takeIf { it.isNotBlank() } ?: return null
            val kind = PluginKind.fromId(json.optString("kind")) ?: return null
            val capsJson = json.optJSONArray("capabilities") ?: JSONArray()
            val capabilities = buildSet {
                for (i in 0 until capsJson.length()) {
                    PluginCapability.fromId(capsJson.optString(i))?.let { add(it) }
                }
            }
            if (capabilities.isEmpty()) return null
            val contractVersion = json.optInt("contractVersion", -1)
            if (contractVersion < 0) return null
            val payloadJson = json.optJSONArray("payload") ?: JSONArray()
            val payload = buildList {
                for (i in 0 until payloadJson.length()) {
                    val entry = payloadJson.optJSONObject(i) ?: continue
                    val path = entry.optString("path").takeIf { it.isNotBlank() } ?: continue
                    val sha256 = entry.optString("sha256").takeIf { it.isNotBlank() } ?: continue
                    add(PluginPayloadFile(path, sha256))
                }
            }
            // Checklist point 1: a payload entry with no hash is dropped
            // above, which means the entry count mismatching payloadJson's
            // own length already fails validation downstream (a manifest
            // cannot describe a file and skip its hash).
            if (payload.size != payloadJson.length()) return null
            val abisJson = json.optJSONArray("abis") ?: JSONArray()
            val abis = buildSet { for (i in 0 until abisJson.length()) add(abisJson.optString(i)) }
            val boundTargetsJson = json.optJSONArray("boundServiceTargets") ?: JSONArray()
            val boundServiceTargets = buildSet { for (i in 0 until boundTargetsJson.length()) add(boundTargetsJson.optString(i)) }
            return PluginManifest(
                id = id,
                origin = origin,
                label = json.optString("label").ifBlank { id },
                description = json.optString("description").takeIf { it.isNotBlank() },
                version = json.optString("version").ifBlank { "0" },
                kind = kind,
                capabilities = capabilities,
                contractVersion = contractVersion,
                requestsRoot = json.optBoolean("requestsRoot", false),
                abis = abis,
                entryClass = json.optString("entryClass").takeIf { it.isNotBlank() },
                payload = payload,
                boundServiceTargets = boundServiceTargets,
            )
        }
    }

    /**
     * Structural problems [PluginBundleInstaller] refuses on, beyond the
     * bare parse -- one string per problem, empty when the manifest is
     * fit to proceed to signature/hash verification. Native bundles must
     * cover both required ABIs (the standing bundle rule, §7d) whenever
     * they ship any native library at all; a pure-JVM/dex native bundle
     * with no `.so` payload has nothing to check per ABI.
     */
    fun structuralProblems(): List<String> = buildList {
        if (contractVersion > PLUGIN_CONTRACT_VERSION) {
            add("contractVersion $contractVersion is newer than this build of droidtop understands ($PLUGIN_CONTRACT_VERSION)")
        }
        if (kind == PluginKind.NATIVE_BUNDLE && entryClass == null) {
            add("a native_bundle plugin must declare entryClass")
        }
        val hasNativeLibs = payload.any { it.path.startsWith("lib/") && it.path.endsWith(".so") }
        if (hasNativeLibs && !abis.containsAll(REQUIRED_ABIS)) {
            add("native bundle ships a .so but declares abis=$abis, not both $REQUIRED_ABIS")
        }
        if (id != id.lowercase() || !id.startsWith("$origin.")) {
            add("id \"$id\" must be lowercase and namespaced as \"<origin>.<name>\"")
        }
    }
}
