package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * True while the shell's pieces are drawn in an in-game overlay (docs/SPEC.md "In-game overlays: HOME chords",
 * Droidtop/tracker#740): a window over another app, not an activity of droidtop's own.
 */
val LocalInGameOverlay = staticCompositionLocalOf { false }

/**
 * The shell's one modal window, every sheet, dialog and page the shell opens over itself. In droidtop's own windows it
 * is Compose's [Dialog]. Inside an in-game overlay ([LocalInGameOverlay]) a Dialog has no activity to belong to, and
 * its window would be refused over another app's, so it is a focusable [Popup] filling the overlay's window instead:
 * a sub-window of the overlay, which any window may have. Back and the pad reach it the same way; the content is the
 * same, centred as a dialog centres it, over a scrim.
 */
@Composable
fun ShellDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    if (!LocalInGameOverlay.current) {
        // A dialog is a window of its own: it holds its own focus (PadFocusHolder, Droidtop/tracker#820).
        Dialog(onDismissRequest = onDismissRequest, properties = properties) { PadFocusHolder(content = content) }
        return
    }
    Popup(
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = properties.dismissOnBackPress,
            // A tap on the scrim is the overlay's own business (closing the whole overlay), never a stray dismiss.
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = OVERLAY_SCRIM_ALPHA)),
            contentAlignment = Alignment.Center,
        ) { PadFocusHolder(content = content) }
    }
}

/** How dark a sheet's scrim is over a running game: the platform dialog's dim, near enough. */
private const val OVERLAY_SCRIM_ALPHA = 0.5f
