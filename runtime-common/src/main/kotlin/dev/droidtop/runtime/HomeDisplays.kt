package dev.droidtop.runtime

/**
 * Which of droidtop's screens a Home press brings back, by the display it was pressed on (docs/SPEC.md 2c and 4c,
 * "Home on every screen"; the owner, 2026-10-17: "It should bring droidtop back on whichever screen we're looking at.
 * Companion if it's that screen, main if it's that one"). Android sends Home on the default display to droidtop's
 * HOME activity (HomeTrampolineActivity) and Home on a secondary display it decorates to droidtop's SECONDARY_HOME
 * activity (SecondaryDisplayActivity); each asks [targetFor] with its own display.
 *
 * The companion and the shell say where they are ([companionShown], [shellShown]): process memory, as the rest of
 * the screen arrangement is. A process that is not running knows neither, and Home then brings the main screen back,
 * which arranges the rest.
 */
object HomeDisplays {
    /** What a Home press on one display brings to the front there. */
    enum class Target {
        /** The companion screen (CompanionActivity) on that display. */
        COMPANION,

        /** The current mode's main screen (Gaming, Desktop or Standard) on the display it runs on. */
        MAIN,

        /** Neither lives there: the secondary surface droidtop draws on any other screen. */
        SURFACE,
    }

    @Volatile
    var companionDisplayId: Int? = null
        private set

    @Volatile
    var shellDisplayId: Int? = null
        private set

    /** The companion is on [displayId] (null: it is gone). */
    fun companionShown(displayId: Int?) {
        companionDisplayId = displayId
    }

    /** The companion on [displayId] finished: nothing of it is there any more. */
    fun companionGone(displayId: Int?) {
        if (companionDisplayId == displayId) companionDisplayId = null
    }

    /** The main shell is in front on [displayId] (null: it left). */
    fun shellShown(displayId: Int?) {
        if (displayId != null) shellDisplayId = displayId
    }

    /**
     * What a Home press on [pressedOn] brings back: the companion where the companion lives (unless the shell has
     * moved onto that display), the main screen where the shell lives or on the default display, and the secondary
     * surface on any other display. Pure.
     */
    fun targetFor(pressedOn: Int, companionDisplayId: Int?, shellDisplayId: Int?, defaultDisplayId: Int = 0): Target = when {
        companionDisplayId == pressedOn && shellDisplayId != pressedOn -> Target.COMPANION
        shellDisplayId == pressedOn || pressedOn == defaultDisplayId -> Target.MAIN
        else -> Target.SURFACE
    }

    fun targetFor(pressedOn: Int): Target = targetFor(pressedOn, companionDisplayId, shellDisplayId)
}
