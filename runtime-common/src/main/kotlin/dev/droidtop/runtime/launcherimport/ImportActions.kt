package dev.droidtop.runtime.launcherimport

/**
 * Nova's and Apex's own home-screen actions in droidtop's terms (Droidtop/tracker#564, the format document's
 * mapping): an action Standard has becomes its gesture action's name; one it has not is null, and the import says
 * so. Pure.
 */
object ImportActions {
    /** droidtop's gesture slots (Standard's `LauncherPrefs.GESTURE_*_ACTION`), by name. */
    const val DOUBLE_TAP = "DOUBLE_TAP"
    const val SWIPE_DOWN = "SWIPE_DOWN"
    const val TWO_FINGER_DOWN = "TWO_FINGER_DOWN"
    const val TWO_FINGER_UP = "TWO_FINGER_UP"
    const val PINCH_IN = "PINCH_IN"
    const val PINCH_OUT = "PINCH_OUT"
    const val HOME_BUTTON = "HOME_BUTTON"

    /** A launcher action name (both launchers share most of them) as one of Standard's `GestureAction` names. */
    fun droidtop(name: String?): String? = when (name?.uppercase()) {
        null -> null
        "NONE" -> "NONE"
        "APP_DRAWER" -> "OPEN_DRAWER"
        "SHOW_NOTIFICATIONS", "EXPAND_STATUS_BAR" -> "OPEN_NOTIFICATIONS"
        "RECENT_APPS", "SHOW_RECENT_APPS" -> "OPEN_RECENTS"
        "SCREEN_LOCK", "SCREEN_OFF" -> "LOCK_SCREEN"
        "SEARCH", "TEXT_SEARCH", "APP_SEARCH" -> "OPEN_SEARCH"
        else -> null
    }

    /** A plain name for a launcher action droidtop has no equivalent of, for the summary. */
    fun describe(name: String): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
