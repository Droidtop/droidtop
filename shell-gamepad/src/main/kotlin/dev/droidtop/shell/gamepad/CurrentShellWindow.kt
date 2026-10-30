package dev.droidtop.shell.gamepad

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import dev.droidtop.shell.gamepad.input.ControllerLayouts

/**
 * Reads the live configuration and whether a pad is attached ([ControllerLayouts.padPresent]). Recomposes on
 * rotation, which is what makes one screen serve both orientations, and when a pad comes or goes.
 */
@Composable
fun currentShellWindow(): ShellWindow {
    val configuration = LocalConfiguration.current
    val padPresent by ControllerLayouts.padPresent.collectAsState()
    return ShellWindow(
        widthDp = configuration.screenWidthDp,
        heightDp = configuration.screenHeightDp,
        padPresent = padPresent,
        minTouchTarget = LocalTouchTarget.current,
    )
}
