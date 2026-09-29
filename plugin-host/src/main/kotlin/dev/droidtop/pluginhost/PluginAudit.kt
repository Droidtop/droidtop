package dev.droidtop.pluginhost

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/**
 * One line of a plugin's activity (docs/plugin-api.md 4.6): [target] is a
 * one-line summary (a package, a domain), never contents; [via] is the
 * chain of plugins a brokered call went through; [result] is `ok` or the
 * error code.
 */
data class AuditEntry(
    val atMs: Long,
    val permission: String,
    val api: String,
    val op: String,
    val target: String,
    val via: List<String>,
    val result: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("t", atMs).put("perm", permission).put("api", api).put("op", op)
        .put("target", target).put("via", org.json.JSONArray(via)).put("result", result)

    companion object {
        fun fromJson(json: JSONObject): AuditEntry = AuditEntry(
            atMs = json.optLong("t"),
            permission = json.optString("perm"),
            api = json.optString("api"),
            op = json.optString("op"),
            target = json.optString("target"),
            via = json.optJSONArray("via")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty(),
            result = json.optString("result"),
        )
    }
}

/**
 * The per-plugin activity ring (docs/plugin-api.md 4.6): the last 2,000
 * entries or 30 days, whichever is fewer, one JSON line each, in droidtop's
 * private storage. Only calls that need a dangerous or critical permission
 * are written (the broker decides); normal-permission calls are not
 * recorded yet. After an uninstall the ring is kept 7 days, marked removed,
 * so "what did it do" can still be answered ([markRemoved], [purgeExpired]).
 */
class PluginAudit(private val dir: File, private val maxEntries: Int = MAX_ENTRIES) {
    private fun fileFor(pluginId: String) = File(dir, "$pluginId.jsonl")

    private fun removedMarker(pluginId: String) = File(dir, "$pluginId.removed")

    fun append(pluginId: String, entry: AuditEntry, nowMs: Long = System.currentTimeMillis()) {
        synchronized(LOCK) { appendLocked(pluginId, entry, nowMs) }
    }

    private fun appendLocked(pluginId: String, entry: AuditEntry, nowMs: Long) {
        dir.mkdirs()
        val kept = readLocked(pluginId).filter { nowMs - it.atMs <= MAX_AGE_MS } + entry
        val trimmed = kept.takeLast(maxEntries)
        val tmp = File(dir, "$pluginId.jsonl.tmp")
        tmp.writeText(trimmed.joinToString("\n", postfix = "\n") { it.toJson().toString() })
        Files.move(tmp.toPath(), fileFor(pluginId).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        // A plugin installed again after a removal starts a live ring again.
        removedMarker(pluginId).delete()
    }

    /** Oldest first. */
    fun entries(pluginId: String): List<AuditEntry> = synchronized(LOCK) { readLocked(pluginId) }

    private fun readLocked(pluginId: String): List<AuditEntry> {
        val file = fileFor(pluginId)
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull { line ->
            line.takeIf { it.isNotBlank() }?.let { runCatching { AuditEntry.fromJson(JSONObject(it)) }.getOrNull() }
        }
    }

    /** When [permission] was last used through the broker, for the Permissions screen. */
    fun lastUsed(pluginId: String, permission: String): Long? =
        entries(pluginId).lastOrNull { it.permission == permission }?.atMs

    fun isRemoved(pluginId: String): Boolean = removedMarker(pluginId).isFile

    /** Uninstall keeps the ring for [REMOVED_RETENTION_MS], labelled removed. */
    fun markRemoved(pluginId: String, nowMs: Long = System.currentTimeMillis()) {
        synchronized(LOCK) {
            if (fileFor(pluginId).isFile) removedMarker(pluginId).writeText(nowMs.toString())
        }
    }

    /** Drops every ring whose plugin was removed more than 7 days ago. */
    fun purgeExpired(nowMs: Long = System.currentTimeMillis()) {
        synchronized(LOCK) {
            val markers = dir.listFiles { f -> f.name.endsWith(".removed") } ?: return
            for (marker in markers) {
                val removedAt = marker.readText().trim().toLongOrNull() ?: 0L
                if (nowMs - removedAt > REMOVED_RETENTION_MS) {
                    val id = marker.name.removeSuffix(".removed")
                    File(dir, "$id.jsonl").delete()
                    marker.delete()
                }
            }
        }
    }

    companion object {
        const val MAX_ENTRIES = 2_000
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val REMOVED_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private val LOCK = Any()

        fun forPluginsRoot(pluginsRoot: File): PluginAudit = PluginAudit(File(pluginsRoot.parentFile ?: pluginsRoot, "plugin-audit"))

        fun forContext(context: Context): PluginAudit = forPluginsRoot(PluginStore.root(context))
    }
}
