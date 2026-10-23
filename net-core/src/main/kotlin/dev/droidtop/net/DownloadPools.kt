package dev.droidtop.net

/**
 * What a download is for (docs/SPEC.md 12a, "One download engine", Droidtop/tracker#577). The owner: "it would behoove
 * us to have categories and stuff, so things are segmented", and "The scrapes get a threadset, game downloads get a
 * threadset (though one game at a time), etc. Downloads are treated as separate sets of resources." Each category is
 * a pool of its own: how many of its downloads run at once is its own, and a full pool queues only its own downloads.
 */
enum class DownloadCategory(val key: String, val label: String, val noun: String) {
    GAMES("games", "Games", "game download"),
    UPDATES("updates", "Updates", "update"),
    PLUGINS("plugins", "Plugins", "plugin download"),
    SYSTEM("system", "System", "system download"),
    ART("art", "Art & info", "art and info download");

    companion object {
        fun fromKey(key: String?): DownloadCategory? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The pools: how many downloads of each [DownloadCategory] run at once, and which ones wait. The owner, on the limits:
 * "Configurable, intelligent". So each limit is the person's when they set one (Download rules, "How many at once"),
 * and otherwise droidtop's own from what it can see ([automaticLimit]). Pure, unit tested; [DownloadGate] in
 * :plugin-host applies it with the download policy.
 */
object DownloadPools {
    /** The job argument naming a download's category ([DownloadCategory.key]). */
    const val ARG_CATEGORY = "category"

    /** The highest limit the person can choose. */
    const val MAX_LIMIT = 8

    private const val QUEUED = "Queued: "

    /**
     * droidtop's own limit for [category] now. Games and updates run one at a time (the owner: "one game at a time");
     * the others use what the connection allows, and give way while a game is running or battery saver is on.
     */
    fun automaticLimit(category: DownloadCategory, conditions: Conditions): Int {
        val base = when (category) {
            DownloadCategory.GAMES, DownloadCategory.UPDATES -> 1
            DownloadCategory.PLUGINS, DownloadCategory.SYSTEM -> 2
            DownloadCategory.ART -> if (conditions.network == NetworkClass.METERED) 2 else 6
        }
        return if (conditions.gameRunning || conditions.batterySaver) 1 else base
    }

    /** The limit in force for [category]: the person's choice when they made one, else [automaticLimit]. */
    fun limit(settings: DownloadSettings, category: DownloadCategory, conditions: Conditions): Int =
        settings.poolLimits[category.key]?.takeIf { it in 1..MAX_LIMIT } ?: automaticLimit(category, conditions)

    /** The line a queued download shows. */
    fun queuedLine(category: DownloadCategory, limit: Int): String =
        QUEUED + if (limit == 1) "one ${category.noun} at a time" else "$limit ${category.noun}s at a time"

    /** Whether [hold] is a pool's queue, not the download policy's. */
    fun isQueued(hold: String?): Boolean = hold?.startsWith(QUEUED) == true

    /**
     * One download the pools consider: one the policy would let run now. [running] when it is transferring, else it
     * waits (queued, or held by a rule that has just lifted). [pinned] when the person pressed Download now on it: it
     * takes a slot first, is never queued, and starts when it waits.
     */
    data class Entry(val id: String, val category: DownloadCategory, val startedAtMs: Long, val running: Boolean, val pinned: Boolean = false)

    sealed interface Move {
        val id: String

        data class Queue(override val id: String, val line: String) : Move

        data class Start(override val id: String) : Move
    }

    /**
     * Which downloads start and which wait, per category: the person's pinned ones take their slots first, then the
     * others in the order they were started, until the limit; the rest wait with the pool's line. A download already
     * where it should be is not moved. Pure.
     */
    fun plan(entries: List<Entry>, limit: (DownloadCategory) -> Int, waitingLine: (String) -> String? = { null }): List<Move> =
        entries.groupBy { it.category }.flatMap { (category, inPool) ->
            val max = limit(category)
            var slots = max - inPool.count { it.pinned }
            val pinnedStarts = inPool.filter { it.pinned && !it.running }.map<Entry, Move> { Move.Start(it.id) }
            pinnedStarts + inPool.filter { !it.pinned }.sortedBy { it.startedAtMs }.mapNotNull { entry ->
                if (slots > 0) {
                    slots--
                    if (entry.running) null else Move.Start(entry.id)
                } else {
                    val line = queuedLine(category, max)
                    if (!entry.running && waitingLine(entry.id) == line) null else Move.Queue(entry.id, line)
                }
            }
        }
}
