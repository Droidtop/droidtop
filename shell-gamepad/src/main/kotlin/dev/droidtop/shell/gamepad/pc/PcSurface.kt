package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.displayName

/** ES-DE's own system id for the PC category -- the card this group opens from. */
internal const val PC_SYSTEM_ID = "pc"

/**
 * :app's "Stores and folders" settings screen, by [dev.droidtop.library.settings.SettingsScreenRegistry]
 * id because this module cannot depend on :app -- the same way
 * `GamingSettingsCatalog` names the console-systems and Windows-games
 * screens it opens. Reached from the gamelist's own Select menu
 * (`GamelistOptionsMenu`'s "Stores and folders" row) now that the PC
 * group's list is the same themed gamelist every other system uses
 * (docs/SPEC.md 7i, revised 2026-09-26) rather than a screen of its own.
 */
internal const val PC_STORES_SCREEN_ID = "pc_stores"

/** Where this game came from. A store row says so itself; anything else is a folder droidtop found. */
internal fun LibraryEntry.sourceLabel(): String = pcInfo?.source ?: "Folder"

/** The detected engine, or null for a PC entry that has none — a Steam game is still a game. */
internal fun LibraryEntry.engineLabel(): String? =
    if (kind == LibraryEntryKind.WINE_PROFILE) null else kind.displayName()
