package dev.droidtop.app.onboarding

import dev.droidtop.app.OnboardingStep
import dev.droidtop.app.plannedSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan is the pipeline: every step the run presents comes out of it,
 * and "step N of M" is counted against it. Both defects below were live
 * on the Android 9 rig with fresh data (build 531).
 */
class OnboardingPlanTest {

    private fun plan(gaming: Boolean, storageGranted: Boolean) = plannedSteps(
        home = null,
        configureDesktop = false,
        configureGaming = gaming,
        storageGranted = storageGranted,
    )

    @Test
    fun `a run that must ask for storage still has every gaming step after it`() {
        val steps = plan(gaming = true, storageGranted = false)
        assertTrue(steps.contains(OnboardingStep.STORAGE_PERMISSION))
        val storage = steps.indexOf(OnboardingStep.STORAGE_PERMISSION)
        val folders = steps.indexOf(OnboardingStep.GAMES_FOLDERS)
        assertTrue("games folders must follow storage", storage in 0 until folders)
        assertEquals(steps.last(), OnboardingStep.WHAT_NEXT)
    }

    @Test
    fun `granting storage does not shorten the plan under the user`() {
        // The rig defect: the plan was recomputed with the permission now
        // held, STORAGE_PERMISSION left it, and the run had no next step.
        // Whatever the plan says, the step the user is standing on has to
        // be in it -- so a run that opened without the permission keeps
        // its storage step for the whole run.
        val asked = plan(gaming = true, storageGranted = false)
        val granted = plan(gaming = true, storageGranted = true)
        assertEquals(asked.size - 1, granted.size)
        assertEquals(
            asked.filterNot { it == OnboardingStep.STORAGE_PERMISSION },
            granted,
        )
    }

    @Test
    fun `a run that already holds storage never asks`() {
        assertTrue(OnboardingStep.STORAGE_PERMISSION !in plan(gaming = true, storageGranted = true))
    }

    @Test
    fun `no gaming means no gaming steps`() {
        val steps = plan(gaming = false, storageGranted = false)
        assertTrue(OnboardingStep.STORAGE_PERMISSION !in steps)
        assertTrue(OnboardingStep.GAMES_FOLDERS !in steps)
        // The theme is Gaming's; the pad is how the shell itself is driven.
        assertTrue(OnboardingStep.APPEARANCE !in steps)
        assertTrue(OnboardingStep.CONTROLLER in steps)
    }

    @Test
    fun `input and appearance come after the games steps and before the keyboard`() {
        val steps = plan(gaming = true, storageGranted = true)
        val folders = steps.indexOf(OnboardingStep.GAMES_FOLDERS)
        val controller = steps.indexOf(OnboardingStep.CONTROLLER)
        val appearance = steps.indexOf(OnboardingStep.APPEARANCE)
        val keyboard = steps.indexOf(OnboardingStep.KEYBOARD)
        assertTrue(folders < controller)
        assertTrue(controller < appearance)
        assertTrue(appearance < keyboard)
    }
}
