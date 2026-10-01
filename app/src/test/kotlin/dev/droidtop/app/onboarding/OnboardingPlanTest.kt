package dev.droidtop.app.onboarding

import dev.droidtop.app.OnboardingStep
import dev.droidtop.app.plannedSteps
import dev.droidtop.library.settings.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan is the pipeline: every step the run presents comes out of it,
 * and "Step N of M" is counted against it (docs/SPEC.md 7b). Only the
 * steps the answers need are in it.
 */
class OnboardingPlanTest {

    @Test
    fun `a gaming-only run with no controller is five steps`() {
        assertEquals(
            listOf(OnboardingStep.MODE, OnboardingStep.HOME, OnboardingStep.GAMES, OnboardingStep.APPEARANCE, OnboardingStep.DONE),
            plannedSteps(Mode.GAMING, alsoOther = false, controllerAttached = false),
        )
    }

    @Test
    fun `desktop brings its own two steps and nothing of gaming`() {
        val steps = plannedSteps(Mode.DESKTOP, alsoOther = false, controllerAttached = false)
        assertTrue(OnboardingStep.GAMES !in steps)
        assertTrue(OnboardingStep.APPEARANCE !in steps)
        assertTrue(OnboardingStep.DESKTOP_SETUP in steps)
        // The keyboard's reason is terminals and Windows programs (rig,
        // dq-coordinator-24, finding 10).
        assertTrue(OnboardingStep.KEYBOARD in steps)
    }

    @Test
    fun `setting up both keeps each mode's steps together`() {
        val steps = plannedSteps(Mode.GAMING, alsoOther = true)
        assertEquals(steps.indexOf(OnboardingStep.GAMES) + 1, steps.indexOf(OnboardingStep.APPEARANCE))
        assertEquals(steps.indexOf(OnboardingStep.DESKTOP_SETUP) + 1, steps.indexOf(OnboardingStep.KEYBOARD))
        assertTrue(steps.indexOf(OnboardingStep.APPEARANCE) < steps.indexOf(OnboardingStep.DESKTOP_SETUP))
    }

    @Test
    fun `the controller is asked only when one was attached, after the modes`() {
        val with = plannedSteps(Mode.GAMING, alsoOther = true, controllerAttached = true)
        assertEquals(OnboardingStep.CONTROLLER, with[with.size - 2])
        assertTrue(OnboardingStep.CONTROLLER !in plannedSteps(Mode.GAMING, alsoOther = true, controllerAttached = false))
    }

    @Test
    fun `every plan starts with the two questions and ends with the summary`() {
        listOf(
            plannedSteps(Mode.GAMING, alsoOther = false),
            plannedSteps(Mode.DESKTOP, alsoOther = true, controllerAttached = false),
        ).forEach { steps ->
            assertEquals(listOf(OnboardingStep.MODE, OnboardingStep.HOME), steps.take(2))
            assertEquals(OnboardingStep.DONE, steps.last())
        }
    }

    @Test
    fun `old step names land on the merged step that holds the question`() {
        // A saved first run, or a Settings row built before the merge.
        assertEquals(OnboardingStep.HOME, OnboardingStep.byName("HOME_CHOICE"))
        assertEquals(OnboardingStep.HOME, OnboardingStep.byName("STANDARD_SETUP"))
        assertEquals(OnboardingStep.GAMES, OnboardingStep.byName("STORAGE_PERMISSION"))
        assertEquals(OnboardingStep.GAMES, OnboardingStep.byName("GAMES_FOLDERS"))
        assertEquals(OnboardingStep.MODE, OnboardingStep.byName("DEFAULT_MODE_CHOICE"))
        assertEquals(OnboardingStep.DONE, OnboardingStep.byName("WHAT_NEXT"))
        assertEquals(OnboardingStep.CONTROLLER, OnboardingStep.byName("CONTROLLER"))
        assertEquals(null, OnboardingStep.byName(null))
        assertEquals(null, OnboardingStep.byName("NOT_A_STEP"))
    }
}
