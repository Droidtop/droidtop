package dev.droidtop.shell.gamepad

import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.GamepadAction
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * The one-time question after the library's first walk: "Fetch box art and
 * details for your games?" (docs/SPEC.md 7h, Droidtop/tracker#174). Raised by
 * [dev.droidtop.library.scraper.ScrapeOffer.pending]; either answer is stored
 * and ends the question for good, B declines. Same panel and pad handling as
 * every other modal of the shell (docs/SPEC.md 6e).
 */
@Composable
internal fun ScrapeOfferDialog(
    onFetch: () -> Unit,
    onNotNow: () -> Unit,
) {
    val rows = remember { listOf("Fetch now", "No thanks") }
    var selected by remember { mutableIntStateOf(0) }

    val window = LocalShellWindow.current
    Dialog(onDismissRequest = onNotNow) {
        GatePadInThisDialog()
        MenuPanel(
            modifier = Modifier.width(window.panelWidth(400.dp)),
            focusLabel = "Fetch box art offer",
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> selected = menuMove(selected, rows.size, press)
                    GamepadAction.A -> if (selected == 0) onFetch() else onNotNow()
                    GamepadAction.B -> onNotNow()
                    else -> Unit
                }
                true
            },
        ) {
            Text("Fetch box art and details for your games?", color = MenuTokens.OnSurface, style = MaterialTheme.typography.titleMedium)
            Text(
                "A confirms · B declines",
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            Text(
                "droidtop will look your games up online and download covers, descriptions and ratings. " +
                    "It runs in the background, can be paused under Downloads and installs, and carries on after a restart. " +
                    "You can change this later in Settings.",
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
                        .then(if (window.touchFirst) Modifier.heightIn(min = window.minTouchTarget) else Modifier)
                        .clip(RoundedCornerShape(8.dp))
                        .selectionFrame(index == selected, RoundedCornerShape(8.dp), rest = Color.Transparent)
                        .clickable { if (index == 0) onFetch() else onNotNow() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}
