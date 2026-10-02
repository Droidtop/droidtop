package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.RunnerAction
import org.junit.Assert.assertEquals
import org.junit.Test

class PcPrimaryActionLabelTest {
    @Test
    fun labelsTheNextSetupStep() {
        assertEquals("Install", primaryActionLabel(RunnerAction.INSTALL_ENGINEHOST))
        assertEquals("Install", primaryActionLabel(RunnerAction.INSTALL_ENGINEHOST_PLUGIN))
        assertEquals("Install", primaryActionLabel(RunnerAction.INSTALL_KIRIKIROID2))
        assertEquals("Set up Windows games", primaryActionLabel(RunnerAction.SET_UP_WINDOWS_GAMES))
        assertEquals("Choose a runner", primaryActionLabel(RunnerAction.CHOOSE_ENGINE_VERSION))
        assertEquals("Choose a runner", primaryActionLabel(null))
    }
}
