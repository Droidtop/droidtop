package dev.droidtop.shell.gamepad

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
                val downTime = android.os.SystemClock.uptimeMillis()
                val upTime = downTime + 16
                target.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, code, 0))
                target.dispatchKeyEvent(KeyEvent(downTime, upTime, KeyEvent.ACTION_UP, code, 0))
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
internal fun TouchHintBar(
    hints: List<Pair<GamepadAction, String>>,
    modifier: Modifier = Modifier,
    background: Color = MenuTokens.HintBar,
    // False for a small pill group placed in a corner rather than a bar.
    fill: Boolean = true,
) {
    if (hints.isEmpty()) return
    val window = LocalShellWindow.current
    val press = rememberGamepadTouch()
    BoxWithConstraints(modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier)) {
        // A bar narrower than the window (the Quick Menu's sheet) spaces its pills like a compact
        // window does: at the full window's spacing the sheet's four hints ran off its right edge,
        // "B Close" cut in half (console, build 1386). It still scrolls if even that does not fit.
        val tight = window.compact || maxWidth < TIGHT_HINT_BAR_WIDTH
        Row(
            modifier = Modifier
                .then(if (fill) Modifier.fillMaxWidth() else Modifier)
                .background(background)
                // A plate gets the frame's hairline on its top edge; over a
                // theme's own canvas (transparent) there is no plate and none.
                .then(if (background.alpha > 0f) Modifier.frameEdge(atTop = true) else Modifier)
                .horizontalScroll(rememberScrollState())
                .heightIn(min = window.frameBarHeight)
                .padding(horizontal = if (tight) minOf(window.barPadding, 12.dp) else window.barPadding),
            horizontalArrangement = Arrangement.spacedBy(if (tight) 10.dp else 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            hints.forEach { (action, label) ->
                TouchHint(action = action, label = label, onPress = { press(action) })
            }
        }
    }
}

/** Below this width a hint bar is a sheet's, not the window's, and packs its pills. */
private val TIGHT_HINT_BAR_WIDTH = 600.dp

@Composable
private fun TouchHint(action: GamepadAction, label: String, onPress: () -> Unit) {
    val window = LocalShellWindow.current
    // The tap target and the drawn chip are two different sizes (owner,
    // on the RP5 console, 2026-09-25: "the pills are also too big"): the
    // chip draws at MenuTokens.HintChipMinHeight on every window, touch
    // or not, and only a touch-first window wraps it in a bigger,
    // invisible Box sized to MenuTokens.HintTouchTarget -- the same 48dp
    // every other touch control on [ShellWindow] uses -- so shrinking the
    // chip never shrinks what a finger can hit.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            // The bar is a LEGEND that happens to be tappable, never a
            // place the cursor goes: `clickable` makes a node focusable,
            // so D-pad Down out of the last row of the PC grid landed in
            // here, where A does nothing at all and the way back is Up
            // (rig, build 546). One selection, and it stays on the
            // content -- `canFocus = false` ahead of the clickable in the
            // same chain makes exactly that node unfocusable while
            // leaving the tap intact.
            .focusProperties { canFocus = false }
            .then(if (window.touchFirst) Modifier.heightIn(min = MenuTokens.HintTouchTarget) else Modifier)
            .clickable(onClick = onPress),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = MenuTokens.HintChipMinHeight)
                .then(
                    if (window.touchFirst) {
                        Modifier.border(1.dp, MenuTokens.HintPillOutline, Corners.Pill)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = if (window.touchFirst) 8.dp else 0.dp, vertical = 2.dp),
        ) {
            Keycap(GamepadKeyMap.labelFor(action).takeIf { it.isNotBlank() } ?: label)
            Text(
                label,
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = MenuTokens.HintLabelTextSize),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The one button glyph the shell draws (docs/SPEC.md 7k): a keycap, round
 * for a single letter ("A") and a pill for a word ("Start", "L1"), filled
 * with the muted ink and lettered in the ground colour, so it reads as a
 * quieter thing than the label beside it, as Steam's footer glyphs do. The
 * hint bar's glyphs, the L1/R1 beside a strip and every other place that
 * names a button use this and nothing else. Not focusable, no tap target of
 * its own: the row it sits in is the control. [dimmed] is a glyph whose
 * press has nowhere to go (a strip at its end), drawn at half strength.
 * The keycap is ported in spirit from DroidDeck's non-focusable bumper caps
 * (ui/SettingsWidgets.kt at 9310d19).
 */
@Composable
internal fun Keycap(label: String, modifier: Modifier = Modifier, height: Dp = MenuTokens.KeycapHeight, dimmed: Boolean = false) {
    val style = MaterialTheme.typography.labelMedium.copy(
        fontSize = MenuTokens.HintGlyphTextSize,
        fontWeight = FontWeight.Bold,
    )
    Text(
        label,
        color = MenuTokens.Ground,
        style = style,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = modifier
            .graphicsLayer { alpha = if (dimmed) 0.5f else 1f }
            .background(MenuTokens.OnSurfaceMuted, Corners.Pill)
            .widthIn(min = height)
            .padding(horizontal = MenuTokens.HintGlyphPaddingHorizontal)
            .opticallyCentred(height, style.fontSize),
    )
}

/**
 * A tiny shoulder-button glyph ("L1"/"R1") beside a tab or section row it
 * switches, replacing a pill in the hint bar for the one thing every
 * screen with tabs does the same way (owner, 2026-09-25: "Can remove the
 * next/previous section pills"). It is a [Keycap], not a hint pill -- no
 * label and no tap target of its own -- because the row it sits beside
 * already IS the switch's visible state, and L1/R1 keep working exactly
 * as before; this only says which buttons step it. One composable, used
 * beside every tab row L1/R1 drives (the shell's top-level section tabs,
 * the Quick Menu's own Notifications/System tabs), so all of them read
 * the same way and none of them repeats the label text or the styling.
 */
@Composable
fun ShoulderGlyph(label: String, modifier: Modifier = Modifier, badge: Boolean = false, dimmed: Boolean = false) {
    // The strip's edition sits in a box as tall as the selected tab's pill,
    // centred the same way, so the two read on one line (tester, 2026-09-29,
    // Droidtop/tracker#157: "L1/R1 are smaller than the tab labels and look
    // misaligned with the pill"). One keycap style either way.
    if (badge) {
        Box(modifier.heightIn(min = MenuTokens.TabPillHeight), contentAlignment = Alignment.Center) {
            Keycap(label, dimmed = dimmed)
        }
    } else {
        Keycap(label, modifier, height = MenuTokens.KeycapHeightSmall, dimmed = dimmed)
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
