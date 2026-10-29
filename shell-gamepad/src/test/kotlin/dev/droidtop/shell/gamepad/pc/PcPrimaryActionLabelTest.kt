package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.GameLaunchStrategy
import dev.droidtop.library.RunnerAction
import dev.droidtop.library.RunnerOption
import dev.droidtop.library.RunnerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PcPrimaryActionLabelTest {
    private fun option(action: RunnerAction?, reason: String? = null, label: String? = null) =
        RunnerOption(GameLaunchStrategy.ENGINEHOST, RunnerState.NEEDS_SETUP, reason, action, actionLabel = label)

    @Test
    fun labelsTheNextSetupStep() {
        assertEquals("Get Ren'Py support", primaryActionLabel(option(RunnerAction.INSTALL_ENGINEHOST_PLUGIN, label = "Get Ren'Py support")))
        assertEquals("Install Enginehost", primaryActionLabel(option(RunnerAction.INSTALL_ENGINEHOST)))
        assertEquals("Install Kirikiroid2", primaryActionLabel(option(RunnerAction.INSTALL_KIRIKIROID2)))
        assertEquals("Set up Windows games", primaryActionLabel(option(RunnerAction.SET_UP_WINDOWS_GAMES)))
        assertEquals("Choose a runner", primaryActionLabel(option(RunnerAction.CHOOSE_ENGINE_VERSION)))
        assertEquals("Choose a runner", primaryActionLabel(option(null)))
    }

    @Test
    fun aRunnersInstallNeverReadsAsTheGamesOwnInstall() {
        // The game's own Install is the installer row's (Droidtop/tracker#657); no runner step may share its words.
        for (action in RunnerAction.entries) assertFalse(action.name, primaryActionLabel(option(action)) == "Install")
    }

    @Test
    fun theLineUnderTheButtonSaysWhichRunnerAndWhy() {
        assertEquals(
            "Install the Ren'Py plugin for Enginehost, then this becomes Play",
            setupLine(option(RunnerAction.INSTALL_ENGINEHOST_PLUGIN, "Install the Ren'Py plugin", "Get Ren'Py support"), "Enginehost"),
        )
        assertEquals(
            "Enginehost runs this game. One step, then this becomes Play",
            setupLine(option(RunnerAction.INSTALL_ENGINEHOST, "Install Enginehost", "Install Enginehost"), "Enginehost"),
        )
    }
}
