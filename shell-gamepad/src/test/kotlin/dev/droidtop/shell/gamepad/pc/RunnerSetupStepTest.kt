package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.GameLaunchStrategy
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.ResolvedRunner
import dev.droidtop.library.RunnerAction
import dev.droidtop.library.RunnerOption
import dev.droidtop.library.RunnerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The game page's primary button runs a runner's setup step itself, and says how it is going. */
class RunnerSetupStepTest {
    private val entry = LibraryEntry(id = "folder:a", title = "A", kind = LibraryEntryKind.WINE_PROFILE)

    private fun runner(state: RunnerState, action: RunnerAction?) =
        ResolvedRunner(RunnerOption(GameLaunchStrategy.LINUX_CONTAINER, state, "why", action), "why", "Linux container")

    private fun play(entry: LibraryEntry, runner: ResolvedRunner) = playStateOf(runner, entry)

    @Test
    fun aNeedsSetupRunnerGivesItsStep() {
        val r = runner(RunnerState.NEEDS_SETUP, RunnerAction.SET_UP_LINUX_GAMES)
        assertEquals(RunnerAction.SET_UP_LINUX_GAMES, runnerSetupStep(play(entry, r), entry, r))
    }

    @Test
    fun aReadyRunnerOrNoRunnerIsALaunchNotAStep() {
        val ready = runner(RunnerState.READY, null)
        assertNull(runnerSetupStep(play(entry, ready), entry, ready))
        assertNull(runnerSetupStep(PcPlayStateLoading, entry, null))
        // The Engine picker is its own button.
        assertNull(runnerSetupStep(playStateOf(null, entry), entry, null))
    }

    @Test
    fun theRunningLineReplacesTheDetailAndStopsASecondPress() {
        val r = runner(RunnerState.NEEDS_SETUP, RunnerAction.SET_UP_LINUX_GAMES)
        val base = play(entry, r)
        assertEquals(base, base.withSetupLine(null, running = false))
        val running = base.withSetupLine("Installing FEX", running = true)
        assertEquals("Installing FEX", running.detail)
        assertFalse(running.pressable)
        assertEquals(base.verb, running.verb)
        // A failure leaves the button pressable to try again.
        val failed = base.withSetupLine("Start Desktop mode first", running = false)
        assertEquals("Start Desktop mode first", failed.detail)
        assertEquals(base.pressable, failed.pressable)
    }
}
