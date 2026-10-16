package dev.droidtop.runtime

/**
 * What Standard's label setting means on the home screen and in the drawer (docs/SPEC.md "Standard",
 * Droidtop/tracker#564). The setting is shell-default's `LabelVisibility`; its values are passed here by name,
 * so the rule is tested where CI runs tests.
 *
 * "Home only" once made the home screen behave like Auto, which drops labels where the layout is short of room
 * (the vertical bar layout of a landscape handheld), so on the console it showed none (rig, 2026-10-16, build
 * 0.2.0-dev.1994; Always worked). Home only now shows them on the home screen as Always does.
 */
object LauncherLabels {
    enum class Home {
        /** No labels on the home screen. */
        HIDDEN,
        /** Labels where the layout has room for them (stock Launcher3). */
        IF_ROOM,
        /** Labels always, even where they may clip. */
        SHOWN,
    }

    /** The home screen's labels for the setting named [setting]. An unknown name is stock behaviour. */
    fun home(setting: String): Home = when (setting) {
        "NEVER_FULL", "NEVER" -> Home.HIDDEN
        "ALWAYS", "NEVER_DRAWER" -> Home.SHOWN
        else -> Home.IF_ROOM
    }

    /** Whether the drawer shows labels for the setting named [setting]. */
    fun drawer(setting: String): Boolean = setting != "NEVER_FULL" && setting != "NEVER_DRAWER"
}
