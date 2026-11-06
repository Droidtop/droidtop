package dev.droidtop.library.settings

/**
 * The app drawer's Suggested row (Droidtop/tracker#706): the apps a person opens at this time of day, from droidtop's
 * own launch history (no Usage Access grant), with what was opened most recently as the tie-breaker. Pure.
 */
object SuggestedApps {
    /** The key one hour's launches of [app] are counted under. */
    fun hourKey(app: String, hour: Int): String = "$app@${hour.mod(24)}"

    /**
     * [recent] (most recent first) ordered for [hour]: each app scores three per launch in this hour and one per launch
     * in the hour either side ([hourCounts], keyed by [hourKey]), plus a little for being recent, so a fresh install's
     * row is its Recent row. Apps with a score keep their place in [recent] on a tie.
     */
    fun rank(recent: List<String>, hourCounts: Map<String, Int>, hour: Int): List<String> {
        fun launches(app: String, h: Int) = hourCounts[hourKey(app, h)] ?: 0
        val score = recent.withIndex().associate { (index, app) ->
            val habit = 3 * launches(app, hour) + launches(app, hour - 1) + launches(app, hour + 1)
            // Recency is worth less than one launch at this hour, so habit leads and recency only breaks ties.
            app to habit * 100 + (recent.size - index)
        }
        return recent.distinct().sortedByDescending { score[it] ?: 0 }
    }

    /** [counts] with one more launch of [app] at [hour], keeping at most [max] keys (the least counted go first). */
    fun recordHour(counts: Map<String, Int>, app: String, hour: Int, max: Int = 600): Map<String, Int> {
        val key = hourKey(app, hour)
        val next = counts + (key to (counts[key] ?: 0) + 1)
        return if (next.size <= max) next else next.entries.sortedByDescending { it.value }.take(max).associate { it.toPair() }
    }
}
