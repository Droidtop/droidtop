package dev.droidtop.runtime

/**
 * Which of droidtop's own components Standard's launcher shows while it hides droidtop's package (docs/SPEC.md
 * 2c, "One droidtop icon"): the one icon, and every widget droidtop provides (Droidtop/tracker#563).
 *
 * Widgets are recognised by what they are (a receiver with the app widget provider metadata), not by a list of
 * classes. The list it replaces named two widgets, so the seven added later were filtered out of the launcher's
 * widget picker (rig, 2026-10-16, build 0.2.0-dev.1994: Search, Quick launch, Downloads, Desktop, Shelf,
 * Companion and the droidtop page missing).
 */
object LauncherSelfFilter {
    /** The activities shown: the one droidtop icon. */
    val SHOWN_ACTIVITIES: Set<String> = setOf("dev.droidtop.app.LauncherGamesActivity")

    /** Whether droidtop's own component [className] shows; [isWidgetProvider] is whether it provides an app widget. */
    fun shown(className: String, isWidgetProvider: Boolean): Boolean = isWidgetProvider || className in SHOWN_ACTIVITIES
}
