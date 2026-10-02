package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * Which parts of a multi-part game the person has finished (docs/SPEC.md 7n).
 *
 * `book1`, `book2` and `book3` are one game played in order, and nothing on
 * disk says how far a person got, so the person says: "Finished with this
 * part" on the game's menu. [LibraryGameGroup.continueCopy] then starts the
 * first part that is not in this set. Keyed by entry id like favourites and
 * the person's own titles, so a rescan keeps it; a small preferences set,
 * because it holds a handful of ids and is read once per fold.
 */
object PartProgress {
    private const val KEY_FINISHED = "droidtop_finished_parts"

    private fun prefs(context: Context) = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)

    /** The ids marked finished. Disk on first use: never on the main thread. */
    fun finished(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_FINISHED, null)?.toSet() ?: emptySet()

    fun setFinished(context: Context, id: String, finished: Boolean) {
        // Copied before writing: the set the preferences hand back is the
        // one they keep, and mutating it in place can lose the edit.
        val all = prefs(context).getStringSet(KEY_FINISHED, null)?.toHashSet() ?: hashSetOf()
        if (finished) all.add(id) else all.remove(id)
        prefs(context).edit().putStringSet(KEY_FINISHED, all).apply()
    }
}
