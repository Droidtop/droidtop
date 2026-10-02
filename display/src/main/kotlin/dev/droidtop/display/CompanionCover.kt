package dev.droidtop.display

/**
 * The display the companion Activity was paused on by something else (an
 * app launched or opened over it) and has not resumed on since, the
 * companion's counterpart of [SecondaryDisplayActivity.coveredDisplayId]
 * (Droidtop/tracker#265). The orchestration reads it through
 * [dev.droidtop.runtime.CompanionScreenGuard] so it never starts the
 * companion again over an app the user is using.
 */
object CompanionCover {
    @Volatile
    var displayId: Int? = null
        private set

    /** The companion paused on [onDisplayId]; a companion that is finishing was not covered. */
    fun paused(onDisplayId: Int?, finishing: Boolean) {
        if (!finishing && onDisplayId != null) displayId = onDisplayId
    }

    /** The companion is in front again on [onDisplayId]. */
    fun resumed(onDisplayId: Int?) {
        if (displayId == onDisplayId) displayId = null
    }

    /** The user asked for the screens back (Home, Main screen, reinitialize). */
    fun clear() {
        displayId = null
    }
}
