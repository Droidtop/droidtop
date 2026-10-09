package dev.droidtop.pluginhost

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** docs/plugin-api.md 4.3: the three states a (plugin, permission) pair is in. */
enum class GrantState {
    GRANTED,
    DENIED,

    /** The default for dangerous and critical permissions the user has not ticked: asked on first use. */
    ASK,
    ;

    companion object {
        fun fromId(id: String?): GrantState? = entries.firstOrNull { it.name.equals(id, ignoreCase = true) }
    }
}

/**
 * What an update added, every item of it (docs/plugin-api.md 4.3, "Updates"):
 * permissions, extension points and exports the new version declares and
 * the old one did not. The user is asked about these and only these, on
 * the same list as at approval. [permissions] never holds a `provide:` id:
 * those are [points], one mechanism each.
 */
data class PermissionDiff(
    val permissions: List<String>,
    val points: List<String>,
    val exports: List<String>,
) {
    val isEmpty: Boolean get() = permissions.isEmpty() && points.isEmpty() && exports.isEmpty()

    companion object {
        fun between(old: PluginManifest, new: PluginManifest): PermissionDiff {
            val oldPermissions = old.v2.permissions.map { it.id }.toSet()
            val oldPoints = old.v2.provides.map { it.point }.toSet()
            val oldExports = old.v2.exports.map { it.grantKey }.toSet()
            // A contract 1 plugin holds what it could always do: only its high-risk news is asked about.
            val v2 = new.contractVersion >= 2
            return PermissionDiff(
                permissions = new.v2.permissions
                    .filter { it.id !in oldPermissions && !it.id.startsWith(PluginPermissions.PROVIDE_PREFIX) && (v2 || PluginGrants.tierOf(it) != PermissionTier.NORMAL) }
                    .map { it.id }
                    .distinct(),
                points = new.v2.provides
                    .filter { it.point !in oldPoints && ExtensionPoints.find(it.point)?.let { p -> v2 || p.risk.needsConsent } == true }
                    .map { it.point }
                    .distinct(),
                exports = new.v2.exports.map { it.grantKey }.filter { it !in oldExports }.distinct(),
            )
        }
    }
}

/**
 * The grant store (docs/plugin-api.md 4.3): for every plugin, an explicit
 * [GrantState] per permission id, one JSON file per plugin under [dir],
 * written through a temp file and renamed so a crash never leaves half a
 * file. Ids are the registry's own (`net.any`), plus two host-made kinds:
 * `provide:<point>` for a high-risk extension point and `export:<api>` for
 * an API the plugin offers other plugins.
 *
 * A permission with no explicit entry has a default that depends on what
 * the plugin is ([defaultFor]): normal permissions are granted, dangerous
 * and critical ones are `ask`, and a contract 1 plugin holds everything it
 * could already do (docs/plugin-api.md 6, "no new prompts for existing
 * behaviour"). A file that cannot be read is treated as `ask` for
 * everything, never as `granted`.
 */
class PluginGrants(private val dir: File) {
    /** [wanted]: permissions a call reached while still `ask`. [fresh]: items an update added that still wait for the user. */
    data class Snapshot(
        val states: Map<String, GrantState> = emptyMap(),
        val wanted: Set<String> = emptySet(),
        val fresh: Set<String> = emptySet(),
        val corrupt: Boolean = false,
    ) {
        /** True while an update's new items are unanswered: the Plugins row says "Wants new access". */
        val wantsNewAccess: Boolean get() = fresh.any { states[it] == GrantState.ASK }
    }

    private fun fileFor(pluginId: String) = File(dir, "$pluginId.json")

    fun read(pluginId: String): Snapshot = synchronized(LOCK) { readLocked(pluginId) }

    private fun readLocked(pluginId: String): Snapshot {
        val file = fileFor(pluginId)
        if (!file.isFile) return Snapshot()
        return try {
            val json = JSONObject(file.readText())
            val states = json.optJSONObject("states") ?: JSONObject()
            Snapshot(
                states = buildMap { states.keys().forEach { k -> GrantState.fromId(states.optString(k))?.let { put(k, it) } } },
                wanted = strings(json.optJSONArray("wanted")),
                fresh = strings(json.optJSONArray("fresh")),
            )
        } catch (e: Exception) {
            Snapshot(corrupt = true)
        }
    }

    private fun strings(array: JSONArray?): Set<String> = buildSet { if (array != null) for (i in 0 until array.length()) add(array.optString(i)) }

    private fun writeLocked(pluginId: String, snapshot: Snapshot) {
        dir.mkdirs()
        val json = JSONObject()
        json.put("states", JSONObject().also { o -> snapshot.states.toSortedMap().forEach { (k, v) -> o.put(k, v.name) } })
        json.put("wanted", JSONArray(snapshot.wanted.sorted()))
        json.put("fresh", JSONArray(snapshot.fresh.sorted()))
        val tmp = File(dir, "$pluginId.json.tmp")
        tmp.writeText(json.toString())
        Files.move(tmp.toPath(), fileFor(pluginId).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        PluginEpoch.bump()
    }

    /** Every explicit state of [pluginId]; a permission without an entry is not here, see [stateOf] for the effective one. */
    fun grantsFor(pluginId: String): Map<String, GrantState> = read(pluginId).states

    /** Sets one state, as the user's own choice: it also clears "wanted" and "wants new access" for that item. */
    fun set(pluginId: String, permission: String, state: GrantState) = synchronized(LOCK) {
        val snap = readLocked(pluginId)
        writeLocked(
            pluginId,
            snap.copy(
                states = if (snap.corrupt) mapOf(permission to state) else snap.states + (permission to state),
                wanted = snap.wanted - permission,
                fresh = snap.fresh - permission,
                corrupt = false,
            ),
        )
    }

    /** Stores a category choice in the plugin's grant file. */
    fun setCategory(pluginId: String, category: String, state: GrantState) =
        set(pluginId, PluginPermissionPolicy.categoryKey(category), state)

    /** Stores one API call choice, which takes precedence over its category. */
    fun setCall(pluginId: String, call: String, state: GrantState) =
        set(pluginId, PluginPermissionPolicy.callKey(call), state)

    /** Removes one category or call override so resolution falls back to the next level. */
    fun resetChoice(pluginId: String, key: String) = synchronized(LOCK) {
        val snap = readLocked(pluginId)
        if (key !in snap.states) return@synchronized
        writeLocked(pluginId, snap.copy(states = snap.states - key, wanted = snap.wanted - key, fresh = snap.fresh - key))
    }

    /** Resolves the request against this plugin's persisted per-plugin choices. */
    fun resolve(pluginId: String, request: PluginPermissionRequest): GrantState =
        PluginPermissionPolicy.resolve(request, read(pluginId).states)

    /** Records that a call reached [permission] while it was still `ask` and could not prompt (a background call). */
    fun noteWanted(pluginId: String, permission: String) = synchronized(LOCK) {
        val snap = readLocked(pluginId)
        if (snap.corrupt || permission in snap.wanted) return@synchronized
        writeLocked(pluginId, snap.copy(wanted = snap.wanted + permission))
    }

    /**
     * The state written when the user approves [record] with the items in
     * [ticked] (docs/plugin-api.md 4.3): a ticked item is granted and an
     * unticked one is not. An unticked dangerous or critical permission
     * stays `ask` (the first-use sheet can still ask for it); every other
     * unticked item, a normal permission, an extension point or an export,
     * is `denied`. Null [ticked] is the list as first shown
     * ([defaultTicked]). A contract 1 plugin keeps the permissions it always
     * had (its root tick becomes the `priv.shell.root` grant); its points
     * and the rest follow [ticked] like any other.
     */
    fun initialiseOnApproval(record: PluginRecord, ticked: Set<String>? = null) = synchronized(LOCK) {
        val on = ticked ?: defaultTicked(record)
        val states = linkedMapOf<String, GrantState>()
        for (declared in record.manifest.v2.permissions) {
            if (declared.id.startsWith(PluginPermissions.PROVIDE_PREFIX)) continue
            states[declared.id] = if (record.manifest.contractVersion < 2) defaultFor(record, declared) else answerFor(declared.id, tierOf(declared), on)
        }
        for (entry in record.manifest.v2.provides) {
            if (ExtensionPoints.find(entry.point) == null) continue
            states[PluginPermissions.PROVIDE_PREFIX + entry.point] = answerFor(PluginPermissions.PROVIDE_PREFIX + entry.point, PermissionTier.NORMAL, on)
        }
        for (export in record.manifest.v2.exports) states[EXPORT_PREFIX + export.grantKey] = answerFor(EXPORT_PREFIX + export.grantKey, PermissionTier.NORMAL, on)
        writeLocked(record.manifest.id, Snapshot(states = states))
    }

    /**
     * The user's answer to an update's new items (docs/plugin-api.md 4.3,
     * "Updates"): each of [ids] becomes granted when in [ticked] and
     * otherwise denied (`ask` for a dangerous permission), and stops being
     * "new". Nothing else in the grants changes.
     */
    fun answerNew(record: PluginRecord, ids: Set<String>, ticked: Set<String>) = synchronized(LOCK) {
        val id = record.manifest.id
        val snap = readLocked(id)
        val states = snap.states.toMutableMap()
        for (item in ids) {
            val tier = record.manifest.v2.permissions.firstOrNull { it.id == item }?.let { tierOf(it) } ?: PermissionTier.NORMAL
            states[item] = answerFor(item, tier, ticked)
        }
        writeLocked(id, snap.copy(states = states, wanted = snap.wanted - ids, fresh = snap.fresh - ids, corrupt = false))
    }

    /**
     * Same-key update (docs/plugin-api.md 1.5): the plugin keeps every
     * grant it effectively had for [old], and each item [PermissionDiff]
     * finds in [new] starts at `ask` and is remembered as fresh. Grants of
     * an old plugin that never had a file are written out first, so the
     * result no longer depends on defaults that an update could change.
     */
    fun applyUpdate(old: PluginRecord, new: PluginRecord): PermissionDiff = synchronized(LOCK) {
        val id = new.manifest.id
        val diff = PermissionDiff.between(old.manifest, new.manifest)
        val snap = readLocked(id)
        val states = linkedMapOf<String, GrantState>()
        for (declared in old.manifest.v2.permissions) {
            if (declared.id.startsWith(PluginPermissions.PROVIDE_PREFIX)) continue
            states[declared.id] = stateOf(old, snap, declared.id) ?: continue
        }
        for (entry in old.manifest.v2.provides) states[PluginPermissions.PROVIDE_PREFIX + entry.point] = provideState(old, snap, entry.point)
        for (export in old.manifest.v2.exports) states[EXPORT_PREFIX + export.grantKey] = exportState(snap, export.grantKey)
        // Explicit entries the user set win over what was just derived.
        states.putAll(snap.states.filterKeys { it in states })
        // Category and call overrides (Droidtop/tracker#263) are the user's own and outlive an update.
        states.putAll(snap.states.filterKeys { it.startsWith(PluginPermissionPolicy.CATEGORY_PREFIX) || it.startsWith(PluginPermissionPolicy.CALL_PREFIX) })
        // So are "Where it appears" (docs/plugin-api.md 1.9) and each service's or schedule's own switch.
        states.putAll(snap.states.filterKeys { it.startsWith(PluginModes.KEY_PREFIX) || it.startsWith(BackgroundProtocol.ENTRY_PREFIX) })
        for (declared in new.manifest.v2.permissions) {
            if (declared.id.startsWith(PluginPermissions.PROVIDE_PREFIX) || declared.id in states) continue
            states[declared.id] = if (declared.id in diff.permissions) GrantState.ASK else defaultFor(new, declared)
        }
        for (entry in new.manifest.v2.provides) {
            val key = PluginPermissions.PROVIDE_PREFIX + entry.point
            if (key !in states) states[key] = if (entry.point in diff.points) GrantState.ASK else provideState(new, Snapshot(), entry.point)
        }
        for (export in new.manifest.v2.exports) {
            val key = EXPORT_PREFIX + export.grantKey
            if (key !in states) states[key] = if (export.grantKey in diff.exports) GrantState.ASK else GrantState.GRANTED
        }
        val fresh = buildSet {
            addAll(diff.permissions)
            addAll(diff.points.map { PluginPermissions.PROVIDE_PREFIX + it })
            addAll(diff.exports.map { EXPORT_PREFIX + it })
        }
        writeLocked(id, Snapshot(states = states, wanted = snap.wanted.filter { it in states }.toSet(), fresh = (snap.fresh + fresh).filter { states[it] == GrantState.ASK }.toSet()))
        diff
    }

    /** Uninstall: the grants and the install id go with the plugin. */
    fun delete(pluginId: String) = synchronized(LOCK) {
        fileFor(pluginId).delete()
        File(dir, "$pluginId.json.tmp").delete()
        File(dir, "$pluginId.installid").delete()
        PluginEpoch.bump()
    }

    /** A random id per install, the only identity `host.info` gives a plugin (docs/plugin-api.md 3 G6). */
    fun installIdFor(pluginId: String): String = synchronized(LOCK) {
        dir.mkdirs()
        val file = File(dir, "$pluginId.installid")
        file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString().also { file.writeText(it) }
    }

    companion object {
        const val EXPORT_PREFIX = "export:"
        private val LOCK = Any()

        fun forPluginsRoot(pluginsRoot: File): PluginGrants = PluginGrants(File(pluginsRoot.parentFile ?: pluginsRoot, "plugin-grants"))

        fun forContext(context: Context): PluginGrants = forPluginsRoot(PluginStore.root(context))

        /** A ticked item is granted; an unticked dangerous or critical permission waits for the first-use sheet, anything else unticked is denied. */
        private fun answerFor(item: String, tier: PermissionTier, ticked: Set<String>): GrantState = when {
            item in ticked -> GrantState.GRANTED
            tier != PermissionTier.NORMAL -> GrantState.ASK
            else -> GrantState.DENIED
        }

        /**
         * What the approval list starts as (docs/SPEC.md 12a, "Approval is a list"): every item ticked,
         * except what SPEC already holds back, a dangerous or critical permission and a high-risk
         * extension point, which start unticked. A contract 1 plugin holds what it could always do, so
         * all of its points start ticked.
         */
        fun defaultTicked(record: PluginRecord): Set<String> = buildSet {
            val v2 = record.manifest.contractVersion >= 2
            for (declared in record.manifest.v2.permissions) {
                if (declared.id.startsWith(PluginPermissions.PROVIDE_PREFIX)) continue
                if (v2 && tierOf(declared) == PermissionTier.NORMAL) add(declared.id)
            }
            for (entry in record.manifest.v2.provides) {
                val point = ExtensionPoints.find(entry.point) ?: continue
                if (!v2 || !point.risk.needsConsent) add(PluginPermissions.PROVIDE_PREFIX + entry.point)
            }
            for (export in record.manifest.v2.exports) add(EXPORT_PREFIX + export.grantKey)
        }

        /** The tier a declared permission is asked at. An id the registry does not know is a provider's own permission: asked like a dangerous one. */
        fun tierOf(declared: DeclaredPermission): PermissionTier {
            val scopeAny = declared.id == "apps.intents.out" &&
                runCatching { JSONObject(declared.extra).optString("scope") }.getOrDefault("") == "any"
            return PluginPermissions.tierFor(declared.id, scopeIsAny = scopeAny) ?: PermissionTier.DANGEROUS
        }

        /** What a permission is when nothing was stored for it. */
        fun defaultFor(record: PluginRecord, declared: DeclaredPermission): GrantState = when {
            record.manifest.contractVersion < 2 ->
                if (declared.id == "priv.shell.root") (if (record.rootApproved) GrantState.GRANTED else GrantState.ASK) else GrantState.GRANTED
            tierOf(declared) == PermissionTier.NORMAL -> GrantState.GRANTED
            else -> GrantState.ASK
        }

        /** The effective state of [permission] for [record], or null when the plugin never declared it. */
        fun stateOf(record: PluginRecord, snapshot: Snapshot, permission: String): GrantState? {
            if (permission.startsWith(PluginPermissions.PROVIDE_PREFIX)) {
                return provideState(record, snapshot, permission.removePrefix(PluginPermissions.PROVIDE_PREFIX))
            }
            val declared = record.manifest.v2.permissions.firstOrNull { it.id == permission } ?: return null
            if (snapshot.corrupt) return GrantState.ASK
            return snapshot.states[permission] ?: defaultFor(record, declared)
        }

        /** Whether the plugin may provide [point]: high-risk points of a contract 2 plugin wait for a grant. */
        fun provideState(record: PluginRecord, snapshot: Snapshot, point: String): GrantState {
            if (snapshot.corrupt) return GrantState.ASK
            snapshot.states[PluginPermissions.PROVIDE_PREFIX + point]?.let { return it }
            val risky = ExtensionPoints.find(point)?.risk?.needsConsent == true
            return if (record.manifest.contractVersion < 2 || !risky) GrantState.GRANTED else GrantState.ASK
        }

        /**
         * Why a call to [point] must not be made, or null when it may be (docs/plugin-api.md 4.3, "A denied point"). The one
         * check every host call to a plugin goes through: a point the plugin never declared (or declared only at a version
         * this build does not serve), and a point the user did not allow or has not answered yet, is never called at all.
         * An `api:` call to a provider is checked by the broker, not here.
         */
        fun pointRefusal(record: PluginRecord, snapshot: Snapshot, point: String): String? {
            if (point.startsWith("api:")) return null
            val label = ExtensionPoints.find(point)?.label?.replaceFirstChar { it.lowercase() } ?: point
            if (!ExtensionPoints.declares(record.manifest, point)) return "${record.manifest.label} does not offer $label"
            if (provideState(record, snapshot, point) == GrantState.GRANTED) return null
            return "${record.manifest.label} has not been allowed to $label"
        }

        /**
         * Why a job must not start, or null when it may. A contract 2 job names its point in the envelope it carries
         * (`args["call"]`) and is checked as that point; a contract 1 job has no envelope and is checked as the point
         * its [capability] replaced.
         */
        fun jobRefusal(record: PluginRecord, snapshot: Snapshot, capability: PluginCapability, args: Map<String, String>): String? {
            val point = args["call"]?.let { PluginCall.fromJson(it)?.point } ?: LegacyManifest.CAPABILITY_POINTS[capability] ?: return null
            return pointRefusal(record, snapshot, point)
        }

        /** Whether the plugin's export under [key] ([ExportedApi.grantKey]) is on: an update's new export waits for a grant. */
        fun exportState(snapshot: Snapshot, key: String): GrantState {
            if (snapshot.corrupt) return GrantState.ASK
            return snapshot.states[EXPORT_PREFIX + key] ?: GrantState.GRANTED
        }
    }
}
