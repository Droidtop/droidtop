package dev.droidtop.display

import android.view.Display
import dev.droidtop.runtime.DisplayOutput
import dev.droidtop.runtime.DisplayOutputKind
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

    /**
     * The one screen paired with the built-in one (docs/SPEC.md 4c "What each screen is for"): the main screen when the
     * menus are elsewhere, else the companion's; null when that screen is not a second screen in [outputs].
     */
    fun pairedSecond(outputs: List<DisplayOutput>, mainId: Int, companionId: Int?): DisplayOutput? {
        val pairedId = if (mainId != Display.DEFAULT_DISPLAY) mainId else companionId
        return outputs.firstOrNull { it.androidDisplayId == pairedId && it.kind == DisplayOutputKind.SECOND_SCREEN }
    }

    /**
     * The second screens a launch may leave empty, for the idle cover (4c, the mirroring fix): every second screen
     * that is not mirrored and does not show the desktop ([desktopOn]), the companion's first.
     */
    fun coverCandidates(
        outputs: List<DisplayOutput>,
        mirroredIds: Set<Int>,
        companionDisplayId: Int?,
        desktopOn: Set<Int> = emptySet(),
    ): List<Int> =
        outputs
            .filter { it.kind == DisplayOutputKind.SECOND_SCREEN && it.androidDisplayId !in mirroredIds && it.androidDisplayId !in desktopOn }
            .map { it.androidDisplayId }
            .sortedBy { if (it == companionDisplayId) 0 else 1 }

    // The desktop on a second screen (docs/SPEC.md 2b, Droidtop/tracker#908 step D5): while the desktop is drawn on a
    // display ([desktopOn], DesktopExtension), nothing of the companion's and no idle cover is put there.

    /** The display the companion's Presentation goes on: [candidate] (DualScreenOrchestration's), unless the desktop is drawn there. */
    fun companionPresentation(candidate: Int?, desktopOn: Set<Int>): Int? = candidate?.takeIf { it !in desktopOn }

    /**
     * [step] with the companion kept off the built-in screen while the desktop is drawn there: the shell still moves,
     * but the companion is not started under it ([Relocation.TO_SECOND] for [Relocation.TO_SECOND_WITH_COMPANION]) or
     * put back over it ([Relocation.NONE] for [Relocation.COMPANION_ON_BUILT_IN]).
     */
    fun aroundDesktop(step: Relocation, desktopOn: Set<Int>): Relocation = when {
        Display.DEFAULT_DISPLAY !in desktopOn -> step
        step == Relocation.TO_SECOND_WITH_COMPANION -> Relocation.TO_SECOND
        step == Relocation.COMPANION_ON_BUILT_IN -> Relocation.NONE
        else -> step
    }

    /** Whether droidtop may put its idle cover on [displayId]: not while the desktop is drawn there. */
    fun mayCover(displayId: Int, desktopOn: Set<Int>): Boolean = displayId !in desktopOn

    /** "Reinitialize displays" ([broken], DualScreenOrchestration's) is not offered for a second screen the desktop is drawn on: it is in use. */
    fun reinitOffered(broken: Boolean, secondId: Int?, desktopOn: Set<Int>): Boolean = broken && secondId !in desktopOn
}
