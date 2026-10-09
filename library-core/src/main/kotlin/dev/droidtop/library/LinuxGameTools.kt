package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * Whether a native Linux game keeps its saves and settings in a home of its own (docs/SPEC.md 7c, "Prefix
 * tools for Linux games"). Off unless the person turns it on: a game has always run with the desktop
 * container's home, its saves are there, and droidtop does not change where a game saves.
 */
object LinuxGameOptionsPrefs {
    private const val KEY_PREFIX = "droidtop_linux_game_own_home_"

    fun ownHome(context: Context, entryId: String): Boolean =
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE).getBoolean(KEY_PREFIX + entryId, false)

    fun setOwnHome(context: Context, entryId: String, on: Boolean) {
        val prefs = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE).edit()
        if (on) prefs.putBoolean(KEY_PREFIX + entryId, true) else prefs.remove(KEY_PREFIX + entryId)
        prefs.apply()
    }
}

/**
 * The settings screen a Linux game's "Linux tools" row opens (docs/SPEC.md 7c): run a program from the
 * game's folder, stop its processes, and keep or reset its own saves and settings. Registered by `:app`
 * (LinuxGameToolsCatalog) and opened by id from the game's page, deep-linked with [argument].
 */
object LinuxToolsScreen {
    const val ID = "linux_game_tools"
    private const val SEPARATOR = "\n"

    /** The game the screen is for: its library id, its title and its folder. */
    data class Target(val entryId: String, val title: String, val gameRoot: String)

    fun argument(entryId: String, title: String, gameRoot: String): String =
        listOf(entryId, title, gameRoot).joinToString(SEPARATOR)

    /** [argument] read back. */
    fun parse(argument: String): Target {
        val parts = argument.split(SEPARATOR)
        return Target(parts[0], parts.getOrNull(1) ?: parts[0], parts.getOrNull(2).orEmpty())
    }
}
