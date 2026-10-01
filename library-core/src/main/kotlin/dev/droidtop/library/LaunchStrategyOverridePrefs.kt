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
}
