package dev.droidtop.library

import android.content.Context

/**
 * The version a person set for a folder game (docs/SPEC.md 7i, "The game
 * page", Droidtop/tracker#397 slice G): it wins over what the folder's name
 * says until it is cleared. Kept by the game's entry id, which a version bump
 * keeps ([GameFolderIds]), so a set version survives a rename. Preferences
 * reads and writes: off the main thread.
 */
object SetVersions {
    private const val PREFS = "droidtop_set_versions"

    /** Where a game's shown version comes from, for the page's More: "set by you", the store's installer, or the folder's name. */
    enum class Origin(val words: String) { SET("set by you"), STORE("from the store's installer"), FOLDER("from the folder name") }

    fun get(context: Context, entryId: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(entryId, null)?.takeIf { it.isNotBlank() }

    /** Sets [version] for [entryId]; blank or null clears it, and the folder name speaks again. */
    fun set(context: Context, entryId: String, version: String?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        val trimmed = version?.trim().orEmpty()
        if (trimmed.isEmpty()) edit.remove(entryId) else edit.putString(entryId, trimmed)
        edit.apply()
    }

    /**
     * The version a folder game shows and where it came from: the one the
     * person set, else the build a store's marker names, else the folder
     * name's ([GameTitleParser], the same parse the title is stripped by);
     * null when none says one. Pure.
     */
    fun shown(entry: LibraryEntry, set: String?): Pair<String, Origin>? {
        set?.takeIf { it.isNotBlank() }?.let { return it to Origin.SET }
        entry.pcInfo?.marker?.buildId?.takeIf { it.isNotBlank() }?.let { return it to Origin.STORE }
        val path = entry.groupingPath() ?: return null
        return GameNaming.derive(path).version.takeIf { it.isNotBlank() }?.let { it to Origin.FOLDER }
    }
}
