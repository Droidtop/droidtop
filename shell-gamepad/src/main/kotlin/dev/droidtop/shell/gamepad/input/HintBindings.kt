package dev.droidtop.shell.gamepad.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.TouchHintBar

/**
 * One promise a hint row can make: [action] does [label] -- but only
 * while [bound] holds, because a hint row is this shell's touch control
 * surface and a hint that names an action nothing handles is a promise
 * the screen does not keep (docs/SPEC.md 7j, "a hint row promises only
 * what dispatches"; the 2026-09-24 UI pass, H8).
 *
 * The condition is a function, not a boolean captured at composition
 * time, so a row built from bindings re-reads it when the state it
 * depends on changes (which item is under the cursor, whether the list
 * it acts on is empty, whether a permission was granted) rather than
 * trusting the caller to recompose for it.
 */
class HintBinding(
    val action: GamepadAction,
    val label: String,
    val bound: () -> Boolean = { true },
)

/**
 * The bindings whose conditions hold right now, as the plain
 * action-and-label pairs [TouchHintBar] draws. One shared answer to
 * "what may this row promise", so per-screen rows stop hand-writing
 * their own filter/build blocks.
 */
fun activeHintPairs(bindings: List<HintBinding>): List<Pair<GamepadAction, String>> =
    bindings.filter { it.bound() }.map { it.action to it.label }

/**
 * [activeHintPairs] as composition state: the filter runs inside
 * [derivedStateOf], so a row that stays composed while a bound action's
 * condition flips (a notification arriving, a cursor moving off the
 * last row of an emptied list) re-reads the conditions without the
 * caller recomposing for it.
 */
@Composable
fun rememberHintList(bindings: List<HintBinding>): List<Pair<GamepadAction, String>> =
    remember(bindings) { derivedStateOf { activeHintPairs(bindings) } }.value

/**
 * A hint row built from [HintBinding]s: [TouchHintBar] over exactly the
 * actions that are bound on this screen right now, with the same
 * background/modifier shape every other row uses. The shell's own footer
 * ([ButtonHintFooter] in GamepadShell), the Quick Menu's Notifications
 * row, the Launcher's games row and the settings-host rows all draw
 * through this one component, so "only what dispatches" is decided once
 * instead of re-decided per screen.
 */
@Composable
fun HintRow(
    bindings: List<HintBinding>,
    modifier: Modifier = Modifier,
    background: Color = MenuTokens.HintBar,
) {
    TouchHintBar(
        hints = rememberHintList(bindings),
        modifier = modifier,
        background = background,
    )
}

/**
 * What the focused element says the footer should promise (docs/SPEC.md
 * 7j, "Filters, sort and the hint bar"). The shell owns one of these and
 * its ONE footer draws it: an element that has the focus declares its hints
 * with [declaresHints], and releases them when the focus leaves, so the bar
 * always names what a press does where the cursor is, and no screen draws a
 * row of its own. Only the focus owner's declaration shows; a release by an
 * element that no longer owns it is ignored, so two elements trading focus
 * in either order end with the right row.
 *
 * A modal layer (a sheet, a menu, a chooser) is a Compose `Dialog`, its own
 * window: the focus of the screen under it does not move, so focus alone
 * would keep the screen's hints on the bar. A layer says so with
 * [DeclareLayerHints]; while one is open the footer shows the TOPMOST layer's
 * hints and nothing else, whatever the screen underneath declares.
 */
class FocusedHints {
    private var owner: Any? = null

    private class Layer(val owner: Any, val bindings: List<HintBinding>)

    private val layers = mutableStateListOf<Layer>()

    /**
     * Whether any modal layer is open: the one list the hint bar and the
     * shell's audio rule (nothing plays beneath a layer, [dev.droidtop.runtime.AudioHandOff.setQuiet])
     * both read.
     */
    val layerOpen: Boolean get() = layers.isNotEmpty()

    /** The topmost open layer's hints, or null while no layer is open; an empty list means the layer draws its own bar. */
    val layerBindings: List<HintBinding>? get() = layers.lastOrNull()?.bindings

    internal fun pushLayer(owner: Any, bindings: List<HintBinding>) {
        val index = layers.indexOfFirst { it.owner === owner }
        when {
            index < 0 -> layers.add(Layer(owner, bindings))
            layers[index].bindings !== bindings -> layers[index] = Layer(owner, bindings)
        }
    }

    internal fun popLayer(owner: Any) {
        layers.removeAll { it.owner === owner }
    }

    /** The declared bindings, or null while no element that declares any has the focus. */
    var bindings by mutableStateOf<List<HintBinding>?>(null)
        private set

    internal fun declare(owner: Any, bindings: List<HintBinding>) {
        if (this.owner === owner && this.bindings === bindings) return
        this.owner = owner
        this.bindings = bindings
    }

    internal fun release(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            bindings = null
        }
    }
}

/** The shell's [FocusedHints]; null outside the shell, where [declaresHints] does nothing. */
val LocalFocusedHints = compositionLocalOf<FocusedHints?> { null }

/**
 * Declares [bindings] as the footer's hints while this element, or anything
 * inside it, has the focus. Pass a remembered list (rebuilt only when what it
 * says changes): a new list instance on every recomposition would republish
 * it on every recomposition.
 */
fun Modifier.declaresHints(bindings: List<HintBinding>): Modifier = composed {
    val host = LocalFocusedHints.current
    val token = remember { Any() }
    var focused by remember { mutableStateOf(false) }
    if (host != null) {
        SideEffect { if (focused) host.declare(token, bindings) }
        DisposableEffect(host) { onDispose { host.release(token) } }
    }
    Modifier.onFocusChanged {
        focused = it.hasFocus
        if (host != null) {
            if (it.hasFocus) host.declare(token, bindings) else host.release(token)
        }
    }
}

/**
 * Makes the `Dialog` this is called from a layer of its own on the hint bar:
 * while it is composed, the footer shows [bindings] (the panel's own A and B)
 * instead of the screen's underneath. An empty [bindings] hides the bar for a
 * layer that draws its own. Called once per modal surface, by [MenuPanel].
 */
@Composable
fun DeclareLayerHints(bindings: List<HintBinding>) {
    val host = LocalFocusedHints.current ?: return
    val token = remember { Any() }
    SideEffect { host.pushLayer(token, bindings) }
    DisposableEffect(host, token) { onDispose { host.popLayer(token) } }
}

/**
 * The shell's footer: the focused element's declared hints, or [fallback]
 * (what the screen means by a press when nothing declares), then the
 * [trailing] ones that hold everywhere. The ONE hint row of a screen the
 * shell draws; the declared list is read here, inside
 * this composable, so a change in it recomposes only the row.
 */
@Composable
fun FocusedHintRow(
    fallback: List<HintBinding>,
    modifier: Modifier = Modifier,
    background: Color = MenuTokens.HintBar,
    // What the shell means on every screen (Start is the left menu), so a
    // declaration names only what is the focused element's own.
    trailing: List<HintBinding> = emptyList(),
) {
    val host = LocalFocusedHints.current
    // A modal layer owns the bar while it is open: its hints alone, no
    // trailing ones (Start's menu does nothing behind a sheet).
    val layer = host?.layerBindings
    if (layer != null) {
        HintRow(bindings = layer, modifier = modifier, background = background)
        return
    }
    HintRow(bindings = (host?.bindings ?: fallback) + trailing, modifier = modifier, background = background)
}
