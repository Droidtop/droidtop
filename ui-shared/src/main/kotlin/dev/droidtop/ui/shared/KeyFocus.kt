package dev.droidtop.ui.shared

import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Keeps a panel that reads the pad with `onPreviewKeyEvent` reachable (Droidtop/tracker#747). Key events go only to
 * the focused element and its ancestors, so a panel whose one focusable child is a text field went deaf the moment the
 * field gave its focus up: Back hiding the on-screen keyboard clears the field's focus, and after that D-pad Up and A
 * did nothing on the console while a tap still worked. With this the panel is a focus target of its own, under its
 * key handler, and whenever nothing inside it holds focus it takes focus back, a frame later, without raising a
 * keyboard (a panel is not a text field). Put it AFTER the key handler in the modifier chain, so the handler is the
 * focus target's ancestor. [enabled] false lets focus go (a page opened over the panel).
 */
fun Modifier.holdKeyFocus(requester: FocusRequester, enabled: Boolean = true): Modifier = composed {
    var within by remember { mutableStateOf(false) }
    LaunchedEffect(within, enabled) {
        if (within || !enabled) return@LaunchedEffect
        // The change that cleared focus may hand it to a child on the same frame (a row the cursor moved to, the
        // field being refocused on purpose); take it back only if nothing has it a frame later.
        withFrameNanos { }
        if (!within) runCatching { requester.requestFocus() }
    }
    this
        .onFocusChanged { within = it.hasFocus }
        .focusRequester(requester)
        .focusable()
}
