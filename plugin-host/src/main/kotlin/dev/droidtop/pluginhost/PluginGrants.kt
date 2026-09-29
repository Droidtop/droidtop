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
 * What an update changed that needs the user's say-so (docs/plugin-api.md
 * 1.5, threat T2): dangerous permissions, high-risk extension points and
 * exports the new version declares and the old one did not. [permissions]
 * never holds a `provide:` id: those are [points], one mechanism each.
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
            val oldExports = old.v2.exports.map { it.api }.toSet()
            return PermissionDiff(
                permissions = new.v2.permissions
                    .filter { it.id !in oldPermissions && !it.id.startsWith(PluginPermissions.PROVIDE_PREFIX) && PluginGrants.tierOf(it) != PermissionTier.NORMAL }
                    .map { it.id }
                    .distinct(),
                points = new.v2.provides
                    .filter { it.point !in oldPoints && ExtensionPoints.find(it.point)?.risk?.needsConsent == true }
                    .map { it.point }
                    .distinct(),
                exports = new.v2.exports.map { it.api }.filter { it !in oldExports }.distinct(),
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

    /** Records that a call reached [permission] while it was still `ask` and could not prompt (a background call). */
    fun noteWanted(pluginId: String, permission: String) = synchronized(LOCK) {
        val snap = readLocked(pluginId)
        if (snap.corrupt || permission in snap.wanted) return@synchronized
        writeLocked(pluginId, snap.copy(wanted = snap.wanted + permission))
    }

    /**
     * The state written when the user approves [record] (docs/plugin-api.md
     * 4.3): normal permissions and points are granted, a dangerous or
     * critical one is granted only when in [ticked] and stays `ask`
     * otherwise, and a contract 1 plugin keeps what it always had (its root
     * tick becomes the `priv.shell.root` grant).
     */
    fun initialiseOnApproval(record: PluginRecord, ticked: Set<String> = emptySet()) = synchronized(LOCK) {
        val states = linkedMapOf<String, GrantState>()
        for (declared in record.manifest.v2.permissions) {
            if (declared.id.startsWith(PluginPermissions.PROVIDE_PREFIX)) continue
            states[declared.id] = if (record.manifest.contractVersion < 2) {
                defaultFor(record, declared)
            } else if (tierOf(declared) == PermissionTier.NORMAL || declared.id in ticked) {
                GrantState.GRANTED
            } else {
                GrantState.ASK
            }
        }
        for (entry in record.manifest.v2.provides) {
            val point = ExtensionPoints.find(entry.point) ?: continue
            val key = PluginPermissions.PROVIDE_PREFIX + entry.point
            states[key] = if (record.manifest.contractVersion < 2 || !point.risk.needsConsent || key in ticked) GrantState.GRANTED else GrantState.ASK
        }
        for (export in record.manifest.v2.exports) states[EXPORT_PREFIX + export.api] = GrantState.GRANTED
        writeLocked(record.manifest.id, Snapshot(states = states))
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
        for (export in old.manifest.v2.exports) states[EXPORT_PREFIX + export.api] = exportState(snap, export.api)
        // Explicit entries the user set win over what was just derived.
        states.putAll(snap.states.filterKeys { it in states })
        for (declared in new.manifest.v2.permissions) {
            if (declared.id.startsWith(PluginPermissions.PROVIDE_PREFIX) || declared.id in states) continue
            states[declared.id] = if (declared.id in diff.permissions) GrantState.ASK else defaultFor(new, declared)
        }
        for (entry in new.manifest.v2.provides) {
            val key = PluginPermissions.PROVIDE_PREFIX + entry.point
            if (key !in states) states[key] = if (entry.point in diff.points) GrantState.ASK else provideState(new, Snapshot(), entry.point)
        }
        for (export in new.manifest.v2.exports) {
            val key = EXPORT_PREFIX + export.api
            if (key !in states) states[key] = if (export.api in diff.exports) GrantState.ASK else GrantState.GRANTED
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

        /** Whether the plugin's export of [api] is on: an update's new export waits for a grant. */
        fun exportState(snapshot: Snapshot, api: String): GrantState {
            if (snapshot.corrupt) return GrantState.ASK
            return snapshot.states[EXPORT_PREFIX + api] ?: GrantState.GRANTED
        }
    }
}
