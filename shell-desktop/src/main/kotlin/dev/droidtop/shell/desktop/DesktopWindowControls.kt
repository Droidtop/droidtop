package dev.droidtop.shell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.droidtop.hostbridge.ControlButton
import dev.droidtop.hostbridge.DesktopWindows
import dev.droidtop.hostbridge.SwayFrames
import dev.droidtop.hostbridge.WindowButton
import dev.droidtop.hostbridge.WindowControls
import dev.droidtop.input.DesktopInputRouter
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.currentShellWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Close, minimise and maximise on the title bars of sway's windows, and a finger-sized resize band around them
 * (docs/SPEC.md 2b "Window controls", Droidtop/tracker#910), over one surface showing the sway output [outputName]
 * ([primary]: the desktop's own surface, whose output may still be unnamed with one output).
 *
 * Drawn from droidtop's side, over the surface, from where sway's tree puts each window ([DesktopWindows.frames], read
 * off the main thread); sway itself is not changed. The press is the [router]'s: it takes a press on a button or in the
 * band before the compositor sees it, and every other press goes on as before ([DesktopInputRouter.windowControls]).
 * Nothing is drawn under labwc, which draws its own title-bar buttons, nor with no session.
 */
@Composable
internal fun BoxScope.WindowControlsOverlay(
    windows: DesktopWindows?,
    router: DesktopInputRouter,
    outputName: String?,
    primary: Boolean,
) {
    val none = remember { MutableStateFlow(SwayFrames.NONE) }
    val frames by (windows?.frames ?: none).collectAsState()
    val noPlaced = remember { MutableStateFlow(emptyMap<Long, dev.droidtop.hostbridge.SwayWindow>()) }
    val placed by (windows?.placed ?: noPlaced).collectAsState()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val targetPx = with(density) { currentShellWindow().windowControlTarget.toPx() }
    // A second surface with no output yet shows nothing; only the primary may stand for the one unnamed output.
    val surfaceOutput = if (primary) outputName else outputName?.takeIf { it.isNotEmpty() }
    val controls = remember(frames, surfaceOutput, size, targetPx) {
        if (!primary && surfaceOutput == null) {
            WindowControls.NONE
        } else {
            WindowControls.layout(frames, surfaceOutput, size.width, size.height, targetPx)
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentWindows by rememberUpdatedState(windows)
    SideEffect { router.windowControls = controls }
    DisposableEffect(router) {
        router.onWindowButton = { button: ControlButton ->
            val actions = currentWindows
            if (actions != null) {
                scope.launch {
                    val done = when (button.kind) {
                        WindowButton.CLOSE -> actions.close(button.window)
                        WindowButton.MINIMIZE -> actions.minimize(button.window)
                        WindowButton.MAXIMIZE -> actions.toggleMaximize(button.window)
                    }
                    if (!done) {
                        dev.droidtop.library.settings.Notices.flash(
                            context.getString(R.string.desktop_window_action_failed),
                            tone = dev.droidtop.library.settings.NoticeTone.Caution,
                            context = context,
                        )
                    }
                }
            }
        }
        router.onPointerReleased = { currentWindows?.refreshFrames() }
        onDispose {
            router.windowControls = WindowControls.NONE
            router.onWindowButton = null
            router.onPointerReleased = null
        }
    }

    Box(modifier = Modifier.matchParentSize().onSizeChanged { size = it }.clearAndSetSemantics {}) {
        for (button in controls.buttons) {
            val visual = button.visual
            val side = with(density) { visual.height.toDp() }
            val glyph = when (button.kind) {
                WindowButton.MINIMIZE -> "–"
                WindowButton.MAXIMIZE -> if (windows?.isPlaced(button.window.conId, placed) == true) "❐" else "□"
                WindowButton.CLOSE -> "✕"
            }
            Box(
                modifier = Modifier
                    .offset { IntOffset(visual.left.roundToInt(), visual.top.roundToInt()) }
                    .size(side)
                    .background(if (button.window.focused) MenuTokens.CardFocused else MenuTokens.Card, MenuTokens.RowShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    glyph,
                    color = MenuTokens.OnSurface,
                    fontSize = with(density) { (visual.height * GLYPH_SHARE).toSp() },
                    maxLines = 1,
                )
            }
        }
    }
}

/** How much of a button's height its glyph takes. */
private const val GLYPH_SHARE = 0.6f
