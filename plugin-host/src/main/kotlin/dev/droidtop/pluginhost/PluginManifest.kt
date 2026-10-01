package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject

/**
 * The highest plugin contract version this build of droidtop reads
 * (docs/plugin-api.md 7): 1 is today's capabilities and [PluginApi], 2
 * adds the manifest fields in [V2Declarations]. A contract 1 manifest is
 * translated to the 2 shape by [LegacyManifest.toV2], so both install and
 * run; the v2 call envelope itself is not served yet, so v2 plugins are
 * reached through the capabilities their `provides` maps to. Bump when
 * [PluginApi] changes in a way an old plugin couldn't safely run against.
 */
const val PLUGIN_CONTRACT_VERSION = 2

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
    /**
     * [PluginKind.NATIVE_BUNDLE]: the fully-qualified class implementing
     * [DroidtopPlugin], loaded by [PluginRuntimeService]'s
     * `DexClassLoader` path. [PluginKind.PYTHON] has no dex class to
     * name, so this field is unused for it -- a python-kind plugin's
     * entry point is always its payload's single `plugin.py`
     * ([PluginManifest.structuralProblems] requires it be present),
     * loaded by [PythonDroidtopPlugin] instead. Absent for
     * [PluginKind.FLUTTER_EMBED], which has no runner yet.
     */
    val entryClass: String?,
    /**
     * [PluginKind.FLUTTER_EMBED] only: the exact pinned Flutter engine
     * version (the git hash [FlutterRuntimeManager] downloads
     * `libflutter.so` for, e.g. "af7e796e161ae0bb1ff0758c71a7105418bd9ded")
     * this plugin's `libapp.so` AOT snapshot was built against. A Dart
     * AOT snapshot's format is tied to the exact engine build that
     * produced it, not a semantic-version range (confirmed against real
     * Flutter tooling errors -- "Snapshot not compatible with the
     * current VM configuration" -- docs/SPEC.md 12a's flutter_embed
     * section), so this is checked byte-for-byte against
     * [FlutterRuntimeManager.pinnedVersion] before activation, the same
     * "runtime version pinned per plugin" requirement the owner's plan
     * called for. Unused (null) for every other kind.
     */
    val runtimeVersion: String? = null,
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
    /**
     * [dev.droidtop.pluginhost.PluginEvent] ids this plugin wants
     * delivered to [DroidtopPlugin.onEvent] (docs/SPEC.md 12a "Event
     * hooks"). Declared, not implied -- same shape as [boundServiceTargets]
     * and [requestsRoot]: an id droidtop doesn't recognise (an older
     * plugin build subscribing to an event a newer droidtop retired, or
     * a typo) is silently never matched rather than a validation
     * failure, so a manifest stays forward- and backward-compatible
     * across [PLUGIN_EVENT_CONTRACT_VERSION] bumps the same way an
     * unrecognised [PluginCapability] id already is in [PluginManifest.fromJson].
     * Empty for a plugin that never reacts to droidtop's own state
     * changes, which is most plugins.
     */
    val subscribedEvents: Set<String> = emptySet(),
    /**
     * The contract 2 fields: provides, permissions, subscribes, exports,
     * requires. For a contract 1 manifest this is derived by
     * [LegacyManifest.toV2] at parse time (never read from the manifest),
     * so everything above the parser sees one model.
     */
    val v2: V2Declarations = V2Declarations.EMPTY,
) {
    companion object {
        private val REQUIRED_ABIS = setOf("arm64-v8a", "x86_64")

        /**
         * A field a manifest may write as JSON `null` (description,
         * entryClass) needs [JSONObject.isNull], not
         * `optString(key).takeIf { it.isNotBlank() }` -- confirmed on the
         * rig (dq-plugins-01) via [PluginRecord]'s own copy of this same
         * pattern: Android's bundled org.json returns the literal string
         * "null" for a key holding [JSONObject.NULL], not an empty
         * string, so the old check kept "null" as if it were real text.
         * PluginManifestTest's JVM unit test never caught it because its
         * testImplementation org.json:json jar (a different
         * implementation than the one Android ships) returns "" there
         * instead -- a real behavioural gap between the test jar and the
         * device, not something a unit test alone could have shown.
         */
        private fun optNullableString(json: JSONObject, key: String): String? =
            if (json.isNull(key)) null else json.optString(key).takeIf { it.isNotBlank() }

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
            val contractVersion = json.optInt("contractVersion", -1)
            if (contractVersion < 0) return null
            val declaredV2 = if (contractVersion >= 2) V2Declarations.fromJson(json) else V2Declarations.EMPTY
            // A v2 plugin may declare only `provides`; the capabilities they map to are served like declared ones.
            val allCapabilities = capabilities + LegacyManifest.capabilitiesFor(declaredV2.provides)
            // A v2 plugin whose points map to no capability (a tray item) is still installable.
            if (allCapabilities.isEmpty() && declaredV2.provides.isEmpty()) return null
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
            val subscribedEventsJson = json.optJSONArray("subscribedEvents") ?: JSONArray()
            val subscribedEvents = buildSet { for (i in 0 until subscribedEventsJson.length()) add(subscribedEventsJson.optString(i)) }
            val manifest = PluginManifest(
                id = id,
                origin = origin,
                label = json.optString("label").ifBlank { id },
                description = optNullableString(json, "description"),
                version = json.optString("version").ifBlank { "0" },
                kind = kind,
                capabilities = allCapabilities,
                contractVersion = contractVersion,
                requestsRoot = json.optBoolean("requestsRoot", false),
                abis = abis,
                entryClass = optNullableString(json, "entryClass"),
                runtimeVersion = optNullableString(json, "runtimeVersion"),
                payload = payload,
                boundServiceTargets = boundServiceTargets,
                subscribedEvents = subscribedEvents,
                v2 = declaredV2,
            )
            return manifest.withDerivedV2()
        }
    }

    /** A contract 1 manifest gets its v2 declarations derived; a contract 2 one keeps what it declared. */
    fun withDerivedV2(): PluginManifest = if (contractVersion >= 2) this else copy(v2 = LegacyManifest.toV2(this))

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
        if (kind == PluginKind.PYTHON && payload.none { it.path == "plugin.py" }) {
            add("a python plugin must ship plugin.py at its payload root")
        }
        if (kind == PluginKind.FLUTTER_EMBED) {
            if (runtimeVersion.isNullOrBlank()) {
                add("a flutter_embed plugin must declare runtimeVersion (the exact pinned Flutter engine version its libapp.so was built against)")
            }
            if (payload.none { it.path.startsWith("lib/") && it.path.endsWith("/libapp.so") }) {
                add("a flutter_embed plugin must ship lib/<abi>/libapp.so")
            }
            if (payload.none { it.path.startsWith("flutter_assets/") }) {
                add("a flutter_embed plugin must ship its flutter_assets/ tree")
            }
        }
        val hasNativeLibs = payload.any { it.path.startsWith("lib/") && it.path.endsWith(".so") }
        if (hasNativeLibs && !abis.containsAll(REQUIRED_ABIS)) {
            add("native bundle ships a .so but declares abis=$abis, not both $REQUIRED_ABIS")
        }
        if (id != id.lowercase() || !id.startsWith("$origin.")) {
            add("id \"$id\" must be lowercase and namespaced as \"<origin>.<name>\"")
        }
        // Contract 2 declarations (docs/plugin-api.md 1.5, 2.7, 4.1). Unknown ids are NOT a problem here: see [unsupportedDeclarations].
        v2.requires.filter { (it.api.startsWith("priv.") || it.api.startsWith("root.")) && !it.optional }.forEach {
            add("requires \"${it.api}\" must be optional: privileged access is never something a plugin's core function depends on")
        }
        // ui.main (docs/plugin-api.md 1.7): only a kind that can host a full screen may declare one, and it must say where it starts.
        v2.provides.filter { it.point == PluginMainUi.POINT }.forEach {
            if (kind != PluginKind.FLUTTER_EMBED) {
                add("ui.main is hosted for flutter_embed plugins only; a ${kind.id} plugin cannot declare it")
            } else if (PluginMainUi.entryOf(it) == null) {
                add("ui.main must name the Dart entrypoint it starts (\"entrypoint\")")
            }
        }
        if (!PluginOriginKeys.isOfficial(origin)) {
            v2.permissions.filter { PluginPermissions.find(it.id)?.officialOnly == true }.forEach {
                add("permission \"${it.id}\" is restricted to the official origin")
            }
            v2.provides.filter { ExtensionPoints.find(it.point)?.officialOnly == true }.forEach {
                add("extension point \"${it.point}\" is restricted to the official origin")
            }
        }
    }

    /**
     * Declared ids this build of droidtop does not know, one line each, for
     * the approval screen's "Not supported by this version of droidtop"
     * (docs/plugin-api.md 1.2). Never a refusal: a newer plugin still
     * installs on an older droidtop, it just does not get those parts. A
     * permission the plugin's own `exports` declares is its own, not
     * unknown.
     */
    fun unsupportedDeclarations(): List<String> = buildList {
        val ownPermissions = v2.exports.flatMap { api -> api.permissions.map { it.id } }.toSet()
        v2.provides.filter { !ExtensionPoints.supports(it.point, it.version) }.forEach {
            add(if (ExtensionPoints.find(it.point) == null) "extension point ${it.point}" else "extension point ${it.point} version ${it.version}")
        }
        v2.permissions.filter { !PluginPermissions.isSupported(it.id) && it.id !in ownPermissions }.forEach { add("permission ${it.id}") }
        v2.subscribes.filter { PluginEvent.fromId(it.event) == null && it.event != "library.default_player_changed" }.forEach { add("event ${it.event}") }
    }
}
