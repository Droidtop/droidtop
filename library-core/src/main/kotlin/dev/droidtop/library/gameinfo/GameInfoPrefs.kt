package dev.droidtop.library.gameinfo

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.runtime.prefs.PrefsFile

/**
 * Whether a game's page may ask HowLongToBeat and the emulators' public compatibility lists about the game
 * (docs/SPEC.md 7h, "Game info"). On by default: it sends the game's title (or, for a compatibility list, nothing
 * but the download of the list) and only when a game's page opens. Off, those rows are not drawn and no request is
 * made. RetroAchievements has its own switch: signing in.
 */
object GameInfoPrefs {
    const val KEY = "droidtop_game_info_lookups"

    fun lookupsOn(context: Context): Boolean = PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).getBoolean(KEY, true)

    fun setLookups(context: Context, on: Boolean) {
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).putBoolean(KEY, on)
    }
}
