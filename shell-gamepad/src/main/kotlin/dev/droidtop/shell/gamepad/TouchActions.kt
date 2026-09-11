package dev.droidtop.shell.gamepad

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import kotlin.math.abs

/**
 * Touch parity without a second copy of the shell's behaviour.
 *
 * Every screen in this shell decides what a press MEANS in one
 * `onKeyEvent` block, close to the state that press acts on. Giving
 * touch its own callbacks would mean writing each of those decisions
 * twice and letting the two drift. So a touch affordance dispatches the
 * real key event instead: [rememberGamepadTouch] hands back a function
 * that sends a genuine [KeyEvent] down the focused window, through
 * exactly the same `GamepadKeyMap.actionFor` / `onKeyEvent` chain a
 * physical pad press takes. A tap on the "A · Launch" pill is a real A
 * press, in whatever context the user is in, forever, with no per-screen
 * wiring at all.
 *
 * A Compose [androidx.compose.ui.window.Dialog] hosts its own window, so
 * a bar inside one dispatches into that window -- the Quick Menu's own
 * key handling -- which is again exactly what the pad does.
 */
@Composable
fun rememberGamepadTouch(): (GamepadAction) -> Unit {
    val view = LocalView.current
    return remember(view) {
        { action: GamepadAction ->
            val code = GamepadKeyMap.keyCodeFor(action)
            if (code != KeyEvent.KEYCODE_UNKNOWN) {
                val target = view.rootView ?: view
                val now = android.os.SystemClock.uptimeMillis()
                // A real DOWN/UP pair: the shell's handlers read
                // KeyUp, and the Quick Menu's R2 toggle reads KeyDown.
                target.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
                target.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            }
        }
    }
}

/**
 * The help bar's own hints, made tappable. The bar is the shell's
 * existing legend for what the face buttons do right now; on a touch
 * screen the same list is the CONTROLS, since there are no face buttons
 * to press. One list, one meaning, two ways in.
 *
 * Horizontally scrollable because a narrow screen cannot fit five hints,
 * and dropping hints to make them fit would drop actions a touch user
 * has no other route to.
 */
@Composable
fun TouchHintBar(
    hints: List<Pair<GamepadAction, String>>,
    modifier: Modifier = Modifier,
    background: Color = Color(0xFF111111),
) {
    if (hints.isEmpty()) return
    val window = LocalShellWindow.current
    val press = rememberGamepadTouch()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = window.edgePadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (window.compact) 12.dp else 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { (action, label) ->
            TouchHint(action = action, label = label, onPress = { press(action) })
        }
    }
}

@Composable
private fun TouchHint(action: GamepadAction, label: String, onPress: () -> Unit) {
    val window = LocalShellWindow.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            // On a touch screen the hint is a button and has to be big
            // enough to hit; on the console it stays the compact legend
            // it always was.
            .then(if (window.touchFirst) Modifier.heightIn(min = window.minTouchTarget) else Modifier)
            .then(
                if (window.touchFirst) {
                    Modifier.border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onPress)
            .padding(horizontal = if (window.touchFirst) 12.dp else 0.dp, vertical = 4.dp),
    ) {
        Text(
            GamepadKeyMap.labelFor(action).takeIf { it.isNotBlank() } ?: label,
            color = Color.Black,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .background(Color.White, RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Text(label, color = Color.Gray, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Swipe a themed list the way the D-pad steps it.
 *
 * The carousel, textlist and grid all own their own cursor and move it
 * in whole steps -- they are not scrollable containers, so Compose's own
 * scroll gestures do not apply to them. This turns a drag into the same
 * whole steps: every [stepDistance] of travel is one call to [onStep],
 * sign carried, so a long flick moves several entries and a short drag
 * moves one. Cross-axis travel is ignored rather than fought over, which
 * is what lets a grid take horizontal drags as columns and vertical ones
 * as rows.
 *
 * @param horizontal how many steps one screen-x unit means (0 = this
 *   list does not move horizontally).
 * @param vertical the same for screen-y.
 */
fun Modifier.esDeSwipeSteps(
    horizontal: Int = 0,
    vertical: Int = 0,
    stepDistanceDp: Float = 48f,
    onStep: (Int) -> Unit,
): Modifier = composed {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val stepPx = with(density) { stepDistanceDp.dp.toPx() }
    pointerInput(horizontal, vertical, stepPx) {
        var accumulated = Offset.Zero
        detectDragGestures(
            onDragStart = { accumulated = Offset.Zero },
            onDragEnd = { accumulated = Offset.Zero },
            onDragCancel = { accumulated = Offset.Zero },
        ) { change, dragAmount ->
            accumulated += dragAmount
            if (horizontal != 0 && abs(accumulated.x) >= stepPx) {
                val steps = (accumulated.x / stepPx).toInt()
                accumulated = accumulated.copy(x = accumulated.x - steps * stepPx)
                // Dragging the content LEFT moves forward through it,
                // the same direction sense as flicking a page away.
                onStep(-steps * horizontal)
                change.consume()
            }
            if (vertical != 0 && abs(accumulated.y) >= stepPx) {
                val steps = (accumulated.y / stepPx).toInt()
                accumulated = accumulated.copy(y = accumulated.y - steps * stepPx)
                onStep(-steps * vertical)
                change.consume()
            }
        }
    }
}
