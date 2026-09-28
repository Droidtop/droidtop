package dev.droidtop.shell.gamepad.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
