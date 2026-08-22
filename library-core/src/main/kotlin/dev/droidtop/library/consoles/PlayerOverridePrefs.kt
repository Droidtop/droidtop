package dev.droidtop.library.consoles

import android.content.Context

/**
 * User's explicit choice of which [Player] handles a given console system,
 * overriding [ConsoleRomProvider.availablePlayers]'s own installed-first
 * default order -- same real Daijishō pattern already used by
 * [SystemOverridePrefs] (a system can have several real candidate players,
 * same as Daijishō's own `PlatformEntity.playerIdList`/`defaultPlayerId`),
 * same shared prefs file every other droidtop setting already uses.
 */
object PlayerOverridePrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_PREFIX = "droidtop_player_override_"

    fun get(context: Context, systemId: String): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PREFIX + systemId, null)

    fun set(context: Context, systemId: String, playerId: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (playerId == null) {
            prefs.edit().remove(KEY_PREFIX + systemId).apply()
        } else {
            prefs.edit().putString(KEY_PREFIX + systemId, playerId).apply()
        }
    }
}
