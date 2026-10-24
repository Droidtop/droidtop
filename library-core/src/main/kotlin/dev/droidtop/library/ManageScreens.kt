package dev.droidtop.library

/**
 * The Wine screens of a game menu's Manage section (docs/SPEC.md 7i, "Manage"; Droidtop/tracker#636), offered
 * for every kind of game: "Run a program in a Wine prefix" ([RUN_IN_PREFIX]: a program, then the prefix it
 * runs attached to) and the Prefix tools of the prefix the game starts in ([PREFIX_TOOLS]). Registered by
 * `:app` (RunInPrefixCatalog, PrefixToolsCatalog) and opened by id, deep-linked with the argument the Linux
 * tools screen takes ([LinuxToolsScreen.argument]): the game, its title and its folder.
 */
object ManageScreens {
    const val RUN_IN_PREFIX = "game_run_in_prefix"
    const val PREFIX_TOOLS = "game_prefix_tools"
}
