package dev.droidtop.library.settings

import android.content.Context
import java.io.File
import java.time.ZoneId
import org.json.JSONObject

/** One game's play: its name and system as they were when it was played, all its time, and its last two weeks by day. */
data class GamePlay(val title: String, val system: String?, val totalSeconds: Long, val days: Map<Long, Long>)

/** What the "Your play" page says (docs/SPEC.md 7f, "Your play", Droidtop/tracker#597). */
data class PlaySummary(
    val weekSeconds: Long,
    val allTimeSeconds: Long,
    /** The five games played most, all time, most first. */
    val top: List<GamePlay>,
    /** Time per system, most first; a game with no system counts under "PC and other games". */
    val bySystem: List<Pair<String, Long>>,
)

/**
 * Play time per game, in every mode (docs/SPEC.md 7f, "Your play", Droidtop/tracker#597): Kid mode's ledger
 * ([PlayTimePrefs]) counts all play together, and only in Kid, for its limits; this is the person's own record,
 * written by the same watcher (`PlayTimeLimiter` in `:app`) for each session droidtop started. Pure parts here, the
 * file in [PlayLogStore].
 */
object PlayLog {
    const val NO_SYSTEM = "PC and other games"
    private const val TOP = 5

    /** [log] with [gameId]'s play from [startMs] to [endMs] added, under the name and system it has now. Pure. */
    fun add(log: Map<String, GamePlay>, gameId: String, title: String, system: String?, startMs: Long, endMs: Long, zone: ZoneId): Map<String, GamePlay> {
        if (endMs <= startMs) return log
        val was = log[gameId]
        val play = GamePlay(
            title = title.ifBlank { was?.title ?: gameId },
            system = system ?: was?.system,
            totalSeconds = (was?.totalSeconds ?: 0L) + (endMs - startMs) / 1000L,
            days = PlayTime.add(was?.days.orEmpty(), startMs, endMs, zone),
        )
        return log + (gameId to play)
    }

    /** The page's numbers at [nowMs]. Pure. */
    fun summary(log: Map<String, GamePlay>, nowMs: Long, zone: ZoneId): PlaySummary {
        val played = log.values.filter { it.totalSeconds > 0 }
        return PlaySummary(
            weekSeconds = played.sumOf { PlayTime.usedThisWeek(it.days, nowMs, zone) },
            allTimeSeconds = played.sumOf { it.totalSeconds },
            top = played.sortedByDescending { it.totalSeconds }.take(TOP),
            bySystem = played.groupBy { it.system?.takeIf { s -> s.isNotBlank() } ?: NO_SYSTEM }
                .map { (system, games) -> system to games.sumOf { it.totalSeconds } }
                .sortedByDescending { it.second },
        )
    }

    fun parse(raw: String?): Map<String, GamePlay> {
        val obj = runCatching { JSONObject(raw ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
        return obj.keys().asSequence().mapNotNull { id ->
            val game = obj.optJSONObject(id) ?: return@mapNotNull null
            id to GamePlay(
                title = game.optString("title").ifBlank { id },
                system = game.optString("system").takeIf { it.isNotBlank() },
                totalSeconds = game.optLong("total").coerceAtLeast(0L),
                days = PlayTime.parse(game.optString("days")),
            )
        }.toMap()
    }

    fun format(log: Map<String, GamePlay>): String = JSONObject().apply {
        for ((id, game) in log) {
            put(
                id,
                JSONObject().put("title", game.title).put("system", game.system ?: "")
                    .put("total", game.totalSeconds).put("days", PlayTime.format(game.days)),
            )
        }
    }.toString()
}

/** The play log as a file of droidtop's own. Small reads and writes; call them off the main thread. */
object PlayLogStore {
    private const val FILE = "play-log.json"

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun log(context: Context): Map<String, GamePlay> = PlayLog.parse(runCatching { file(context).readText() }.getOrNull())

    @Synchronized
    fun record(context: Context, gameId: String, title: String, system: String?, startMs: Long, endMs: Long, zone: ZoneId = ZoneId.systemDefault()) {
        val updated = PlayLog.add(log(context), gameId, title, system, startMs, endMs, zone)
        runCatching {
            val target = file(context)
            val temp = File(target.parentFile, "$FILE.tmp")
            temp.writeText(PlayLog.format(updated))
            if (!temp.renameTo(target)) target.writeText(PlayLog.format(updated))
        }
    }
}

/** The "Your play" page (docs/SPEC.md 7f, Droidtop/tracker#597): this week, all time, the most played, per system. */
object PlaySummaryScreen {
    const val ID_LINK = "pref_your_play"

    fun screen() = CatalogScreen(
        id = "your_play",
        title = "Your play",
        subtitle = "Time played in games droidtop started",
        groups = { context -> groups(context) },
    )

    fun link() = NestedScreenItem(
        id = ID_LINK,
        title = "Your play",
        subtitle = "This week, all time, and your most played games",
        inline = screen(),
        valueLabel = { context ->
            val summary = PlayLog.summary(PlayLogStore.log(context), System.currentTimeMillis(), ZoneId.systemDefault())
            if (summary.allTimeSeconds > 0) "${PlayTime.duration(summary.weekSeconds)} this week" else "Nothing played yet"
        },
    )

    private fun groups(context: Context): List<CatalogGroup> {
        val summary = PlayLog.summary(PlayLogStore.log(context), System.currentTimeMillis(), ZoneId.systemDefault())
        if (summary.allTimeSeconds <= 0) {
            return listOf(
                CatalogGroup(
                    id = "your_play_empty",
                    title = null,
                    items = listOf(TextBlockItem(id = "your_play_none", text = "Nothing played yet. Time counts from the next game droidtop starts.")),
                ),
            )
        }
        return listOf(
            CatalogGroup(
                id = "your_play_totals",
                title = null,
                items = listOf(
                    ActionItem(id = "your_play_week", title = "This week", subtitle = "From Monday", value = PlayTime.duration(summary.weekSeconds)),
                    ActionItem(id = "your_play_all", title = "All time", value = PlayTime.duration(summary.allTimeSeconds)),
                ),
            ),
            CatalogGroup(
                id = "your_play_top",
                title = "Most played",
                items = summary.top.mapIndexed { i, game ->
                    ActionItem(id = "your_play_top_$i", title = game.title, subtitle = game.system, value = PlayTime.duration(game.totalSeconds))
                },
            ),
            CatalogGroup(
                id = "your_play_systems",
                title = "By system",
                items = summary.bySystem.mapIndexed { i, (system, seconds) ->
                    ActionItem(id = "your_play_system_$i", title = system, value = PlayTime.duration(seconds))
                },
            ),
        )
    }
}
