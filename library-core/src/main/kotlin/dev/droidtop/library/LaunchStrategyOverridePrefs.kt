package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.runtime.prefs.PrefsFile

/**
 * User's explicit choice of which [GameLaunchStrategy] runs a given
 * entry, overriding the availability model's own stated default.
 *
 * **Any PC entry**, not only an engine-detected one (docs/SPEC.md 7i): a
 * Steam or folder game is the same object in the same list, with the same
 * four runners resolved for it, so it gets the same override. Nothing in
 * the storage was ever engine-specific -- it is keyed by
 * [LibraryEntry.id], which every provider supplies -- only the resolution
 * that read it was, and [PcRunnerOptions] now runs for every entry -- same real pattern as
 * [dev.droidtop.library.consoles.PlayerOverridePrefs] for ROMs (a game can
 * have several real candidate launch paths, same idea as Daijishō's
 * `PlatformEntity.playerIdList`/`defaultPlayerId` this whole session keeps
 * coming back to). Stores [GameLaunchStrategy.name], keyed by
 * [LibraryEntry.id]. A real per-entry picker IS wired to this: the
 * Gaming shell's game detail screen reads it and writes the user's
 * choice back (see `GamepadShell`), matching ConsoleSystemsActivity's
 * PlayerPicker for ROMs.
 */
object LaunchStrategyOverridePrefs {
    private val prefs = { context: Context -> PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).keyedStrings("droidtop_launch_strategy_override_") }

    fun get(context: Context, entryId: String): String? = prefs(context).get(entryId)

    fun set(context: Context, entryId: String, strategy: GameLaunchStrategy?) {
        prefs(context).set(entryId, strategy?.name)
    }

    /**
     * "Run once with…" (docs/SPEC.md 7i, Droidtop/tracker#636): [strategy] runs [entryId]'s next launch only,
     * and the saved choice above is left as it is. Held in memory, not in the preferences, because the
     * launch it is for starts straight after it is set; a process that dies in between forgets it, which is
     * the right answer for a choice made for one run.
     */
    fun runOnce(entryId: String, strategy: GameLaunchStrategy) {
        once[entryId] = strategy.name
    }

    /** The runner a launch of [entryId] uses: a "Run once with…" choice (taken, so it applies once), else the saved one. */
    fun forLaunch(context: Context, entryId: String): String? = once.remove(entryId) ?: get(context, entryId)

    /** Whether [forLaunch]'s answer for [entryId] is a one-time choice, so a launch that cannot honour it says so. */
    fun isOnce(entryId: String): Boolean = once.containsKey(entryId)

    private val once = java.util.concurrent.ConcurrentHashMap<String, String>()
}
