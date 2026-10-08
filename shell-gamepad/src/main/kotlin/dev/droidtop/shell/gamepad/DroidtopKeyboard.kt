package dev.droidtop.shell.gamepad

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.droidtop.runtime.keyboard.AddonKeyboard
import dev.droidtop.runtime.keyboard.AddonKeyboardRules
import org.pocketworkstation.pckeyboard.KeyboardPanel
import org.pocketworkstation.pckeyboard.KeyboardSink
import org.pocketworkstation.pckeyboard.WindowKeySink

/** The share of its screen's height the keyboard takes where it shares the screen with other content. */
const val INLINE_KEYBOARD_HEIGHT_PERCENT = 35f

/**
 * droidtop's one keyboard ([KeyboardPanel]: the embedded Hacker's Keyboard grid) as a composable, for every
 * Compose surface that draws a keyboard of its own (docs/SPEC.md 4c, "Typing on the add-on display",
 * Droidtop/tracker#314). [sink] may change between compositions; the view keeps the one it was built with and
 * forwards to the latest.
 */
@Composable
fun DroidtopKeyboard(
    sink: KeyboardSink,
    modifier: Modifier = Modifier,
    heightPercent: Float = INLINE_KEYBOARD_HEIGHT_PERCENT,
    suppressImeView: Boolean = false,
) {
    val current by rememberUpdatedState(sink)
    val forwarding = remember {
        object : KeyboardSink {
            override val takesText: Boolean get() = current.takesText

            override fun key(androidKeyCode: Int, down: Boolean) = current.key(androidKeyCode, down)

            override fun text(chars: CharSequence) = current.text(chars)
        }
    }
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx -> KeyboardPanel(ctx, forwarding, heightPercent, suppressImeView) },
    )
}

/**
 * droidtop's keyboard under a droidtop text field, drawn only on a screen where Android draws no keyboard
 * (a secondary display droidtop has not set to show Android's own, [AddonKeyboardRules.ownFieldNeedsKeyboard]).
 * It types into whichever field of this window has focus, through the window's own key path ([WindowKeySink]),
 * so it needs no input method, no window focus and no permission.
 */
@Composable
fun OwnFieldKeyboard(modifier: Modifier = Modifier) {
    val view = LocalView.current
    val local by AddonKeyboard.localDisplays.collectAsState()
    var displayId by remember(view) { mutableStateOf<Int?>(null) }
    // Read once the window is attached: a dialog's view has no display during its first composition.
    LaunchedEffect(view) { displayId = view.display?.displayId }
    if (!AddonKeyboardRules.ownFieldNeedsKeyboard(displayId, local)) return
    // This window's field has its keyboard beside it: the window-wide one stays away.
    DisposableEffect(view) {
        val root = view.rootView
        InWindowKeyboard.ownFieldShown(root, true)
        onDispose { InWindowKeyboard.ownFieldShown(root, false) }
    }
    val sink = remember(view) { WindowKeySink { view.rootView } }
    // Wherever "Keyboard displays on" puts it (SPEC 4c): drawn here only when that is this screen.
    val setting by AddonKeyboard.placement.collectAsState()
    val hosts by AddonKeyboard.companionHostsKeyboard.collectAsState()
    val companions by KeyboardTargets.companionsChanged.collectAsState()
    val display = displayId ?: return
    var opened by remember(view) { mutableStateOf<KeyboardTargets.Opened?>(null) }
    DisposableEffect(sink, display, setting, hosts, companions) {
        val now = KeyboardTargets.open(display, sink)
        opened = now
        onDispose { (now as? KeyboardTargets.Opened.Elsewhere)?.let { KeyboardTargets.close(it.request) } }
    }
    if (opened == KeyboardTargets.Opened.Here) DroidtopKeyboard(sink, modifier.padding(top = 12.dp))
}
