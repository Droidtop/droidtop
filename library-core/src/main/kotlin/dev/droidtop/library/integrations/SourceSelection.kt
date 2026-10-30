package dev.droidtop.library.integrations

import android.content.Context

/**
 * Get games' source filter (owner, 2026-10-30, Droidtop/tracker#825: "We want a single get games section, and a filter
 * to choose where it searches"): the sources the person turned off, kept across launches. What is kept is the ones
 * turned off, so a source installed later is searched until the person turns it off too. [UnifiedSearch.search] leaves
 * them out and lists them in [UnifiedState.off] for the filter to draw.
 */
object SourceSelection {
    private const val PREFS = "droidtop_get_games_sources"
    private const val KEY_OFF = "off"

    /** The ids of the sources turned off. A preferences read. */
    fun off(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_OFF, null)?.toSet().orEmpty()

    /** Turns [sourceId] on or off for every search from now on. */
    fun set(context: Context, sourceId: String, on: Boolean) {
        val next = toggled(off(context), sourceId, on)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_OFF, next).apply()
    }

    /** [off] with [sourceId] turned on or off. Pure. */
    internal fun toggled(off: Set<String>, sourceId: String, on: Boolean): Set<String> = if (on) off - sourceId else off + sourceId
}
