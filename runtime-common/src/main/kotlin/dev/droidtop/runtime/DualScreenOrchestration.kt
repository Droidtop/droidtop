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
     * is left with neither a live [dev.droidtop.app.SecondScreenPresentation]
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
