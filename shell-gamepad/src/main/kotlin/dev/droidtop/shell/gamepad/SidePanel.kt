package dev.droidtop.shell.gamepad

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HideSystemBarsInThisDialog
import kotlinx.coroutines.launch

/** The edge a [SidePanelFrame] slides in from. */
internal enum class PanelEdge { LEFT, RIGHT, BOTTOM }

// How dark the page behind goes. A plain scrim, not a blur: a real blur
// of a themed canvas with video and animation on it is a per-frame cost
// the handheld should not pay for a menu that is open for seconds.
private const val SIDE_PANEL_SCRIM_ALPHA = 0.55f

/** A panel width as a [fraction] of the screen, held between [min] and [max] and never past the screen. */
internal fun sidePanelWidth(screen: Dp, fraction: Float, min: Dp, max: Dp): Dp =
    (screen * fraction).coerceIn(min, max).coerceAtMost(screen)

/**
 * The one frame for the Gaming shell's side panels, the left menu and the
 * Quick Menu (docs/SPEC.md 7j, "Gaming controls"): a Compose [Dialog] on
 * purpose, so its window owns input while it is open and the shell underneath
 * needs no fencing; a dimmed page behind it (tap to close); the panel sliding in
 * from [edge] and sliding back out on close. [BOTTOM] is a full-width sheet at
 * most [bottomMaxHeight] of the screen tall (a screen held upright has no room
 * for a side panel); [LEFT] and [RIGHT] are full height and [panelWidth] wide.
 *
 * [content] draws inside the panel and is given the panel's width and the
 * `close` to call for every way out (B, Start, a hint-row tap, a tap on the page):
 * it slides the panel out first and calls [onDismiss] when it is gone. Leaving
 * for another menu is not a close, so it calls its own callback straight away.
 * Controller handling stays with the caller (`onPad(preview = true)` on its
 * root), because it needs the caller's own state.
 *
 * [floatMargin] above zero floats a side panel that far in from its edge,
 * the top and the bottom (Steam's floating side panels): it then takes the
 * panel radius ([Corners.Panel]), a hairline of the text ink at 5 percent
 * ([MenuTokens.PanelBorder]) and the menu shadow. The width [content] is
 * given is the panel's own, inside the margins.
 */
@Composable
internal fun SidePanelFrame(
    edge: PanelEdge,
    onDismiss: () -> Unit,
    panelWidth: (screen: Dp) -> Dp = { it },
    bottomMaxHeight: Float = 0.72f,
    floatMargin: Dp = 0.dp,
    content: @Composable (width: Dp, close: () -> Unit) -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                shown.animateTo(0f, Motion.panelOut())
                dismiss()
            }
        }
    }
    LaunchedEffect(Unit) { shown.animateTo(1f, Motion.panelIn()) }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        GatePadInThisDialog()
        HideSystemBarsInThisDialog()
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // The dimmed page: tapping it closes, as B does.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = shown.value }
                    .background(MenuTokens.Scrim.copy(alpha = SIDE_PANEL_SCRIM_ALPHA))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
            )
            val floating = edge != PanelEdge.BOTTOM && floatMargin > 0.dp
            val margin = if (floating) floatMargin else 0.dp
            val width = if (edge == PanelEdge.BOTTOM) maxWidth else panelWidth(maxWidth) - margin * 2
            Surface(
                // The shell's own overlay surface, not the platform's colour scheme: every token
                // the panels draw with is defined against it (a light device state once gave a
                // white panel with white-on-white labels).
                color = MenuTokens.OverlaySurface,
                tonalElevation = 0.dp,
                shape = if (floating) Corners.Panel else androidx.compose.ui.graphics.RectangleShape,
                border = if (floating) androidx.compose.foundation.BorderStroke(1.dp, MenuTokens.PanelBorder) else null,
                shadowElevation = if (floating) Elevation.Menu else 0.dp,
                modifier = Modifier
                    .padding(margin)
                    .then(
                        if (edge == PanelEdge.BOTTOM) {
                            Modifier.fillMaxWidth().heightIn(max = maxHeight * bottomMaxHeight)
                        } else {
                            Modifier.fillMaxHeight().width(width)
                        },
                    )
                    .align(
                        when (edge) {
                            PanelEdge.LEFT -> Alignment.CenterStart
                            PanelEdge.RIGHT -> Alignment.CenterEnd
                            PanelEdge.BOTTOM -> Alignment.BottomCenter
                        },
                    )
                    .graphicsLayer {
                        // Out past its own margin too, so a floating panel leaves the screen whole.
                        val away = 1f - shown.value
                        val gap = margin.toPx()
                        when (edge) {
                            PanelEdge.LEFT -> translationX = -away * (size.width + gap)
                            PanelEdge.RIGHT -> translationX = away * (size.width + gap)
                            PanelEdge.BOTTOM -> translationY = away * size.height
                        }
                    },
            ) {
                // The panel's own sliding focus ring, inside the surface so it travels with it.
                FocusGlideHost { content(width, close) }
            }
        }
    }
}
