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
        val shellOnAddon = DualScreenOrchestration.chooserCandidates(secondDisplayId = 9, shellOnSecond = true)
        assertEquals(9, shellOnAddon.first().displayId)
        assertEquals("This screen (add-on)", shellOnAddon.first().label)
        assertEquals(null, shellOnAddon[1].displayId)

        val shellOnBuiltIn = DualScreenOrchestration.chooserCandidates(secondDisplayId = 9, shellOnSecond = false)
        assertEquals(9, shellOnBuiltIn.first().displayId)
        assertEquals("The other screen (add-on)", shellOnBuiltIn.first().label)
        assertEquals("This screen (built-in)", shellOnBuiltIn[1].label)
    }
}
