package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The orchestration decisions behind the mirroring fix, the relocation
 * give-up policy, and the addon-first chooser ordering (docs/SPEC.md
 * sections 4 and 4c) — pure, because none of it can be verified on
 * hardware from this environment (section 6c).
 */
class DualScreenOrchestrationTest {

    @Test
    fun `second screen preference has no effect with one display and no companion is shown`() {
        assertEquals(DualScreenOrchestration.ShellMove.NONE,
            DualScreenOrchestration.shellMove(0, null, true, true, false, false))
        assertFalse(DualScreenOrchestration.shouldShowSecondScreenCompanion(1))
    }

    @Test
    fun `a companion surface left on the only display finishes`() {
        assertTrue(DualScreenOrchestration.companionSurfaceRetires(1, 0, secondaryOnly = false))
        assertTrue(DualScreenOrchestration.companionSurfaceRetires(1, 0, secondaryOnly = true))
        assertFalse(DualScreenOrchestration.companionSurfaceRetires(2, 0, secondaryOnly = false))
    }

    @Test
    fun `the idle cover finishes on the built-in display but stays on the add-on`() {
        assertTrue(DualScreenOrchestration.companionSurfaceRetires(2, 0, secondaryOnly = true))
        assertFalse(DualScreenOrchestration.companionSurfaceRetires(2, 9, secondaryOnly = true))
    }

    @Test
    fun `a companion is dismissed only when one is visible and not wanted`() {
        assertFalse(DualScreenOrchestration.shouldDismissCompanion(showCompanion = false, companionVisible = false))
        assertTrue(DualScreenOrchestration.shouldDismissCompanion(showCompanion = false, companionVisible = true))
        assertFalse(DualScreenOrchestration.shouldDismissCompanion(showCompanion = true, companionVisible = true))
        assertFalse(DualScreenOrchestration.shouldDismissCompanion(showCompanion = true, companionVisible = false))
    }

    @Test
    fun `disconnecting the display occupied by shell moves shell to remaining built-in`() {
        assertEquals(0, DualScreenOrchestration.disconnectedShellDestination(9, setOf(0)))
    }

    @Test
    fun `reconnecting second display restores selected arrangement`() {
        assertEquals(DualScreenOrchestration.ShellMove.TO_SECOND,
            DualScreenOrchestration.shellMove(0, 9, true, true, false, false))
        assertTrue(DualScreenOrchestration.shouldShowSecondScreenCompanion(2))
    }

    @Test
    fun `a launch to the built-in screen covers the addon with the idle surface`() {
        // The reported bug: game goes to the default display, the addon's
        // stack is left empty, and Android mirrors the default display
        // onto it. The addon must be covered.
        assertEquals(
            listOf(9),
            DualScreenOrchestration.displaysNeedingIdleCover(
                secondaryDisplayIds = listOf(9),
                launchTargetDisplayId = null,
                shellDisplayId = 0,
                parkedDisplayId = null,
            ),
        )
    }

    @Test
    fun `a launch to the addon itself needs no cover there`() {
        assertEquals(
            emptyList<Int>(),
            DualScreenOrchestration.displaysNeedingIdleCover(
                secondaryDisplayIds = listOf(9),
                launchTargetDisplayId = 9,
                shellDisplayId = 0,
                parkedDisplayId = null,
            ),
        )
    }

    @Test
    fun `the display the shell renders on is never covered`() {
        // Shell relocated to the addon: it stays resumed and visible
        // there while a game launches on the built-in panel.
        assertEquals(
            emptyList<Int>(),
            DualScreenOrchestration.displaysNeedingIdleCover(
                secondaryDisplayIds = listOf(9),
                launchTargetDisplayId = null,
                shellDisplayId = 9,
                parkedDisplayId = null,
            ),
        )
    }

    @Test
    fun `a display parked by an earlier launch is left alone`() {
        // An app is already running there; covering it would put
        // droidtop's idle surface over a live game.
        assertEquals(
            emptyList<Int>(),
            DualScreenOrchestration.displaysNeedingIdleCover(
                secondaryDisplayIds = listOf(9),
                launchTargetDisplayId = null,
                shellDisplayId = 0,
                parkedDisplayId = 9,
            ),
        )
    }

    @Test
    fun `with two secondary displays each is judged separately`() {
        assertEquals(
            listOf(12),
            DualScreenOrchestration.displaysNeedingIdleCover(
                secondaryDisplayIds = listOf(9, 12),
                launchTargetDisplayId = 9,
                shellDisplayId = 0,
                parkedDisplayId = null,
            ),
        )
    }

    @Test
    fun `relocation gives up after the configured number of attempts`() {
        assertFalse(DualScreenOrchestration.relocationHasFailed(0))
        assertFalse(DualScreenOrchestration.relocationHasFailed(1))
        assertTrue(DualScreenOrchestration.relocationHasFailed(DualScreenOrchestration.MAX_RELOCATION_ATTEMPTS))
    }

    @Test
    fun `the addon is the first chooser row whichever screen the shell is on`() {
        // Prioritising the external screen: the default-highlighted row
        // is the addon in both arrangements; only the relative labels
        // change.
        val names = mapOf(0 to "Bottom screen", 9 to "Top screen")
        val shellOnAddon = DualScreenOrchestration.chooserCandidates(listOf(0, 9), names, shellDisplayId = 9, secondDisplayId = 9)
        assertEquals(9, shellOnAddon.first().displayId)
        assertEquals("Top screen (this one)", shellOnAddon.first().label)
        assertEquals(null, shellOnAddon[1].displayId)
        assertEquals("Bottom screen (the other one)", shellOnAddon[1].label)

        val shellOnBuiltIn = DualScreenOrchestration.chooserCandidates(listOf(0, 9), names, shellDisplayId = 0, secondDisplayId = 9)
        assertEquals(9, shellOnBuiltIn.first().displayId)
        assertEquals("Top screen (the other one)", shellOnBuiltIn.first().label)
        assertEquals("Bottom screen (this one)", shellOnBuiltIn[1].label)
    }

    @Test
    fun `three screens are all offered, only the two roles can be remembered`() {
        val names = mapOf(0 to "Top screen", 4 to "Bottom screen", 9 to "DELL U2415")
        val rows = DualScreenOrchestration.chooserCandidates(listOf(0, 9, 4), names, shellDisplayId = 0, secondDisplayId = 4)
        assertEquals(listOf(4, 9, null), rows.map { it.displayId })
        assertEquals(listOf("Bottom screen", "DELL U2415", "Top screen (this one)"), rows.map { it.label })
        assertEquals(listOf(true, false, true), rows.map { it.rememberable })
    }

    @Test
    fun `no second display means nothing to reinit`() {
        assertFalse(
            DualScreenOrchestration.secondScreenNeedsReinit(
                secondDisplayId = null,
                parkedDisplayId = null,
                shellOnSecond = false,
                presentationDisplayId = null,
                idleCoverDisplayId = null,
            ),
        )
    }

    @Test
    fun `a parked addon (an app the user launched) is never broken`() {
        assertFalse(
            DualScreenOrchestration.secondScreenNeedsReinit(
                secondDisplayId = 9,
                parkedDisplayId = 9,
                shellOnSecond = false,
                presentationDisplayId = null,
                idleCoverDisplayId = null,
            ),
        )
    }

    @Test
    fun `the shell itself on the addon needs no separate cover`() {
        assertFalse(
            DualScreenOrchestration.secondScreenNeedsReinit(
                secondDisplayId = 9,
                parkedDisplayId = null,
                shellOnSecond = true,
                presentationDisplayId = null,
                idleCoverDisplayId = null,
            ),
        )
    }

    @Test
    fun `a live presentation on the addon means it is covered`() {
        assertFalse(
            DualScreenOrchestration.secondScreenNeedsReinit(
                secondDisplayId = 9,
                parkedDisplayId = null,
                shellOnSecond = false,
                presentationDisplayId = 9,
                idleCoverDisplayId = null,
            ),
        )
    }

    @Test
    fun `the idle cover activity on the addon means it is covered`() {
        assertFalse(
            DualScreenOrchestration.secondScreenNeedsReinit(
                secondDisplayId = 9,
                parkedDisplayId = null,
                shellOnSecond = false,
                presentationDisplayId = null,
                idleCoverDisplayId = 9,
            ),
        )
    }

    @Test
    fun `the addon with neither a presentation nor an idle cover is broken -- the mirroring recurrence`() {
        // The exact confirmed-live case: an app on the addon exited on
        // its own, and nothing droidtop tracks is left on that display.
        assertTrue(
            DualScreenOrchestration.secondScreenNeedsReinit(
                secondDisplayId = 9,
                parkedDisplayId = null,
                shellOnSecond = false,
                presentationDisplayId = null,
                idleCoverDisplayId = null,
            ),
        )
    }

    @Test
    fun `a presentation tracked on a DIFFERENT display does not count`() {
        assertTrue(
            DualScreenOrchestration.secondScreenNeedsReinit(
                secondDisplayId = 9,
                parkedDisplayId = null,
                shellOnSecond = false,
                presentationDisplayId = 12,
                idleCoverDisplayId = null,
            ),
        )
    }

    @Test
    fun `Main screen Built-in moves a shell sitting on the second screen back`() {
        // Tracker#163: the shell stayed on display 10 after the choice
        // changed, and the companion then covered it.
        assertEquals(
            DualScreenOrchestration.ShellMove.TO_BUILT_IN,
            DualScreenOrchestration.shellMove(
                shellDisplayId = 10, secondDisplayId = 10, shellModeEligible = true,
                mainScreenWantsSecond = false, secondParked = false, relocationFailed = false,
            ),
        )
    }

    @Test
    fun `Built-in still moves the shell back while an app is parked on the second screen`() {
        assertEquals(
            DualScreenOrchestration.ShellMove.TO_BUILT_IN,
            DualScreenOrchestration.shellMove(
                shellDisplayId = 10, secondDisplayId = 10, shellModeEligible = true,
                mainScreenWantsSecond = false, secondParked = true, relocationFailed = false,
            ),
        )
    }

    @Test
    fun `Second-when-present moves a built-in shell to the second screen unless parked or refused`() {
        fun move(parked: Boolean, failed: Boolean) = DualScreenOrchestration.shellMove(
            shellDisplayId = 0, secondDisplayId = 10, shellModeEligible = true,
            mainScreenWantsSecond = true, secondParked = parked, relocationFailed = failed,
        )
        assertEquals(DualScreenOrchestration.ShellMove.TO_SECOND, move(parked = false, failed = false))
        assertEquals(DualScreenOrchestration.ShellMove.NONE, move(parked = true, failed = false))
        assertEquals(DualScreenOrchestration.ShellMove.NONE, move(parked = false, failed = true))
    }

    @Test
    fun `a shell already where the choice wants it, or without a second screen, does not move`() {
        val none = DualScreenOrchestration.ShellMove.NONE
        assertEquals(none, DualScreenOrchestration.shellMove(10, 10, true, true, false, false))
        assertEquals(none, DualScreenOrchestration.shellMove(0, 10, true, false, false, false))
        assertEquals(none, DualScreenOrchestration.shellMove(0, null, true, true, false, false))
        assertEquals(none, DualScreenOrchestration.shellMove(10, 10, false, false, false, false))
    }

    @Test
    fun `the companion Presentation is never placed on the display the shell occupies`() {
        val builtIn = DualScreenOrchestration.ShellMove.NONE
        assertEquals(null, DualScreenOrchestration.companionPresentationDisplayId(10, 10, false, builtIn))
        assertEquals(
            null,
            DualScreenOrchestration.companionPresentationDisplayId(
                0, 10, false, DualScreenOrchestration.ShellMove.TO_SECOND,
            ),
        )
        assertEquals(null, DualScreenOrchestration.companionPresentationDisplayId(0, 10, true, builtIn))
        assertEquals(10, DualScreenOrchestration.companionPresentationDisplayId(0, 10, false, builtIn))
        assertEquals(null, DualScreenOrchestration.companionPresentationDisplayId(0, null, false, builtIn))
    }

    @Test
    fun `chooser labels follow the shell's real display, parked or not`() {
        // Tracker#162: shell on display 10 with display 10 parked read as
        // "not on second" and swapped the labels.
        assertTrue(DualScreenOrchestration.shellIsOnSecond(shellDisplayId = 10, secondDisplayId = 10))
        assertFalse(DualScreenOrchestration.shellIsOnSecond(shellDisplayId = 0, secondDisplayId = 10))
        assertFalse(DualScreenOrchestration.shellIsOnSecond(shellDisplayId = 0, secondDisplayId = null))
    }

    @Test
    fun `a user app over the idle cover on the second display owns it and is never evicted`() {
        // Tracker#243: Settings opened on display 10 while the shell is on 0.
        val owned = DualScreenOrchestration.userAppDisplayId(0, 10, shellStarted = true, coverCoveredDisplayId = 10)
        assertEquals(10, owned)
        // Treated as parked: no companion there, no shell move onto it.
        assertEquals(null, DualScreenOrchestration.companionPresentationDisplayId(
            0, 10, secondParked = owned != null, move = DualScreenOrchestration.ShellMove.NONE))
        assertEquals(DualScreenOrchestration.ShellMove.NONE,
            DualScreenOrchestration.shellMove(0, 10, true, true, secondParked = true, relocationFailed = false))
        assertFalse(DualScreenOrchestration.secondScreenNeedsReinit(10, 10, false, null, null))
    }

    @Test
    fun `a user app over the shell on the second display owns it only while the shell is not started`() {
        assertEquals(10, DualScreenOrchestration.userAppDisplayId(10, 10, shellStarted = false, coverCoveredDisplayId = null))
        assertEquals(null, DualScreenOrchestration.userAppDisplayId(10, 10, shellStarted = true, coverCoveredDisplayId = null))
        // A cover covered while the shell itself sits there is not a user app.
        assertEquals(null, DualScreenOrchestration.userAppDisplayId(10, 10, shellStarted = true, coverCoveredDisplayId = 10))
    }

    @Test
    fun `the display is idle again once the user app is gone`() {
        assertEquals(null, DualScreenOrchestration.userAppDisplayId(0, 10, true, coverCoveredDisplayId = null))
        assertEquals(10, DualScreenOrchestration.companionPresentationDisplayId(
            0, 10, secondParked = false, move = DualScreenOrchestration.ShellMove.NONE))
    }

    @Test
    fun `no second display means no user-owned display`() {
        assertEquals(null, DualScreenOrchestration.userAppDisplayId(0, null, false, 10))
    }
}
