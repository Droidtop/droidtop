package dev.droidtop.library.consoles

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.runtime.prefs.PrefsFile

/**
 * User's explicit choice of which [Player] handles a given console system,
 * overriding [ConsoleRomProvider.availablePlayers]'s own installed-first
 * default order -- same real Daijishō pattern already used by
 * [SystemOverridePrefs] (a system can have several real candidate players,
 * same as Daijishō's own `PlatformEntity.playerIdList`/`defaultPlayerId`),
 * same shared prefs file every other droidtop setting already uses.
 */
object PlayerOverridePrefs {
    private val prefs = { context: Context -> PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).keyedStrings("droidtop_player_override_") }

    fun get(context: Context, systemId: String): String? = prefs(context).get(systemId)

    fun set(context: Context, systemId: String, playerId: String?) = prefs(context).set(systemId, playerId)
}
