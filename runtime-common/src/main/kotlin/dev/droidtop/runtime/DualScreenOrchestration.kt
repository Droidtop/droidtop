package dev.droidtop.runtime

/**
 * The dual-screen decisions MainActivity's orchestration loop makes, as
 * pure functions — display work cannot be verified on hardware from the
 * development environment (docs/SPEC.md section 6c), so every decision
 * that CAN be proven without a device is kept out of the Activity and
 * unit-tested here.
 */
object DualScreenOrchestration {

    /**
     * Which secondary displays must get droidtop's idle surface placed on
     * them before a launch is dispatched.
     *
     * Why this exists at all: an Android secondary display whose own
     * window stack is empty falls back to MIRRORING the default display —
     * that is the platform's built-in behaviour, and it is exactly the
     * "launching apps mirrors them" report. droidtop cannot rely on the
     * platform placing its SECONDARY_HOME activity there, because that
     * only happens while droidtop holds the HOME role AND the display is
     * one Android decorates (docs/SPEC.md section 4c) — neither is
     * guaranteed on the addon. So droidtop places its own idle surface,
     * explicitly, on every secondary display the launch would otherwise
     * leave empty:
     *
     * - not the display the launch itself is going to (the game covers it);
     * - not the display the shell is rendering on (the shell covers it,
     *   and it stays resumed there — Android keeps top activities on
     *   OTHER displays resumed when a launch happens on one);
     * - not a display parked by an earlier launch (droidtop keeps its
     *   hands off a display an app is already running on).
     */
    fun displaysNeedingIdleCover(
        secondaryDisplayIds: List<Int>,
        launchTargetDisplayId: Int?,
        shellDisplayId: Int,
        parkedDisplayId: Int?,
    ): List<Int> = secondaryDisplayIds.filter {
        it != launchTargetDisplayId && it != shellDisplayId && it != parkedDisplayId
    }

    /**
     * Relocation give-up policy: moving the shell to the addon is a
     * startActivity the platform may refuse (some presentation-category
     * displays reject activity launches), and the existing cooldown only
     * stops the retry LOOP — it never concludes anything. After this many
     * whole cooldown windows in which the shell verifiably did not end up
     * on the addon, the orchestration stops fighting and falls back to
     * shell-on-built-in with the live companion covering the addon, so
     * the addon shows droidtop's surface instead of a mirror of whatever
     * the built-in panel is doing.
     */
    const val MAX_RELOCATION_ATTEMPTS = 2

    fun relocationHasFailed(attempts: Int): Boolean = attempts >= MAX_RELOCATION_ATTEMPTS

    /** Where the shell has to be moved so it matches the Main screen choice. */
    enum class ShellMove { NONE, TO_SECOND, TO_BUILT_IN }

    /** A detached display leaves one authoritative shell destination. */
    fun disconnectedShellDestination(shellDisplayId: Int, availableDisplayIds: Set<Int>): Int? =
        if (shellDisplayId !in availableDisplayIds) availableDisplayIds.firstOrNull() else null

    fun shouldShowSecondScreenCompanion(displayCount: Int): Boolean = displayCount > 1

    /**
     * A dismiss is itself an Activity start on the built-in display, which
     * takes focus from the shell; the shell's onStop/onStart then re-runs the
     * orchestration pass, which would dismiss again. So a dismiss is sent
     * only when a companion actually exists to dismiss (tracker#217).
     */
    fun shouldDismissCompanion(showCompanion: Boolean, companionVisible: Boolean): Boolean =
        !showCompanion && companionVisible

    /**
     * Whether the shell is on the second screen RIGHT NOW. Derived from the
     * display the shell actually occupies, never from a decision flag:
     * parking (an app launched onto the second screen) makes the decision
     * flag false while the shell has not moved, which flipped the launch
     * chooser's "this screen" / "the other screen" labels.
     */
    fun shellIsOnSecond(shellDisplayId: Int, secondDisplayId: Int?): Boolean =
        secondDisplayId != null && shellDisplayId == secondDisplayId

    /**
     * The one relocation decision for the shell, both ways. The Main screen
     * choice owns where the shell lives: "Second screen when connected"
     * moves it onto the second screen, "Built-in screen" moves it back, even
     * while an app is parked on the second screen (the shell was never what
     * the parking was about). Only the move TO the second screen respects
     * parking and a relocation the platform refused.
     */
    fun shellMove(
        shellDisplayId: Int,
        secondDisplayId: Int?,
        shellModeEligible: Boolean,
        mainScreenWantsSecond: Boolean,
        secondParked: Boolean,
        relocationFailed: Boolean,
    ): ShellMove {
        if (secondDisplayId == null || !shellModeEligible) return ShellMove.NONE
        val onSecond = shellIsOnSecond(shellDisplayId, secondDisplayId)
        return when {
            mainScreenWantsSecond && !onSecond && !secondParked && !relocationFailed -> ShellMove.TO_SECOND
            !mainScreenWantsSecond && onSecond -> ShellMove.TO_BUILT_IN
            else -> ShellMove.NONE
        }
    }

    /**
     * The display the live companion Presentation may be shown on, or null.
     * Never the display the shell occupies (it would sit on top of the
     * shell and eat its touches), never one an app is parked on, and not
     * while the shell is about to move there.
     */
    fun companionPresentationDisplayId(
        shellDisplayId: Int,
        secondDisplayId: Int?,
        secondParked: Boolean,
        move: ShellMove,
    ): Int? = when {
        secondDisplayId == null || secondParked -> null
        shellIsOnSecond(shellDisplayId, secondDisplayId) -> null
        move == ShellMove.TO_SECOND -> null
        else -> secondDisplayId
    }

    /**
     * The second display a USER-launched app is in the foreground of, or
     * null (tracker#243). The two screens are independent: an app the user
     * opened there (Android Settings, anything) stays, and droidtop does not
     * relaunch the companion, move the shell or re-front itself over it on
     * a timer or its own lifecycle callbacks. The caller treats the display
     * exactly like a parked one until this returns null again, which
     * happens only when that app is gone (the idle cover resumes there, so
     * [coverCoveredDisplayId] clears) or the user asks for the screen back
     * (Home, the screen chooser, Main screen, reinitialize).
     *
     * Two signals, since the shell and the idle cover are the two droidtop
     * surfaces something can be opened over:
     * - the shell is on the second display and is no longer started, so
     *   something else is in front of it;
     * - the idle cover was paused on the second display by something that
     *   is not the shell ([coverCoveredDisplayId]) and has not resumed.
     */
    fun userAppDisplayId(
        shellDisplayId: Int,
        secondDisplayId: Int?,
        shellStarted: Boolean,
        coverCoveredDisplayId: Int?,
    ): Int? {
        if (secondDisplayId == null) return null
        return if (shellIsOnSecond(shellDisplayId, secondDisplayId)) {
            if (!shellStarted) secondDisplayId else null
        } else {
            if (coverCoveredDisplayId == secondDisplayId) secondDisplayId else null
        }
    }
    /**
     * Chooser candidates in priority order: the addon/second screen FIRST,
     * so the default-highlighted row is the better surface (per direction:
     * when the add-on is attached it is the preferred screen, not an
     * afterthought). Labels stay relative — "this screen"/"the other
     * screen" is right however Android enumerated the panels (section 4c).
     */
    data class ChooserCandidate(val displayId: Int?, val label: String)

    fun chooserCandidates(secondDisplayId: Int, shellOnSecond: Boolean): List<ChooserCandidate> =
        if (shellOnSecond) {
            listOf(
                ChooserCandidate(secondDisplayId, "This screen (add-on)"),
                ChooserCandidate(null, "The other screen (built-in)"),
            )
        } else {
            listOf(
                ChooserCandidate(secondDisplayId, "The other screen (add-on)"),
                ChooserCandidate(null, "This screen (built-in)"),
            )
        }

    /**
     * Whether the add-on display looks like it needs a hard reinit right
     * now -- the "Reinitialize displays" pill's own visibility condition
     * (docs/SPEC.md section 4c, "Reinitialize displays" made automatic).
     *
     * Confirmed live (2026-09-25): mirroring can recur through a door
     * [displaysNeedingIdleCover] does not watch -- an app on the addon
     * exits on its own, with droidtop's own shell never losing foreground
     * on ITS display, so nothing re-runs role orchestration and the addon
     * is left with neither a live [dev.droidtop.display.SecondScreenPresentation]
     * nor the idle SECONDARY_HOME cover -- which is exactly when Android
     * falls back to mirroring it. This is the same "is anything of ours
     * actually on the addon" question [displaysNeedingIdleCover] asks
     * before a launch, asked continuously instead of only pre-launch.
     *
     * A display the user launched an app onto ([parkedDisplayId] ==
     * [secondDisplayId]) is deliberately excluded: droidtop keeps its
     * hands off a display an app is running on, by design, not by
     * accident, so that is never "broken."
     */
    fun secondScreenNeedsReinit(
        secondDisplayId: Int?,
        parkedDisplayId: Int?,
        shellOnSecond: Boolean,
        presentationDisplayId: Int?,
        idleCoverDisplayId: Int?,
    ): Boolean {
        if (secondDisplayId == null || secondDisplayId == parkedDisplayId) return false
        // The shell itself is the content there (Gaming/Desktop on the
        // addon as the main output) -- nothing else needs to cover it.
        if (shellOnSecond) return false
        if (presentationDisplayId == secondDisplayId) return false
        if (idleCoverDisplayId == secondDisplayId) return false
        return true
    }
}
