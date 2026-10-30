package dev.droidtop.shell.gamepad

import android.view.View
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
import androidx.compose.ui.focus.focusRequester
import dev.droidtop.shell.gamepad.input.onPad
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import dev.droidtop.runtime.keyboard.AddonKeyboard
import org.pocketworkstation.pckeyboard.KeyboardPanel
import org.pocketworkstation.pckeyboard.KeyboardSink
import org.pocketworkstation.pckeyboard.WindowKeySink
import java.util.WeakHashMap

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
 * droidtop's keyboard for a droidtop text field, wherever droidtop's keyboard serves it ([KeyboardTargets.servesField]:
 * a screen where Android draws no keyboard, or any screen while a companion takes the typing, Droidtop/tracker#612).
 * With a companion the keys are typed on its Keys panel and Android's keyboard is kept away from this screen.
 * It types into whichever field of this window has focus, through the window's own key path ([WindowKeySink]),
 * so it needs no input method, no window focus and no permission. [active] is whether the person is typing in the
 * field now: a page whose cursor left the field for its rows passes false, and the keyboard closes wherever it was
 * shown (the companion's stayed up over search results, Droidtop/tracker#485 rig step 1), coming back with the cursor.
 */
@Composable
fun OwnFieldKeyboard(modifier: Modifier = Modifier, active: Boolean = true) {
    val view = LocalView.current
    val local by AddonKeyboard.localDisplays.collectAsState()
    var displayId by remember(view) { mutableStateOf<Int?>(null) }
    // Read once the window is attached: a dialog's view has no display during its first composition.
    LaunchedEffect(view) { displayId = view.display?.displayId }
    val setting by AddonKeyboard.placement.collectAsState()
    val hosts by AddonKeyboard.companionHostsKeyboard.collectAsState()
    val companions by KeyboardTargets.companionsChanged.collectAsState()
    // Decided again when a companion comes or goes, or the setting or Android's own keyboards change.
    val serves = remember(displayId, local, setting, hosts, companions) {
        KeyboardTargets.servesField(displayId, KeyboardTargets.assistiveOn(view.context))
    }
    if (!serves) return
    // This window's field has its keyboard beside it: the window-wide one stays away.
    DisposableEffect(view) {
        val root = view.rootView
        OwnFieldWindows.shown(root, true)
        onDispose { OwnFieldWindows.shown(root, false) }
    }
    val sink = remember(view) { WindowKeySink { view.rootView } }
    // Wherever "Keyboard displays on" puts it (SPEC 4c): drawn here only when that is this screen.
    val display = displayId ?: return
    var opened by remember(view) { mutableStateOf<KeyboardTargets.Opened?>(null) }
    DisposableEffect(sink, display, setting, hosts, companions, active) {
        if (!active) {
            opened = null
            return@DisposableEffect onDispose {}
        }
        val now = KeyboardTargets.open(display, sink, assistive = KeyboardTargets.assistiveOn(view.context))
        opened = now
        onDispose { (now as? KeyboardTargets.Opened.Elsewhere)?.let { KeyboardTargets.close(it.request) } }
    }
    if (opened == KeyboardTargets.Opened.Here) DroidtopKeyboard(sink, modifier.padding(top = 12.dp))
    // The keys are served on another screen: Android's own keyboard stays away from this one (#523).
    if (opened is KeyboardTargets.Opened.Elsewhere) {
        LaunchedEffect(view) {
            while (true) {
                KeyboardTargets.hideAndroidKeyboard(view)
                delay(KEYBOARD_GUARD_MS)
            }
        }
    }
}

private const val KEYBOARD_GUARD_MS = 150L

/**
 * Root views with an [OwnFieldKeyboard] composed in them, counted, so the window-wide keyboard
 * (`InWindowKeyboard`, :shell-gamepad) draws no second one there. Weak: a gone window is forgotten. Main thread only.
 */
object OwnFieldWindows {
    private val roots = WeakHashMap<View, Int>()

    internal fun shown(root: View, shown: Boolean) {
        val count = (roots[root] ?: 0) + if (shown) 1 else -1
        if (count <= 0) roots.remove(root) else roots[root] = count
    }

    /** Whether [root] has a field drawing its own keyboard beside it. */
    fun has(root: View): Boolean = (roots[root] ?: 0) > 0
}

/**
 * A dialog's one text field, holding the cursor from the moment the dialog opens (Droidtop/tracker#523): keys from any
 * keyboard, the companion's included (it types into the focused field, [OwnFieldKeyboard]), land in it with no press
 * to reach it first. Android's keyboard comes up with that focus only where droidtop raises one on its own
 * ([KeyboardTargets.raisesOnOwnFocus]: one screen, no controller or keyboard); pass [raisesKeyboardWithFocus] as the field's
 * KeyboardOptions. Otherwise A on the field brings it up, where droidtop's own keyboard does not serve the screen.
 * [tag] names the field in the log when focus never attaches.
 */
@Composable
fun Modifier.opensFocused(tag: String): Modifier {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val view = LocalView.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    KeepKeyboardDownOnFocus()
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, tag) }
    return this
        .onPad { press ->
            if (press.action == dev.droidtop.shell.gamepad.input.GamepadAction.A && !KeyboardTargets.servesField(view.display?.displayId)) {
                keyboard?.show()
                true
            } else {
                false
            }
        }
        .focusRequester(focus)
}

/** Whether a field that takes the cursor on its own also brings Android's keyboard up ([opensFocused]). */
val raisesKeyboardWithFocus: Boolean get() = KeyboardTargets.raisesOnOwnFocus()

/**
 * Keeps Android's keyboard from coming up by itself when this window takes focus with a field in it, unless droidtop's
 * own rule raises one ([raisesKeyboardWithFocus]). A window that leaves its soft input state unspecified gets the system
 * keyboard on a large screen as soon as a text field in it is focused, whatever Compose asks for: on the console Y
 * opened Search and Gboard came up over Gaming although the field asked for none (Droidtop/tracker#832, dev.2778).
 * Only the state bits change, never adjustResize. An explicit show (A or a tap on the field) still raises it.
 */
@Composable
fun KeepKeyboardDownOnFocus() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            ?: (view.context as? android.app.Activity)?.window
        val before = window?.attributes?.softInputMode
        if (window != null && before != null && !raisesKeyboardWithFocus) {
            val state = android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE
            window.setSoftInputMode((before and state.inv()) or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        onDispose { if (window != null && before != null) window.setSoftInputMode(before) }
    }
}
