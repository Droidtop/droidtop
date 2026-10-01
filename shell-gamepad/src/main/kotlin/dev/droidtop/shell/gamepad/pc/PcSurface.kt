package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.displayName
import dev.droidtop.library.scraper.isPcOrEngineGame

/**
 * ES-DE's own system id for the PC category. The PC Games tab owns it
 * outright (docs/SPEC.md 7i): a ROM a person dropped in `roms/pc` is a PC
 * game, never a console system's card, and the `pc` folder is where the
 * tab's scraped media and gamelist.xml live.
 */
internal const val PC_SYSTEM_ID = "pc"

/**
 * :app's "PC setup" settings screen (game folders, the Windows system
 * files, Downloads), by [dev.droidtop.library.settings.SettingsScreenRegistry]
 * id because this module cannot depend on :app -- the same way
 * `GamingSettingsCatalog` names the console-systems and Windows-games
 * screens it opens. The PC Games tab opens it in place from its options
 * menu, and shows it instead of an empty library (docs/SPEC.md 7i).
 */
internal const val PC_STORES_SCREEN_ID = "pc_stores"

/**
 * Which tab a game is on (docs/SPEC.md 7i, 2026-10-01): PC Games holds
 * every game that is not a console system's ROM -- a detected engine
 * game, a store or Wine title, and whatever sits in a `pc` folder --
 * and Retro Games holds the rest. One rule, read by both tabs, so no game
 * can be on both or on neither.
 */
internal val LibraryEntry.onPcGamesTab: Boolean
    get() = isPcOrEngineGame || systemId == null || systemId == PC_SYSTEM_ID

/** Where this game came from. A store row says so itself; anything else is a folder droidtop found. */
internal fun LibraryEntry.sourceLabel(): String = pcInfo?.source ?: "Folder"

/** The detected engine, or null for a PC entry that has none -- a Steam game is still a game. */
internal fun LibraryEntry.engineLabel(): String? =
    if (kind == LibraryEntryKind.WINE_PROFILE) null else kind.displayName()

/**
 * Whether this copy is on the device: a store row says so; a folder the
 * walk still finds is installed by definition, and one it no longer finds
 * ([LibraryEntry.missing]) is not. The Installed shelf and the Installed
 * view read this one answer (docs/SPEC.md 7i).
 */
internal val LibraryEntry.isInstalled: Boolean
    get() = pcInfo?.installed ?: !missing
