package dev.droidtop.shell.gamepad

import androidx.compose.runtime.withFrameNanos

/**
 * Requests focus once the [androidx.compose.ui.focus.FocusRequester]'s
 * node actually exists. A themed view composes its list widget through
 * several nested layout passes, so the focusable node attaches a LATER
 * frame than a LaunchedEffect's first dispatch, and a single request on
 * that first dispatch leaves the screen with no focus at all (seen on
 * device via logcat). Retries across frames until the node exists; gives
 * up quietly after ~1s of frames rather than looping forever on a theme
 * whose view never attaches one (ES-DWEE's widgetless system view).
 *
 * The Compose droidtop ships (1.9) does not throw for an unattached
 * requester: requestFocus() prints a warning and does nothing, and only
 * requestFocus(FocusDirection) says whether focus was taken. Success is
 * what Compose returns, never "did not throw", so the retries continue
 * until a dialog's field exists (console, 0.2.0-dev.2127).
 */
suspend fun requestFocusWhenAttached(
    focusRequester: androidx.compose.ui.focus.FocusRequester,
    tag: String,
) {
    val taken = retryEachFrame(
        attempts = FOCUS_ATTEMPTS,
        attempt = { runCatching { focusRequester.requestFocus(androidx.compose.ui.focus.FocusDirection.Enter) }.getOrDefault(false) },
        nextFrame = { withFrameNanos {} },
    )
    if (!taken) {
        android.util.Log.w("droidtop.GamepadShell", "$tag focus never attached after $FOCUS_ATTEMPTS frames")
    }
}

/** Runs [attempt] once a frame until it returns true, at most [attempts] times; whether it ever did. Pure. */
suspend fun retryEachFrame(attempts: Int, attempt: () -> Boolean, nextFrame: suspend () -> Unit): Boolean {
    repeat(attempts) { i ->
        if (attempt()) return true
        if (i < attempts - 1) nextFrame()
    }
    return false
}

internal const val FOCUS_ATTEMPTS = 60
