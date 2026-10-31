package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
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
 * The modal layers of an in-game overlay, drawn in the overlay's own window ([OverlayLayerHost]). Over another app a
 * sub-window does not get the input focus: on the console the R2 Quick Menu over DuckStation was drawn in a focusable
 * Popup that the window manager named the focus while the input dispatcher listed no focused window at all, and
 * droidtop's leave-touch-mode step was refused for a window it did not own, so the D-pad did nothing
 * (Droidtop/tracker#820, dev.2778). The overlay window itself is focusable and focused, so every sheet lives in it.
 */
class OverlayLayers {
    internal class Layer(onDismiss: () -> Unit, content: @Composable () -> Unit, locals: CompositionLocalContext) {
        var onDismiss by mutableStateOf(onDismiss)
        var content by mutableStateOf(content)
        var locals by mutableStateOf(locals)
    }

    internal val layers = mutableStateListOf<Layer>()
}

private val LocalOverlayLayers = staticCompositionLocalOf<OverlayLayers?> { null }

/**
 * The root of an in-game overlay's window: [content], then each open sheet over it, newest on top, each over its
 * scrim and holding its own focus ([PadFocusHolder]). Back closes the top one. One window, so the pad reaches it.
 */
@Composable
fun OverlayLayerHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val host = remember { OverlayLayers() }
    CompositionLocalProvider(LocalOverlayLayers provides host) {
        Box(
            modifier = modifier.onPreviewKeyEvent { event ->
                val top = host.layers.lastOrNull()
                if (top != null && event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyUp) top.onDismiss()
                    true
                } else {
                    false
                }
            },
        ) {
            content()
            host.layers.forEach { layer ->
                key(layer) {
                    CompositionLocalProvider(layer.locals) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = OVERLAY_SCRIM_ALPHA))
                                // A tap on the scrim is the overlay's own business, never a press on what is under it.
                                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                            contentAlignment = Alignment.Center,
                        ) { PadFocusHolder(content = layer.content) }
                    }
                }
            }
        }
    }
}

/**
 * The shell's one modal window, every sheet, dialog and page the shell opens over itself. In droidtop's own windows it
 * is Compose's [Dialog]. Inside an in-game overlay ([LocalInGameOverlay]) a Dialog has no activity to belong to, and
 * its window would be refused over another app's, so it is a layer of the overlay's own window ([OverlayLayerHost]),
 * centred over a scrim as a dialog centres it. A focusable [Popup] is kept only for an overlay without a host.
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
    // In the overlay's own window when it has a host (every overlay does): a Popup is a sub-window, and over another app
    // it never gets the pad (Droidtop/tracker#820).
    LocalOverlayLayers.current?.let { host ->
        val locals = currentCompositionLocalContext
        val layer = remember { OverlayLayers.Layer(onDismissRequest, content, locals) }
        layer.onDismiss = onDismissRequest
        layer.content = content
        layer.locals = locals
        DisposableEffect(host, layer) {
            host.layers.add(layer)
            onDispose { host.layers.remove(layer) }
        }
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
