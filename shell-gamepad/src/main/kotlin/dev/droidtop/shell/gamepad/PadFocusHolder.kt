package dev.droidtop.shell.gamepad

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Focus is always somewhere in a pad window (docs/SPEC.md 7j, "Every screen opens with a focused item";
 * Droidtop/tracker#820). With nothing focused, the D-pad has no node to move from and every press is dropped
 * (console, build 2730: "I have to hit A for it to work"), so this holder, around the content of each pad window
 * (the shell, a [ShellDialog], an in-game overlay), puts the focus on the first focusable item whenever nothing in it
 * holds focus: when it opens, when a tab, pane or page change removes the focused node, and on a return to the window.
 * A screen that places its own focus (the last-focused row, a restored cursor) does so within the first frames and
 * this does nothing. It only moves the focus, never presses anything, so nothing acts until A.
 */
@Composable
fun PadFocusHolder(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val focusManager = LocalFocusManager.current
    var inside by remember { mutableStateOf(false) }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    LaunchedEffect(inside, resumes) {
        if (inside) return@LaunchedEffect
        var frames = 0
        while (!inside) {
            withFrameNanos { }
            frames++
            // The screen's own placement first; then a try every few frames until something focusable is there (a
            // list still loading has none yet).
            if (frames >= PadFocusRules.SETTLE_FRAMES && PadFocusRules.tryOn(frames)) {
                runCatching { focusManager.moveFocus(FocusDirection.Next) }
            }
        }
    }
    Box(modifier.onFocusChanged { inside = it.hasFocus }) { content() }
}

/** When the holder tries to place the focus. Pure. */
internal object PadFocusRules {
    /** Frames a screen has to place its own focus before the holder does: about 100 ms. */
    const val SETTLE_FRAMES = 6

    /** After that, a try every few frames, so a list that is still loading is focused as soon as it is drawn. */
    const val RETRY_EVERY_FRAMES = 6

    fun tryOn(frame: Int): Boolean = frame >= SETTLE_FRAMES && (frame - SETTLE_FRAMES) % RETRY_EVERY_FRAMES == 0
}
