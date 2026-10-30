package dev.droidtop.display

import android.view.Display
import dev.droidtop.runtime.DualScreenOrchestration

/**
 * The second-screen decisions [SecondScreenOrchestrator.observe] acts on (Droidtop/tracker#765), pure so they are
 * tested apart from the display events and activity starts that feed and follow them.
 */
internal object SecondScreenPlan {
    /**
     * The display a game opens on for the launch-screen setting [target] (null when the setting does not apply, e.g.
     * outside Gaming or with one screen); null means the shell's own screen, which is also where Ask starts from.
     */
    fun gameLaunchDisplay(target: DisplayRolePrefs.GameLaunchTarget?, shellOnSecond: Boolean, secondId: Int?): Int? =
        when (target) {
            null, DisplayRolePrefs.GameLaunchTarget.ASK -> null
            DisplayRolePrefs.GameLaunchTarget.BUILT_IN -> Display.DEFAULT_DISPLAY
            DisplayRolePrefs.GameLaunchTarget.FOLLOW_SHELL -> if (shellOnSecond) secondId else null
            DisplayRolePrefs.GameLaunchTarget.SECOND -> secondId
        }

    /** What the orchestrator does about the shell's screen this pass. */
    enum class Relocation {
        NONE,

        /** Bring the shell back to the built-in screen. */
        TO_BUILT_IN,

        /** Move the shell to the second screen, leaving the built-in one as it is (an app is in front there). */
        TO_SECOND,

        /** Start the companion on the built-in screen, then move the shell to the second screen. */
        TO_SECOND_WITH_COMPANION,

        /** The shell is on the second screen already: put the companion back on the built-in one. */
        COMPANION_ON_BUILT_IN,
    }

    /** Whether the shell belongs on the second screen this pass: moving there, or there and wanted there. */
    fun wantsSecond(move: DualScreenOrchestration.ShellMove, secondId: Int?, shellOnSecond: Boolean, wantShellOnSecond: Boolean): Boolean =
        secondId != null && (move == DualScreenOrchestration.ShellMove.TO_SECOND || (shellOnSecond && wantShellOnSecond))

    /**
     * The step to take, given the [move] decided, where the shell is ([currentDisplay]) and the second screen
     * ([secondId]), whether the companion shows and an app is in front of its screen, and whether the cooldown since
     * the last attempt has passed ([cooledDown]). Nothing happens inside the cooldown.
     */
    fun relocation(
        move: DualScreenOrchestration.ShellMove,
        currentDisplay: Int,
        secondId: Int?,
        shellOnSecond: Boolean,
        wantShellOnSecond: Boolean,
        companionVisible: Boolean,
        appOnCompanionScreen: Boolean,
        cooledDown: Boolean,
    ): Relocation = when {
        !cooledDown -> Relocation.NONE
        move == DualScreenOrchestration.ShellMove.TO_BUILT_IN -> Relocation.TO_BUILT_IN
        !wantsSecond(move, secondId, shellOnSecond, wantShellOnSecond) -> Relocation.NONE
        currentDisplay != secondId -> if (appOnCompanionScreen) Relocation.TO_SECOND else Relocation.TO_SECOND_WITH_COMPANION
        !companionVisible && !appOnCompanionScreen -> Relocation.COMPANION_ON_BUILT_IN
        else -> Relocation.NONE
    }
}
