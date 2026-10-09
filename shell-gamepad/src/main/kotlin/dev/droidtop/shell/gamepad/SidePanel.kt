package dev.droidtop.shell.gamepad

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HideSystemBarsInThisDialog
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import dev.droidtop.shell.gamepad.theme.UiSound
import kotlinx.coroutines.launch

/** The edge a [SidePanelFrame] slides in from. */
internal enum class PanelEdge { LEFT, RIGHT, BOTTOM }

/** A panel width as a [fraction] of the screen, held between [min] and [max] and never past the screen. */
internal fun sidePanelWidth(screen: Dp, fraction: Float, min: Dp, max: Dp): Dp =
    (screen * fraction).coerceIn(min, max).coerceAtMost(screen)

/**
 * The one frame for the Gaming shell's side panels, the left menu and the
 * Quick Menu (docs/SPEC.md 7j, "Gaming controls"): a Compose [Dialog] on
 * purpose, so its window owns input while it is open and the shell underneath
 * needs no fencing; a dimmed page behind it (tap to close; [scrim] says how
 * dark); the panel sliding in from [edge] and sliding back out on close.
 * [BOTTOM] is a full-width sheet at most [bottomMaxHeight] of the screen tall
 * (a screen held upright has no room for a side panel); [LEFT] and [RIGHT] are
 * full height and [panelWidth] wide.
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
 *
 * [topInset] and [footer] are the other Steam shape, its main menu's: the
 * panel stays flush with its edge but starts [topInset] below the top and
 * ends where [footer] begins, a strip across the whole window under the
 * dimmed page that holds the menu's own hint row (Steam draws the main
 * menu's legend in the footer's place, not inside the panel). The footer
 * fades with the panel; it is not part of the panel's slide.
 */
@Composable
internal fun SidePanelFrame(
    edge: PanelEdge,
    onDismiss: () -> Unit,
    panelWidth: (screen: Dp) -> Dp = { it },
    bottomMaxHeight: Float = 0.72f,
    floatMargin: Dp = 0.dp,
    scrim: Color = MenuTokens.Scrim.copy(alpha = MenuTokens.QuickPanelScrimAlpha),
    topInset: Dp = 0.dp,
    footer: (@Composable (close: () -> Unit) -> Unit)? = null,
    content: @Composable (width: Dp, close: () -> Unit) -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            EsDeNavigationSounds.play(UiSound.PANEL_CLOSE)
            scope.launch {
                shown.animateTo(0f, Motion.panelOut())
                dismiss()
            }
        }
    }
    // Each side menu opens and closes with its own cue (Steam's side-menu sounds, docs/SPEC.md
    // "Interface sounds"); swapping to the other menu is that menu opening.
    LaunchedEffect(Unit) {
        EsDeNavigationSounds.play(UiSound.PANEL_OPEN)
        shown.animateTo(1f, Motion.panelIn())
    }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        GatePadInThisDialog()
        HideSystemBarsInThisDialog()
        Box(modifier = Modifier.fillMaxSize()) {
            // The dimmed page: tapping it closes, as B does.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = shown.value }
                    .background(scrim)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
            )
            Column(Modifier.fillMaxSize()) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = if (edge == PanelEdge.BOTTOM) 0.dp else topInset),
                ) {
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
                        // A panel that stops short of the window's edges floats over the page.
                        shadowElevation = if (floating || topInset > 0.dp || footer != null) Elevation.Menu else 0.dp,
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
                if (footer != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = topInset)
                            .graphicsLayer { alpha = shown.value }
                            // Opaque, on the panel's own surface: the page's hint row sits in this same strip,
                            // and through a see-through footer its pills showed under the menu's (rig, build
                            // 1649: "ENU", "ILTER" fragments between A Open and B Close).
                            .background(MenuTokens.OverlaySurface),
                        contentAlignment = Alignment.Center,
                    ) { footer(close) }
                }
            }
        }
    }
}
