package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.stores.StoreAchievement
import dev.droidtop.library.stores.StoreAchievements
import dev.droidtop.pluginhost.PluginCrashPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * Achievements from plugins (docs/plugin-api.md 3 A8, `achievements.provider@1`; Droidtop/tracker#143 restore plan d):
 * the same Achievements row and page RetroAchievements and the stores fill, for a game neither covers. A provider
 * answers `set {entry}` with the game's achievements and `progress {entry}` with the ones the person earned; the first
 * provider with any is shown. A provider learns the game's title, system and store id, never a folder on the device.
 */
object PluginAchievements {
    const val POINT = "achievements.provider"
    private const val BUDGET_MS = 10_000L
    private const val MAX = 1000

    /** One provider's answer for [entry]: its name and the achievements, or null when none has any. Network: off the main thread. */
    suspend fun read(context: Context, entry: LibraryEntry): Pair<String, StoreAchievements>? {
        val providers = providersOf(context.applicationContext, POINT)
        if (providers.isEmpty()) return null
        val args = entryArgs(entry)
        for ((record, provided) in providers) {
            val label = provided.label ?: record.manifest.label
            val set = call(context, record, "set", args) ?: continue
            val progress = call(context, record, "progress", args)
            val list = parse(set, progress)
            if (list.total > 0) return label to list
        }
        return null
    }

    /** What a provider is told of a game: its title, system and, for a store's game, the store's id. Pure. */
    fun entryArgs(entry: LibraryEntry): JSONObject {
        val game = JSONObject()
            .put("title", entry.gameName ?: GameNaming.displayName(entry.title))
            .put("system", entry.systemId)
        entry.pcInfo?.storeId?.takeIf { dev.droidtop.library.PcSource.storeIdOf(it) != null }?.let { key ->
            game.put("store", JSONObject().put("id", key.substringBefore(':')).put("gameId", key.substringAfter(':')))
        }
        return JSONObject().put("entry", game)
    }

    private suspend fun call(context: Context, record: dev.droidtop.pluginhost.PluginRecord, op: String, args: JSONObject): JSONObject? {
        val policy = PluginCrashPolicy(context.applicationContext)
        return try {
            val reply = policy.handle(record, newCall(POINT, op, "game.page", JSONObject(args.toString()), BUDGET_MS), timeoutMs = BUDGET_MS, crashOnTimeout = false, userInitiated = false)
            reply.takeIf { it.ok }?.data
        } finally {
            policy.shutdown()
        }
    }

    /**
     * A provider's `set` (`{achievements: [{id, title, desc?, points?, badgeUrl?, hidden?}]}`) and `progress`
     * (`{unlocked: [{id, unlockedAt}]}`, `unlockedAt` in epoch milliseconds or an ISO date) as droidtop shows them.
     * An entry without an id or a title is left out; progress for an id the set does not have is ignored. Pure.
     */
    fun parse(set: JSONObject, progress: JSONObject?): StoreAchievements {
        val earned = HashMap<String, Long>()
        progress?.optJSONArray("unlocked")?.forEachObject { row ->
            val id = row.optString("id").takeIf { it.isNotBlank() } ?: return@forEachObject
            unlockedAt(row.opt("unlockedAt"))?.let { earned[id] = it }
        }
        val list = mutableListOf<StoreAchievement>()
        set.optJSONArray("achievements")?.forEachObject { row ->
            if (list.size >= MAX) return@forEachObject
            val id = row.optString("id").takeIf { it.isNotBlank() } ?: return@forEachObject
            val title = row.optString("title").trim().takeIf { it.isNotEmpty() } ?: return@forEachObject
            list += StoreAchievement(
                id = id,
                title = title.take(120),
                description = row.optString("desc").trim().take(300),
                earnedAtMs = earned[id],
                hidden = row.optBoolean("hidden", false),
                iconUrl = row.optString("badgeUrl").takeIf { it.startsWith("https://") },
            )
        }
        return StoreAchievements(list)
    }

    private fun unlockedAt(raw: Any?): Long? = when (raw) {
        is Number -> raw.toLong().takeIf { it > 0 }
        is String -> runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDate.parse(raw.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
        else -> null
    }

    private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(block)
    }
}
