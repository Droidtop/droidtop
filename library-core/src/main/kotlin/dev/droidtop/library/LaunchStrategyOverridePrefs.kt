package dev.droidtop.library

import android.content.Context

/**
 * User's explicit choice of which [GameLaunchStrategy] runs a given
 * engine-game entry, overriding [EngineGameProvider.launch]'s own
 * priority-order default -- same real pattern as
 * [dev.droidtop.library.consoles.PlayerOverridePrefs] for ROMs (a game can
 * have several real candidate launch paths, same idea as Daijishō's
 * `PlatformEntity.playerIdList`/`defaultPlayerId` this whole session keeps
 * coming back to). Stores [GameLaunchStrategy.name], keyed by
 * [LibraryEntry.id]. No settings UI wired to this yet -- see
 * [EngineGameProvider.launch]'s own doc comment for why a real per-entry
 * picker (matching ConsoleSystemsActivity's PlayerPicker) is the natural
 * next step, not built in this pass.
 */
object LaunchStrategyOverridePrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_PREFIX = "droidtop_launch_strategy_override_"

    fun get(context: Context, entryId: String): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PREFIX + entryId, null)

    fun set(context: Context, entryId: String, strategy: GameLaunchStrategy?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (strategy == null) {
            prefs.edit().remove(KEY_PREFIX + entryId).apply()
        } else {
            prefs.edit().putString(KEY_PREFIX + entryId, strategy.name).apply()
        }
    }
}
