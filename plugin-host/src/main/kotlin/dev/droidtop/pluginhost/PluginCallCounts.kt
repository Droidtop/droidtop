package dev.droidtop.pluginhost

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject

/**
 * A plugin's everyday calls, counted per day (docs/plugin-api.md 4.6, Droidtop/tracker#61): the calls the activity
 * ring does not list one by one (normal-tier permissions, and ops that need none), as a count per day and per
 * permission, or per `api op` when there is none. Kept [DAYS] days beside the ring, in `<id>.counts.json`.
 *
 * Everyday calls are frequent, so counts gather in memory and are written at most every [FLUSH_MS], when the day
 * changes, and before they are read. A process killed between writes loses those few seconds of counts, which is
 * what a count (not a log) can afford.
 */
class PluginCallCounts(private val dir: File) {
    private fun fileFor(pluginId: String) = File(dir, "$pluginId.counts.json")

    /** Counts one call of [pluginId] under [key] on [nowMs]'s day. */
    fun count(pluginId: String, key: String, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
        val day = dayOf(nowMs, zone)
        synchronized(LOCK) {
            val held = pending.getOrPut(id(pluginId)) { Pending(day, nowMs) }
            if (held.day != day) {
                flushLocked(pluginId, nowMs)
                pending[id(pluginId)] = Pending(day, nowMs)
            }
            val now = pending.getValue(id(pluginId))
            now.counts[key] = (now.counts[key] ?: 0) + 1
            if (nowMs - now.sinceMs >= FLUSH_MS) flushLocked(pluginId, nowMs)
        }
    }

    /** Days newest first, each with its counts by key, highest first. */
    fun days(pluginId: String, nowMs: Long = System.currentTimeMillis()): List<Pair<String, List<Pair<String, Int>>>> =
        synchronized(LOCK) {
            flushLocked(pluginId, nowMs)
            readLocked(pluginId).entries.sortedByDescending { it.key }
                .map { (day, counts) -> day to counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value } }
        }

    /**
     * The newest day (`YYYY-MM-DD`) on which [pluginId] made a call counted under [key], or null when none in the last
     * [DAYS] days: an everyday permission's "last used" on the Permissions page, which the activity ring never lists
     * (Droidtop/tracker#60).
     */
    fun lastDay(pluginId: String, key: String, nowMs: Long = System.currentTimeMillis()): String? =
        days(pluginId, nowMs).firstOrNull { (_, counts) -> counts.any { it.first == key } }?.first

    /** Writes what is held in memory for [pluginId], merged into the file, and drops days older than [DAYS]. */
    fun flush(pluginId: String, nowMs: Long = System.currentTimeMillis()) {
        synchronized(LOCK) { flushLocked(pluginId, nowMs) }
    }

    private fun flushLocked(pluginId: String, nowMs: Long) {
        val held = pending.remove(id(pluginId)) ?: return
        if (held.counts.isEmpty()) return
        val all = readLocked(pluginId).mapValues { it.value.toMutableMap() }.toMutableMap()
        val day = all.getOrPut(held.day) { mutableMapOf() }
        for ((key, n) in held.counts) day[key] = (day[key] ?: 0) + n
        val kept = all.entries.sortedByDescending { it.key }.take(DAYS)
        val json = JSONObject()
        for ((d, counts) in kept) json.put(d, JSONObject(counts as Map<*, *>))
        dir.mkdirs()
        val tmp = File(dir, "$pluginId.counts.json.tmp")
        tmp.writeText(json.toString())
        Files.move(tmp.toPath(), fileFor(pluginId).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun readLocked(pluginId: String): Map<String, Map<String, Int>> {
        val file = fileFor(pluginId)
        if (!file.isFile) return emptyMap()
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyMap()
        return json.keys().asSequence().associateWith { d ->
            val day = json.optJSONObject(d) ?: return@associateWith emptyMap()
            day.keys().asSequence().associateWith { day.optInt(it) }
        }
    }

    /** Deletes [pluginId]'s counts, with the ring's own expiry ([PluginAudit.purgeExpired]). */
    fun delete(pluginId: String) {
        synchronized(LOCK) {
            pending.remove(id(pluginId))
            fileFor(pluginId).delete()
        }
    }

    private fun id(pluginId: String) = dir.path + "/" + pluginId

    private class Pending(val day: String, val sinceMs: Long) {
        val counts = mutableMapOf<String, Int>()
    }

    companion object {
        const val DAYS = 30
        const val FLUSH_MS = 30_000L
        private val LOCK = Any()

        // Shared by every instance over the same folder: the broker and the Activity screen each make their own.
        private val pending = HashMap<String, Pending>()

        /** The local date of [nowMs], as `YYYY-MM-DD`. Pure. */
        fun dayOf(nowMs: Long, zone: ZoneId): String = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()
    }
}
