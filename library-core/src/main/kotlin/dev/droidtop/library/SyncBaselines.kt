package dev.droidtop.library

import android.content.Context

/**
 * What "Recently added" means for a PC game (docs/SPEC.md 7g, "Stores"):
 * added since its source's first sync or scan, so signing in to a store, or
 * adding a game folder, puts nothing on the shelf, and a game a later sync or
 * Rescan brings does. One record per source, keyed by [PcSource.id], for
 * stores and game folders alike.
 *
 * The first time a source's rows are seen, the newest first-seen time among
 * them is its baseline: everything already there was already the person's.
 * A first folder scan publishes a part at a time over minutes (a rescan of a
 * card can take five), so rows first seen within [SETTLE_MS] of the baseline
 * being set are part of that first scan and move the baseline with them.
 */
object SyncBaselines {
    /** How long after a source is first seen its rows still count as its first sync or scan. */
    const val SETTLE_MS = 15L * 60 * 1000

    /** The newest first-seen time a source's first sync or scan reached, and when the baseline was set. */
    data class Baseline(val seenUpTo: Long, val setAt: Long)

    /**
     * [baselines] after seeing [rows] (a source id and a first-seen time each)
     * at [now]. Pure: a source with no baseline takes its newest row; one
     * still settling takes the rows that arrived within [SETTLE_MS].
     */
    fun update(baselines: Map<String, Baseline>, rows: List<Pair<String, Long>>, now: Long): Map<String, Baseline> {
        val bySource = rows.filter { it.second > 0L }.groupBy({ it.first }, { it.second })
        if (bySource.isEmpty()) return baselines
        val next = HashMap(baselines)
        for ((source, seen) in bySource) {
            val known = next[source]
            if (known == null) {
                next[source] = Baseline(seen.max(), now)
            } else {
                val settling = seen.filter { it <= known.setAt + SETTLE_MS }.maxOrNull()
                if (settling != null && settling > known.seenUpTo) next[source] = known.copy(seenUpTo = settling)
            }
        }
        return next
    }

    /**
     * Whether a game first seen at [firstSeen] from [sourceId] was added since
     * its source's baseline. A source with no baseline yet has added nothing.
     */
    fun isRecentlyAdded(sourceId: String, firstSeen: Long, baselines: Map<String, Baseline>): Boolean =
        firstSeen > 0L && baselines[sourceId]?.let { firstSeen > it.seenUpTo } == true

    private const val PREFS = "droidtop_pc_sync_baselines"

    /** The kept baselines. A preferences read: off the main thread. */
    fun load(context: Context): Map<String, Baseline> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
            val parts = (value as? String)?.split(':') ?: return@mapNotNull null
            val seen = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val setAt = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            key to Baseline(seen, setAt)
        }.toMap()

    fun save(context: Context, baselines: Map<String, Baseline>) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
        baselines.forEach { (source, baseline) -> edit.putString(source, "${baseline.seenUpTo}:${baseline.setAt}") }
        edit.apply()
    }
}
