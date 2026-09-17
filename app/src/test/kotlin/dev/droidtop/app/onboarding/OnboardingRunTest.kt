package dev.droidtop.app.onboarding

import dev.droidtop.app.OnboardingRun
import dev.droidtop.app.OnboardingStep
import dev.droidtop.app.plannedSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A run of onboarding is ONE run, whichever way up the device is held.
 *
 * The rig defect (build 546): walking to "Step 7 of 10 -- Appearance" in
 * landscape and rotating came back at "Step 1 of 7". Rotating destroys
 * and rebuilds the Activity, which took the answers with it and then
 * rebuilt the plan from a fresh reading of the storage permission --
 * which by then was granted, so the plan lost a step as well.
 */
class OnboardingRunTest {

    @Test
    fun `a second start does not re-seed the run`() {
        val run = OnboardingRun()
        run.start(startStep = null, storageGranted = false)
        run.configureGaming.value = true
        run.homeChoice.value = null
        run.step.value = OnboardingStep.APPEARANCE
        run.history.add(OnboardingStep.WELCOME)

        // What a rotation does: the Activity is built again and seeds the
        // run again, with the permission now held.
        run.start(startStep = OnboardingStep.CONTROLLER, storageGranted = true)

        assertEquals(false, run.storageGrantedAtEntry)
        assertEquals(null, run.startStep)
        assertEquals(OnboardingStep.APPEARANCE, run.step.value)
        assertEquals(true, run.configureGaming.value)
        assertEquals(listOf(OnboardingStep.WELCOME), run.history.toList())
    }

    @Test
    fun `the plan the run is walking does not change when the device turns`() {
        val run = OnboardingRun()
        run.start(startStep = null, storageGranted = false)
        run.configureGaming.value = true

        fun planNow() = plannedSteps(
            home = run.homeChoice.value,
            configureDesktop = run.configureDesktop.value,
            configureGaming = run.configureGaming.value,
            storageGranted = run.storageGrantedAtEntry,
        )

        val before = planNow()
        run.step.value = before[6]
        // Granting the permission mid-run and then rotating: both the
        // things that changed the plan on the rig, at once.
        run.storageAccessGranted.value = true
        run.start(startStep = null, storageGranted = true)

        assertEquals(before, planNow())
        assertTrue("the step the user is on stays in the plan", run.step.value in planNow())
    }
}
