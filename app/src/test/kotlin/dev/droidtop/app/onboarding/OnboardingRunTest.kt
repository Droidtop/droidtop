package dev.droidtop.app.onboarding

import dev.droidtop.app.OnboardingRun
import dev.droidtop.app.OnboardingStep
import dev.droidtop.app.SetupBefore
import dev.droidtop.app.plannedSteps
import dev.droidtop.library.settings.Mode
import dev.droidtop.shell.standard.HomeRolePrefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A run of onboarding is ONE run, whichever way up the device is held, and
 * whichever build wrote it down.
 *
 * The rig defect (build 546): walking to a late step in landscape and
 * rotating came back at step 1, because the Activity took the answers
 * with it. And a run saved by the flow before the steps were merged
 * (2026-10-01) must resume on the merged step, not start over.
 */
class OnboardingRunTest {

    @Test
    fun `a second start does not re-seed the run`() {
        val run = OnboardingRun()
        run.start(startStep = null)
        run.opensInto.value = Mode.DESKTOP
        run.step.value = OnboardingStep.APPEARANCE
        run.history.add(OnboardingStep.MODE)

        // What a rotation does: the Activity is built again and seeds the
        // run again.
        run.start(startStep = OnboardingStep.CONTROLLER, controllerAttached = false)

        assertEquals(null, run.startStep)
        assertEquals(OnboardingStep.APPEARANCE, run.step.value)
        assertEquals(Mode.DESKTOP, run.opensInto.value)
        assertEquals(listOf(OnboardingStep.MODE), run.history.toList())
        assertEquals(true, run.controllerAttachedAtEntry)
    }

    @Test
    fun `the plan the run is walking does not change when the device turns`() {
        val run = OnboardingRun()
        run.start(startStep = null, controllerAttached = true)
        run.alsoOther.value = true

        fun planNow() = plannedSteps(run.opensInto.value, run.alsoOther.value, run.controllerAttachedAtEntry)

        val before = planNow()
        run.step.value = before[4]
        run.start(startStep = null, controllerAttached = false)

        assertEquals(before, planNow())
        assertTrue("the step the user is on stays in the plan", run.step.value in planNow())
    }

    @Test
    fun `a first run starts on the defaults and a rerun starts from the setup as it is`() {
        val first = OnboardingRun()
        first.start(startStep = null)
        assertEquals(Mode.GAMING, first.opensInto.value)
        assertEquals(false, first.alsoOther.value)
        assertEquals(HomeRolePrefs.HomeImplementation.NONE, first.homeChoice.value)
        assertTrue(first.configureGaming)
        assertTrue(!first.configureDesktop)

        val rerun = OnboardingRun()
        rerun.start(
            startStep = null,
            before = SetupBefore(
                enabled = setOf(Mode.GAMING, Mode.DESKTOP, Mode.LAUNCHER),
                defaultMode = Mode.LAUNCHER,
                home = HomeRolePrefs.HomeImplementation.STANDARD,
                alternative = null,
            ),
        )
        assertEquals(Mode.GAMING, rerun.opensInto.value)
        assertEquals(true, rerun.alsoOther.value)
        assertEquals(HomeRolePrefs.HomeImplementation.STANDARD, rerun.homeChoice.value)
        // "Opens into Android" is the Home step's "droidtop's home screen" row now.
        assertEquals(true, rerun.homeShowsHomeScreen.value)
        assertEquals(Mode.LAUNCHER, rerun.defaultMode)
    }

    @Test
    fun `a run saved before the merge resumes on the merged step with its answers`() {
        val saved = JSONObject(
            """{"step":"STORAGE_PERMISSION","history":["WELCOME","HOME_CHOICE","STANDARD_SETUP","CONFIGURE_MORE"],
               "home":"STANDARD","desktop":true,"gaming":true,"mode":"standard","storageAtEntry":false}""",
        )
        val run = OnboardingRun()
        run.start(startStep = null, saved = saved)
        assertEquals(OnboardingStep.GAMES, run.step.value)
        // Three old pages collapse to two merged steps, each once.
        assertEquals(listOf(OnboardingStep.MODE, OnboardingStep.HOME), run.history.toList())
        assertEquals(Mode.GAMING, run.opensInto.value)
        assertEquals(true, run.alsoOther.value)
        assertEquals(HomeRolePrefs.HomeImplementation.STANDARD, run.homeChoice.value)
        assertEquals(true, run.homeShowsHomeScreen.value)
        assertEquals(Mode.LAUNCHER, run.defaultMode)
    }

    @Test
    fun `a saved run round-trips through its own json`() {
        val run = OnboardingRun()
        run.start(startStep = null, controllerAttached = false)
        run.opensInto.value = Mode.DESKTOP
        run.homeChoice.value = HomeRolePrefs.HomeImplementation.STANDARD
        run.step.value = OnboardingStep.DESKTOP_SETUP
        run.history.addAll(listOf(OnboardingStep.MODE, OnboardingStep.HOME))

        val again = OnboardingRun()
        again.start(startStep = null, saved = JSONObject(run.toJson()))
        assertEquals(OnboardingStep.DESKTOP_SETUP, again.step.value)
        assertEquals(listOf(OnboardingStep.MODE, OnboardingStep.HOME), again.history.toList())
        assertEquals(Mode.DESKTOP, again.opensInto.value)
        assertEquals(HomeRolePrefs.HomeImplementation.STANDARD, again.homeChoice.value)
        assertEquals(false, again.homeShowsHomeScreen.value)
        assertEquals(Mode.DESKTOP, again.defaultMode)
        assertEquals(false, again.controllerAttachedAtEntry)
    }
}
