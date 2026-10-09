package dev.droidtop.shell.gamepad

import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.shell.gamepad.input.ControllerLayouts

/**
 * "Press the button labelled A": the capture that stands in for asking which
 * face buttons are Nintendo-style (docs/SPEC.md 7b, "Console and controller
 * detection"). Raised from the Quick Menu's "Controller buttons" tile, and
 * lightly on its own when the active pad's earlier answer no longer holds
 * and nothing else says what it is ([light]).
 *
 * The press is read as the raw key code BEFORE the layout's own swap
 * ([ControllerLayouts.handleCaptureKey], on the panel's preview chain so the
 * panel's own B-means-back never sees it), because the whole point is to
 * learn what that button arrives as. Touch can dismiss it ("Not now"), and
 * so can the system back key.
 */
@Composable
internal fun PadCapturePrompt(light: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val window = LocalShellWindow.current
    val pad = ControllerLayouts.activePadName
    Dialog(onDismissRequest = onDone) {
        GatePadInThisDialog()
        MenuPanel(
            modifier = Modifier
                .width(window.panelWidth(420.dp))
                .onPreviewKeyEvent { event ->
                    ControllerLayouts.handleCaptureKey(context, event.nativeKeyEvent, onDone)
                },
            focusLabel = "Controller buttons",
            hints = emptyList(),
            onPad = { true },
        ) {
            Text(
                if (light) "Your controller changed" else "Press the button labelled A",
                color = MenuTokens.OnSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                (if (light) "Press the button labelled A again so droidtop keeps confirm and cancel on the right buttons. " else "") +
                    (pad?.let { "Using $it. " } ?: "") +
                    "Not sure? Touch Not now and droidtop keeps its best guess.",
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Text(
                "Not now",
                color = MenuTokens.Value,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = window.minTouchTarget)
                    .clip(RoundedCornerShape(8.dp))
                    .selectionFrame(false, RoundedCornerShape(8.dp), rest = Color.Transparent)
                    .clickable {
                        ControllerLayouts.dismissRecapture()
                        onDone()
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
