package dev.droidtop.pluginhost

import android.content.Context
import java.io.File
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
            return PermissionDiff(
                permissions = new.v2.permissions
                    .filter { it.id !in oldPermissions && !it.id.startsWith(PluginPermissions.PROVIDE_PREFIX) }
                    .map { it.id }
                    .distinct(),
                points = new.v2.provides
                    .filter { it.point !in oldPoints && ExtensionPoints.find(it.point) != null }
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
 * written whole through AtomicWrite so a crash never leaves half a
 * file. Ids are the registry's own (`net.any`), plus two host-made kinds:
 * `provide:<point>` for a high-risk extension point and `export:<api>` for
 * an API the plugin offers other plugins.
 *
 * A permission with no explicit entry has a default that depends on what
 * the permission is ([defaultFor]): normal permissions are granted, and
 * dangerous and critical ones are `ask`. A file that cannot be read is
 * treated as `ask` for everything, never as `granted`.
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

    private val store = PerPluginJsonStore(dir)

    fun read(pluginId: String): Snapshot = synchronized(LOCK) { readLocked(pluginId) }

    // A corrupt file is never set aside or read as empty here: it would fall back to the defaults, which allow every
    // normal permission. It reads as "ask about everything" until the person answers again.
    private fun readLocked(pluginId: String): Snapshot {
        val json = when (val read = store.read(pluginId)) {
            PerPluginJsonStore.Read.Absent -> return Snapshot()
            is PerPluginJsonStore.Read.Corrupt -> return Snapshot(corrupt = true)
            is PerPluginJsonStore.Read.Found -> read.json
        }
        return try {
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
        val json = JSONObject()
        json.put("states", JSONObject().also { o -> snapshot.states.toSortedMap().forEach { (k, v) -> o.put(k, v.name) } })
        json.put("wanted", JSONArray(snapshot.wanted.sorted()))
        json.put("fresh", JSONArray(snapshot.fresh.sorted()))
        store.write(pluginId, json)
        changed()
        PluginEpoch.bump()
    }

    /** Every explicit state of [pluginId]; a permission without an entry is not here, see [stateOf] for the effective one. */
    fun grantsFor(pluginId: String): Map<String, GrantState> = read(pluginId).states

    /**
     * Sets one state, as the user's own choice: it also clears "wanted" and "wants new access" for that item. A changed
     * hole permission ([PluginTiers.HOLES]) or shared-files permission ([SharedFiles.PERMISSIONS]) retires the
     * plugin's process, whose filter and file rules can never change: the next call starts it afresh with what is granted
     * now, so a refused network or folder is gone at once (docs/plugin-api.md 5.3).
     */
    fun set(pluginId: String, permission: String, state: GrantState) {
        // A choice made on the Permissions page replaces a Not now from earlier in this run (Droidtop/tracker#518).
        PluginRefusals.forgetNotNow(pluginId, permission)
        val changedHole = synchronized(LOCK) {
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
            (permission in PluginTiers.HOLES || permission in SharedFiles.PERMISSIONS) && snap.states[permission] != state
        }
        if (changedHole) runCatching { PluginProcesses.retire(pluginId) }
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
     * the plugin declares `required` stays `ask` (the first-use sheet can still ask for it); every other
     * unticked item, an optional permission, a normal permission, an extension point or an export,
     * is `denied`. Null [ticked] is the list as first shown
     * ([defaultTicked]).
     */
    fun initialiseOnApproval(record: PluginRecord, ticked: Set<String>? = null) = synchronized(LOCK) {
        val on = ticked ?: approvalTicked(record, readLocked(record.manifest.id))
        val states = linkedMapOf<String, GrantState>()
        for (declared in record.manifest.v2.permissions) {
            if (declared.id.startsWith(PluginPermissions.PROVIDE_PREFIX)) continue
            states[declared.id] = answerFor(declared.id, tierOf(declared), on, declared.required)
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
            val declared = record.manifest.v2.permissions.firstOrNull { it.id == item }
            states[item] = answerFor(item, declared?.let { tierOf(it) } ?: PermissionTier.NORMAL, ticked, declared?.required ?: false)
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
        // So are "Where it appears" (docs/plugin-api.md 1.9, and its facet targets, 1.10) and each service's or schedule's own switch.
        states.putAll(snap.states.filterKeys { it.startsWith(PluginModes.KEY_PREFIX) || it.startsWith(BackgroundProtocol.ENTRY_PREFIX) ||
            it.startsWith(FacetGuards.TARGET_KEY_PREFIX) || it.startsWith(FacetGuards.FACET_KEY_PREFIX) })
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
        store.delete(pluginId)
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

        private val _changes = kotlinx.coroutines.flow.MutableStateFlow(0L)

        /** Bumps on every write, so a screen of grants (a plugin's page, its Permissions) is read again when one changes. */
        val changes: kotlinx.coroutines.flow.StateFlow<Long> = _changes

        internal fun changed() {
            _changes.value = _changes.value + 1
        }
        private val LOCK = Any()

        fun forPluginsRoot(pluginsRoot: File): PluginGrants = PluginGrants(File(pluginsRoot.parentFile ?: pluginsRoot, "plugin-grants"))

        fun forContext(context: Context): PluginGrants = forPluginsRoot(PluginStore.root(context))

        /**
         * A ticked item is granted. An unticked dangerous or critical permission the plugin declares as
         * required still waits for the first-use sheet (it cannot work without it, and the list says
         * so); every other unticked item, an optional permission included, is denied, so the tick box
         * really declines it (Droidtop/tracker#512).
         */
        private fun answerFor(item: String, tier: PermissionTier, ticked: Set<String>, required: Boolean = false): GrantState = when {
            item in ticked -> GrantState.GRANTED
            tier != PermissionTier.NORMAL && required -> GrantState.ASK
            else -> GrantState.DENIED
        }

        /**
         * What the approval list starts as (docs/SPEC.md 12a, "Approval is a list"): every item ticked,
         * except what SPEC already holds back, a dangerous or critical permission and a high-risk
         * extension point, which start unticked.
         */
        fun defaultTicked(record: PluginRecord): Set<String> = buildSet {
            for (declared in record.manifest.v2.permissions) {
                if (declared.id.startsWith(PluginPermissions.PROVIDE_PREFIX)) continue
                if (tierOf(declared) == PermissionTier.NORMAL) add(declared.id)
            }
            for (entry in record.manifest.v2.provides) {
                val point = ExtensionPoints.find(entry.point) ?: continue
                if (!point.risk.needsConsent) add(PluginPermissions.PROVIDE_PREFIX + entry.point)
            }
            for (export in record.manifest.v2.exports) add(EXPORT_PREFIX + export.grantKey)
        }

        /**
         * What the approval list starts as for [record] given what was stored for it before ([snapshot]): an item the
         * person had answered keeps that answer (allowed ticked, blocked unticked), and only what was never answered
         * starts as [defaultTicked]. A plugin sent back to waiting by an update signed with another key, or approved
         * again after it was denied, is not reset to the defaults that block every high-risk point
         * (Droidtop/tracker#270); the person still sees every tick before approving.
         */
        fun approvalTicked(record: PluginRecord, snapshot: Snapshot): Set<String> {
            val defaults = defaultTicked(record)
            if (snapshot.corrupt || snapshot.states.isEmpty()) return defaults
            val items = buildList {
                record.manifest.v2.permissions.filterNot { it.id.startsWith(PluginPermissions.PROVIDE_PREFIX) }.forEach { add(it.id) }
                record.manifest.v2.provides.forEach { add(PluginPermissions.PROVIDE_PREFIX + it.point) }
                record.manifest.v2.exports.forEach { add(EXPORT_PREFIX + it.grantKey) }
            }
            return items.filterTo(LinkedHashSet()) { item ->
                when (snapshot.states[item]) {
                    GrantState.GRANTED -> true
                    GrantState.DENIED -> false
                    else -> item in defaults
                }
            }
        }

        /** The tier a declared permission is asked at. An id the registry does not know is a provider's own permission: asked like a dangerous one. */
        fun tierOf(declared: DeclaredPermission): PermissionTier {
            val scopeAny = declared.id == "apps.intents.out" &&
                runCatching { JSONObject(declared.extra).optString("scope") }.getOrDefault("") == "any"
            return PluginPermissions.tierFor(declared.id, scopeIsAny = scopeAny) ?: PermissionTier.DANGEROUS
        }

        /** What a permission is when nothing was stored for it. */
        fun defaultFor(record: PluginRecord, declared: DeclaredPermission): GrantState = when {
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

        /** Whether the plugin may provide [point]: high-risk points wait for a grant. */
        fun provideState(record: PluginRecord, snapshot: Snapshot, point: String): GrantState {
            if (snapshot.corrupt) return GrantState.ASK
            snapshot.states[PluginPermissions.PROVIDE_PREFIX + point]?.let { return it }
            val risky = ExtensionPoints.find(point)?.risk?.needsConsent == true
            return if (!risky) GrantState.GRANTED else GrantState.ASK
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
         * Asks the person on the first-use sheet whether [record] may provide [point], a point it declares that is set to
         * Ask first or not answered yet, and keeps the answer: Allow turns it on, Never blocks it, Not now leaves it to be
         * asked again (Droidtop/tracker#270). True when it is allowed afterwards. Blocks until answered: off the main
         * thread, and only for something the person just did.
         */
        fun askPoint(context: Context, record: PluginRecord, point: String): Boolean {
            val id = record.manifest.id
            val store = forContext(context)
            if (!ExtensionPoints.declares(record.manifest, point)) return false
            when (provideState(record, store.read(id), point)) {
                GrantState.GRANTED -> return true
                GrantState.DENIED -> return false
                GrantState.ASK -> Unit
            }
            val known = ExtensionPoints.find(point)
            val key = PluginPermissions.PROVIDE_PREFIX + point
            val label = known?.label?.replaceFirstChar { it.lowercase() } ?: point
            val tier = if (known?.risk == PointRisk.CRITICAL) PermissionTier.CRITICAL else PermissionTier.DANGEROUS
            return when (PluginGrantPrompts.ask(GrantPromptRequest(id, record.manifest.label, key, label, known?.lets?.ifEmpty { null }, tier))) {
                GrantAnswer.ALLOW -> {
                    store.set(id, key, GrantState.GRANTED)
                    true
                }
                GrantAnswer.NEVER -> {
                    store.set(id, key, GrantState.DENIED)
                    false
                }
                GrantAnswer.NOT_NOW -> false
            }
        }

        /**
         * Asks the person on the first-use sheet whether [record] may use [permission], one it declares, because they
         * pressed a facet's grant action (docs/plugin-api.md 1.10, "Interaction"): the sheet shows the manifest's reason,
         * and the plugin cannot word it. Asked even when it was turned off before, since the person asked for it. Allow
         * grants it, Never turns it off, Not now changes nothing. True when it is granted afterwards. Blocks until
         * answered: off the main thread, and only for something the person just did.
         */
        fun askPermission(context: Context, record: PluginRecord, permission: String): Boolean {
            val declared = record.manifest.v2.permissions.firstOrNull { it.id == permission } ?: return false
            val known = PluginPermissions.find(permission) ?: return false
            val id = record.manifest.id
            val store = forContext(context)
            if (stateOf(record, store.read(id), permission) == GrantState.GRANTED) return true
            return when (PluginGrantPrompts.ask(GrantPromptRequest(id, record.manifest.label, permission, known.label, declared.reason, known.tier))) {
                GrantAnswer.ALLOW -> {
                    store.set(id, permission, GrantState.GRANTED)
                    true
                }
                GrantAnswer.NEVER -> {
                    store.set(id, permission, GrantState.DENIED)
                    false
                }
                GrantAnswer.NOT_NOW -> false
            }
        }

        /**
         * Why a job must not start, or null when it may. A job names its point in the envelope it carries
         * (`args["call"]`) and is checked as that point; one started without an envelope is checked as the point its
         * [capability] is served for ([LegacyManifest.CAPABILITY_POINTS]).
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
