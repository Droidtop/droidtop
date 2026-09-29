package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

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
    val rows = remember { listOf("Download now", "Not now") }
    var selected by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, "Windows setup offer") }

    val window = LocalShellWindow.current
    Dialog(onDismissRequest = onNotNow) {
        Column(
            Modifier
                .width(window.panelWidth(400.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(MenuTokens.OverlaySurface)
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            selected = (selected + 1).coerceAtMost(rows.lastIndex)
                            true
                        }
                        Key.DirectionUp -> {
                            selected = (selected - 1).coerceAtLeast(0)
                            true
                        }
                        Key.ButtonA, Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                            if (selected == 0) onDownload() else onNotNow()
                            true
                        }
                        Key.ButtonB, Key.Back, Key.Escape -> {
                            onNotNow()
                            true
                        }
                        else -> false
                    }
                }
                .padding(20.dp),
        ) {
            Text("Windows games need a one-time download", color = MenuTokens.OnSurface, style = MaterialTheme.typography.titleMedium)
            Text(
                "A confirms · B postpones",
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            Text(
                "droidtop will download Wine and the Windows base system, " +
                    "several hundred megabytes, then set up the environment Windows games run in.",
                color = MenuTokens.Value,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            rows.forEachIndexed { index, row ->
                Text(
                    row,
                    color = if (index == selected) MenuTokens.OnSurface else MenuTokens.Value,
                    fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        // A row is a button here, so it is at least as
                        // big as a finger on a screen without a pad.
                        .then(if (window.touchFirst) Modifier.heightIn(min = window.minTouchTarget) else Modifier)
                        .clip(RoundedCornerShape(8.dp))
                        .selectionFrame(index == selected, RoundedCornerShape(8.dp), rest = Color.Transparent)
                        .clickable { if (index == 0) onDownload() else onNotNow() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}
