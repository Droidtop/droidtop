package dev.droidtop.shell.gamepad

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * The offer the Windows system-files download stops on before anything
 * is fetched (Droidtop/tracker#140): A on a Windows game whose
 * environment is not set up, and the game menu's same setup row, come
 * here first rather than silently starting a several-hundred-megabyte
 * download. Says what would be fetched -- Wine and the Windows base
 * system, several hundred megabytes -- so the choice is a real one, and
 * declines cleanly: B closes the offer and nothing is downloaded.
 *
 * Opened from [dev.droidtop.library.PcRunnerOptions.windowsSetupConsent],
 * which GamepadShell installs -- the same registered-hook shape
 * [dev.droidtop.library.LaunchDisplay.chooser] already uses for the
 * per-launch display question.
 */
@Composable
internal fun WindowsSetupOfferDialog(
    onDownload: () -> Unit,
    onNotNow: () -> Unit,
) {
    OfferDialog(
        title = stringResource(R.string.windows_setup_title),
        // No size: what is fetched depends on what the device already has
        // (Droidtop/tracker#301), and a figure that can be wrong is worse than none.
        body = "droidtop will download Wine and the Windows base system, " +
            "then set up the environment Windows games run in.",
        action = "Download now",
        decline = "Not now",
        focusLabel = "Windows setup offer",
        onAction = onDownload,
        onDecline = onNotNow,
    )
}
